package com.rarible.protocol.order.core.model

import com.rarible.ethereum.domain.EthUInt256

data class RaribleMatchedOrders(
    val left: SimpleOrder,
    val right: SimpleOrder
) {
    data class SimpleOrder(
        val data: OrderData,
        val salt: EthUInt256
    )
}
