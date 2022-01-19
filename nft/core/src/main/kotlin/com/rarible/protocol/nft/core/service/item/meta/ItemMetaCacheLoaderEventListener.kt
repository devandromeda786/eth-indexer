package com.rarible.protocol.nft.core.service.item.meta

import com.rarible.core.common.convert
import com.rarible.core.loader.LoadTaskStatus
import com.rarible.loader.cache.CacheEntry
import com.rarible.loader.cache.CacheLoaderEvent
import com.rarible.loader.cache.CacheLoaderEventListener
import com.rarible.protocol.dto.NftItemEventDto
import com.rarible.protocol.nft.core.model.ExtendedItem
import com.rarible.protocol.nft.core.model.ItemId
import com.rarible.protocol.nft.core.model.ItemMeta
import com.rarible.protocol.nft.core.producer.ProtocolNftEventPublisher
import com.rarible.protocol.nft.core.repository.item.ItemRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Component

/**
 * Event listener of the underlying items' metadata [ItemMeta] loading infrastructure
 * that listens to [CacheLoaderEvent]s and sends item update events [NftItemEventDto].
 *
 * - when initial loading of item's metadata succeeds, we send item update event with this meta
 * - if the initial loading fails, we send item update event with empty meta
 * - if an item's metadata is updated, we send item update event with the new meta
 * - if an item's metadata update fails, we ignore that event
 */
@Component
class ItemMetaCacheLoaderEventListener(
    private val itemRepository: ItemRepository,
    private val protocolNftEventPublisher: ProtocolNftEventPublisher,
    private val conversionService: ConversionService,
    private val itemUpdateEventAssertQueue: ItemUpdateEventAssertQueue
) : CacheLoaderEventListener<ItemMeta> {

    override val type get() = ItemMetaCacheLoader.TYPE

    override suspend fun onEvent(cacheLoaderEvent: CacheLoaderEvent<ItemMeta>) {
        val itemId = ItemId.parseId(cacheLoaderEvent.key)
        val item = itemRepository.findById(itemId).awaitFirstOrNull() ?: return
        val meta = when (val cacheEntry = cacheLoaderEvent.cacheEntry) {
            is CacheEntry.Loaded -> {
                logMetaLoading(itemId, "loaded meta")
                cacheEntry.data
            }
            is CacheEntry.InitialFailed -> {
                logMetaLoading(itemId, "initial loading failed: ${cacheEntry.failedStatus.errorMessage}")
                // Send 'null' because the initial loading has failed.
                null
            }
            is CacheEntry.LoadedAndUpdateFailed -> {
                logMetaLoading(itemId, "update failed: ${cacheEntry.failedUpdateStatus.errorMessage}")
                // Update has failed => no need to send previous data.
                return
            }
            is CacheEntry.LoadedAndUpdateScheduled -> {
                logMetaLoading(itemId, "update was scheduled")
                // Update has been scheduled => no need to send previous data.
                return
            }
            is CacheEntry.InitialLoadScheduled -> {
                logMetaLoading(itemId, "loading scheduled")
                when (cacheEntry.loadStatus) {
                    is LoadTaskStatus.Scheduled -> {
                        // Initial loading has been scheduled => nothing to send yet.
                        return
                    }
                    is LoadTaskStatus.WaitsForRetry -> {
                        // Initial loading has failed and waiting for retry => send at least 'null' meta for now.
                        null
                    }
                }
            }
            is CacheEntry.NotAvailable -> {
                logMetaLoading(itemId, "meta was removed")
                // Nothing to send.
                return
            }
        }
        val itemEventDto = conversionService.convert<NftItemEventDto>(ExtendedItem(item, meta))
        protocolNftEventPublisher.publish(itemEventDto)
        itemUpdateEventAssertQueue.onItemEventSent(itemId)
    }
}