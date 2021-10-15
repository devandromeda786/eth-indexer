package com.rarible.protocol.order.core.service

import com.rarible.core.common.nowMillis
import com.rarible.core.common.optimisticLock
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.ethereum.listener.log.domain.LogEvent
import com.rarible.ethereum.listener.log.domain.LogEventStatus
import com.rarible.protocol.order.core.event.OrderListener
import com.rarible.protocol.order.core.event.OrderVersionListener
import com.rarible.protocol.order.core.model.*
import com.rarible.protocol.order.core.provider.ProtocolCommissionProvider
import com.rarible.protocol.order.core.repository.order.OrderRepository
import com.rarible.protocol.order.core.repository.order.OrderVersionRepository
import com.rarible.protocol.order.core.service.balance.AssetMakeBalanceProvider
import io.daonomic.rpc.domain.Word
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Service responsible for inserting or updating order state (see [save]).
 */
@Component
class OrderUpdateService(
    private val orderRepository: OrderRepository,
    private val assetMakeBalanceProvider: AssetMakeBalanceProvider,
    private val orderVersionRepository: OrderVersionRepository,
    private val orderReduceService: OrderReduceService,
    private val protocolCommissionProvider: ProtocolCommissionProvider,
    private val priceUpdateService: PriceUpdateService,
    private val orderVersionListener: OrderVersionListener,
    private val orderListener: OrderListener
) {
    private val logger = LoggerFactory.getLogger(OrderUpdateService::class.java)

    /**
     * Inserts a new order or updates an existing order with data from the [orderVersion].
     * Orders are identified by [OrderVersion.hash].
     *
     * [orderVersion] **must be** a valid order update having the same values for significant fields
     * (`data`, `start`, `end`, etc).
     * **Validation is not part of this function**. So make sure the source of order version updates is trustworthy.
     * On API level validation is performed in `OrderValidator.validate(existing: Order, update: OrderVersion)`.
     */
    suspend fun save(orderVersion: OrderVersion): Order {
        orderVersionRepository.save(orderVersion).awaitFirst()
        val order = optimisticLock { orderReduceService.updateOrder(orderVersion.hash) }
        checkNotNull(order) { "Order ${orderVersion.hash} has not been updated" }

        orderListener.onOrder(order)
        orderVersionListener.onOrderVersion(orderVersion)
        return order
    }

    suspend fun update(hash: Word) {
        val order = orderRepository.findById(hash)
        val updatedOrder = orderReduceService.updateOrder(hash)

        if (updatedOrder != null && order?.lastEventId != updatedOrder.lastEventId) {
            orderListener.onOrder(updatedOrder)
        }
    }

    /**
     * Updates the order's make stock and prices without calling the OrderReduceService.
     */
    suspend fun updateMakeStock(hash: Word, knownMakeBalance: EthUInt256? = null): Order? {
        val order = orderRepository.findById(hash) ?: return null

        val makeBalance = knownMakeBalance ?: assetMakeBalanceProvider.getMakeBalance(order) ?: EthUInt256.ZERO

        val protocolCommission = protocolCommissionProvider.get()
        val withNewMakeStock = order.withMakeBalance(makeBalance, protocolCommission)
        logger.info("Fetched makeBalance $makeBalance (knownMakeBalance=$knownMakeBalance, newMakeStock=${withNewMakeStock.makeStock}, oldMakeStock=${order.makeStock})")

        val updated = if (order.makeStock == EthUInt256.ZERO && withNewMakeStock.makeStock != EthUInt256.ZERO) {
            priceUpdateService.withUpdatedAllPrices(withNewMakeStock)
        } else {
            withNewMakeStock
        }
        return if (order.makeStock != updated.makeStock) {
            val savedOrder = orderRepository.save(updated)
            logger.info("Updated order ${savedOrder.hash}, makeStock=${savedOrder.makeStock}, makeBalance=$makeBalance")
            orderListener.onOrder(savedOrder)
            savedOrder
        } else {
            order
        }
    }

    suspend fun saveOrRemoveOnChainOrderVersions(logEvents: List<LogEvent>) {
        for (log in logEvents) {
            if (log.data is OnChainOrder) {
                val onChainOrder = log.data as OnChainOrder
                val onChainOrderKey = log.toLogEventKey()
                val orderVersion = onChainOrder.toOrderVersion(onChainOrderKey)
                    .run { priceUpdateService.withUpdatedAllPrices(this) }
                if (log.status == LogEventStatus.CONFIRMED) {
                    if (!orderVersionRepository.existsByOnChainOrderKey(onChainOrderKey).awaitFirst()) {
                        try {
                            orderVersionRepository.save(orderVersion).awaitFirst()
                        } catch (ignored: DuplicateKeyException) {
                        }
                    }
                } else {
                    orderVersionRepository.deleteByOnChainOrderKey(onChainOrderKey).awaitFirstOrNull()
                }
            }
        }
    }

    private fun OnChainOrder.toOrderVersion(onChainOrderKey: LogEventKey) =
        OrderVersion(
            maker = maker,
            taker = taker,
            make = make,
            take = take,
            makePriceUsd = null,
            takePriceUsd = null,
            makePrice = null,
            takePrice = null,
            makeUsd = null,
            takeUsd = null,
            createdAt = createdAt,
            platform = platform,
            type = orderType,
            salt = salt,
            start = start,
            end = end,
            data = data,
            signature = signature,
            hash = hash,
            onChainOrderKey = onChainOrderKey
        )
}
