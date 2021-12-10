package com.rarible.protocol.nft.core.service.item

import com.rarible.ethereum.domain.EthUInt256
import com.rarible.ethereum.listener.log.domain.LogEvent
import com.rarible.ethereum.listener.log.domain.LogEventStatus
import com.rarible.protocol.nft.core.converters.model.ItemEventConverter
import com.rarible.protocol.nft.core.converters.model.OwnershipEventConverter
import com.rarible.protocol.nft.core.model.*
import com.rarible.protocol.nft.core.repository.history.LazyNftItemHistoryRepository
import com.rarible.protocol.nft.core.repository.history.NftItemHistoryRepository
import com.rarible.protocol.nft.core.service.composit.CompositeTaskReduceService
import io.daonomic.rpc.domain.Word
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.flux
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import scalether.domain.Address

@Component
@ExperimentalCoroutinesApi
@Profile("reduce-v2")
class ItemReduceServiceV2(
    private val skipTokens: ReduceSkipTokens,
    private val compositeTaskReduceService: CompositeTaskReduceService,
    private val historyRepository: NftItemHistoryRepository,
    private val lazyHistoryRepository: LazyNftItemHistoryRepository
) : ItemReduceService {

    override fun onItemHistories(logs: List<LogEvent>): Mono<Void> {
        logger.info("onHistories ${logs.size} logs")
        return logs.toFlux()
            .map { it.data as ItemHistory }
            .map { history -> ItemId(history.token, history.tokenId) }
            .filter { skipTokens.allowReducing(it.token, it.tokenId) }
            .flatMap { update(token = it.token, tokenId = it.tokenId, from = null) }
            .then()
    }

    @OptIn(FlowPreview::class)
    override fun update(token: Address?, tokenId: EthUInt256?, from: ItemId?): Flux<ItemId> = flux {
        logger.info("Update token=$token, tokenId=$tokenId")
        val events = Flux.mergeComparing(
            compareBy<HistoryLog>(
                { it.item.token.toString() },
                { it.item.tokenId },
                { it.log.blockNumber },
                { it.log.logIndex }
            ),
            findLazyItemsHistory(token, tokenId, from),
            historyRepository.findItemsHistory(token, tokenId, from)
        ).map {
            CompositeEvent(
                itemEvent = ItemEventConverter.convert(it.log),
                ownershipEvents = OwnershipEventConverter.convert(it.log)
            )
        }
        compositeTaskReduceService.reduce(events.asFlow()).collect { entity ->
            entity.id.itemId()?.let { send(it) }
        }
    }

    private fun findLazyItemsHistory(token: Address?, tokenId: EthUInt256?, from: ItemId?): Flux<HistoryLog> {
        return lazyHistoryRepository.find(token, tokenId, from).map {
            HistoryLog(
                item = it,
                log = LogEvent(
                    data = it,
                    address = Address.ZERO(),
                    topic = Word.apply(ByteArray(32)),
                    transactionHash = Word.apply(ByteArray(32)),
                    status = LogEventStatus.CONFIRMED,
                    blockNumber = -1,
                    logIndex = -1,
                    index = 0,
                    minorLogIndex = 0
                )
            )
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ItemReduceServiceV2::class.java)
    }
}
