package com.rarible.protocol.order.listener.service.descriptors.exchange.v2

import com.rarible.ethereum.listener.log.LogEventDescriptor
import com.rarible.protocol.contracts.exchange.v2.events.CancelEventDeprecated
import com.rarible.protocol.order.core.configuration.OrderIndexerProperties
import com.rarible.protocol.order.core.model.HistorySource
import com.rarible.protocol.order.core.model.OrderCancel
import com.rarible.protocol.order.core.repository.exchange.ExchangeHistoryRepository
import com.rarible.protocol.order.core.repository.order.OrderRepository
import io.daonomic.rpc.domain.Word
import kotlinx.coroutines.reactor.mono
import org.reactivestreams.Publisher
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import scalether.domain.Address
import scalether.domain.response.Log
import scalether.domain.response.Transaction
import java.time.Instant

@Service
class ExchangeV2CancelDeprecatedDescriptor(
    exchangeContractAddresses: OrderIndexerProperties.ExchangeContractAddresses,
    private val orderRepository: OrderRepository
): LogEventDescriptor<OrderCancel> {

    private val exchangeContract = exchangeContractAddresses.v2

    override val collection: String = ExchangeHistoryRepository.COLLECTION

    override val topic: Word = CancelEventDeprecated.id()

    override fun convert(log: Log, transaction: Transaction, timestamp: Long): Publisher<OrderCancel> {
        return mono { listOfNotNull(convert(log, Instant.ofEpochSecond(timestamp))) }.flatMapMany { it.toFlux() }
    }

    override fun getAddresses(): Mono<Collection<Address>> = Mono.just(setOf(exchangeContract))

    private suspend fun convert(log: Log, date: Instant): OrderCancel {
        val event = CancelEventDeprecated.apply(log)
        val hash = Word.apply(event.hash())
        val order = orderRepository.findById(hash)

        return OrderCancel(
            hash = hash,
            make = order?.make,
            take = order?.take,
            date = date,
            maker = order?.maker,
            source = HistorySource.RARIBLE
        )
    }
}
