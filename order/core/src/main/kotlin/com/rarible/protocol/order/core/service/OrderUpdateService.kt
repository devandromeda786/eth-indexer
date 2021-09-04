package com.rarible.protocol.order.core.service

import com.rarible.core.common.nowMillis
import com.rarible.core.common.optimisticLock
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.protocol.order.core.event.OrderVersionListener
import com.rarible.protocol.order.core.model.Order
import com.rarible.protocol.order.core.model.OrderVersion
import com.rarible.protocol.order.core.provider.ProtocolCommissionProvider
import com.rarible.protocol.order.core.repository.order.OrderRepository
import com.rarible.protocol.order.core.repository.order.OrderVersionRepository
import com.rarible.protocol.order.core.service.asset.AssetBalanceProvider
import io.daonomic.rpc.domain.Word
import kotlinx.coroutines.reactive.awaitFirst
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OrderUpdateService(
    private val orderRepository: OrderRepository,
    private val assetBalanceProvider: AssetBalanceProvider,
    private val orderVersionRepository: OrderVersionRepository,
    private val orderReduceService: OrderReduceService,
    private val protocolCommissionProvider: ProtocolCommissionProvider,
    private val priceUpdateService: PriceUpdateService,
    private val orderVersionListener: OrderVersionListener
) {
    private val logger = LoggerFactory.getLogger(OrderUpdateService::class.java)

    suspend fun save(orderVersion: OrderVersion): Order {
        orderVersionRepository.save(orderVersion).awaitFirst()
        val order = optimisticLock { orderReduceService.updateOrder(orderVersion.hash) }
        orderVersionListener.onOrderVersion(orderVersion)
        return order
    }

    /**
     * Updates the order's make stock and prices without calling the OrderReduceService.
     */
    suspend fun updateMakeStock(hash: Word, knownMakeBalance: EthUInt256? = null): Order? = optimisticLock {
        val order = orderRepository.findById(hash) ?: return@optimisticLock null
        val makeBalance = knownMakeBalance ?: assetBalanceProvider.getAssetStock(order.maker, order.make.type) ?: EthUInt256.ZERO
        val protocolCommission = protocolCommissionProvider.get()
        val withNewBalance = order.withMakeBalance(makeBalance, protocolCommission)
        val updated = if (order.makeStock == EthUInt256.ZERO && withNewBalance.makeStock != EthUInt256.ZERO) {
            priceUpdateService.updateOrderPrice(withNewBalance, nowMillis())
        } else {
            withNewBalance
        }
        logger.info("Updated order ${updated.hash}, makeStock=${updated.makeStock}, makeBalance=$makeBalance")
        return@optimisticLock orderRepository.save(updated)
    }
}