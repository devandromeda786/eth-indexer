package com.rarible.protocol.nft.core.service.item.meta.descriptors

import com.rarible.core.apm.CaptureSpan
import com.rarible.protocol.nft.core.model.ItemId
import com.rarible.protocol.nft.core.model.ItemProperties
import com.rarible.protocol.nft.core.model.PendingLogItemProperties
import com.rarible.protocol.nft.core.repository.PendingLogItemPropertiesRepository
import com.rarible.protocol.nft.core.repository.item.ItemRepository
import com.rarible.protocol.nft.core.service.item.meta.ItemPropertiesResolver
import com.rarible.protocol.nft.core.service.item.meta.ItemPropertiesService
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import scalether.domain.Address

@Component
@CaptureSpan(type = META_CAPTURE_SPAN_TYPE)
class PendingLogItemPropertiesResolver(
    private val pendingLogItemPropertiesRepository: PendingLogItemPropertiesRepository,
    private val itemRepository: ItemRepository,
    private val rariblePropertiesResolver: RariblePropertiesResolver,
    @Value("\${pending.log.item.properties.resolver.ttl:300000}") private val pendingLogTTL: Long
) : ItemPropertiesResolver {

    override val name: String get() = "Pending"

    override val maxAge: Long get() = pendingLogTTL

    suspend fun savePendingLogItemPropertiesByUri(itemId: ItemId, uri: String) {
        try {
            val itemProperties = rariblePropertiesResolver.resolveByTokenUri(itemId, uri) ?: return
            val pendingLogItemProperties = PendingLogItemProperties(itemId.decimalStringValue, itemProperties)
            pendingLogItemPropertiesRepository.save(pendingLogItemProperties).awaitFirstOrNull()
        } catch (e: Exception) {
            ItemPropertiesService.logProperties(itemId, "failed to save pending log item properties", warn = true)
        }
    }

    override suspend fun resolve(itemId: ItemId): ItemProperties? {
        val item = itemRepository.findById(itemId).awaitFirstOrNull() ?: return null
        val isPendingMinting = item.pending.any { it.from == Address.ZERO() }
        if (!isPendingMinting) {
            ItemPropertiesService.logProperties(itemId, "removing properties of an already confirmed item")
            // Minted items must provide properties from the contract.
            // This resolver is applicable only while the item is in pending minting state.
            // Cleanup the
            pendingLogItemPropertiesRepository.deleteById(itemId.decimalStringValue).awaitFirstOrNull()
            return null
        }
        return pendingLogItemPropertiesRepository.findById(itemId.decimalStringValue)
            .onErrorResume { Mono.empty() }
            .awaitFirstOrNull()?.value
    }
}
