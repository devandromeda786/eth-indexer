package com.rarible.protocol.order.listener.service.opensea

import com.rarible.opensea.client.OpenSeaClient
import com.rarible.opensea.client.model.*
import com.rarible.protocol.order.listener.configuration.OrderListenerProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component
import java.lang.Long.min
import java.time.Instant

@Component
class OpenSeaOrderService(
    private val openSeaClient: OpenSeaClient,
    properties: OrderListenerProperties
) {
    private val loadOpenSeaPeriod = properties.loadOpenSeaPeriod.seconds

    suspend fun getNextOrdersBatch(listedAfter: Long, listedBefore: Long): List<OpenSeaOrder> = coroutineScope {
        val batches = (listedBefore - listedAfter - 1) / loadOpenSeaPeriod
        assert(batches >= 0) { "OpenSea batch count must be positive" }

        (0..batches).map {
            async {
                val nextListedAfter = listedAfter + (it * loadOpenSeaPeriod)
                val nextListedBefore = min(listedAfter + ((it + 1) * loadOpenSeaPeriod), listedBefore)
                getNextOrders(nextListedAfter, nextListedBefore)
            }
        }.awaitAll().flatten()
    }

    suspend fun getNextOrders(listedAfter: Long, listedBefore: Long): List<OpenSeaOrder> {
        val orders = mutableListOf<OpenSeaOrder>()

        do {
            val request = OrdersRequest(
                listedAfter = Instant.ofEpochSecond(listedAfter),
                listedBefore = Instant.ofEpochSecond(listedBefore),
                offset = orders.size,
                sortBy = SortBy.CREATED_DATE,
                sortDirection = SortDirection.ASC,
                limit = null,
                side = null
            )
            val result = getOrders(request)

            orders.addAll(result)
        } while (result.isNotEmpty() && orders.size <= MAX_OFFSET)

        return orders
    }

    private suspend fun getOrders(request: OrdersRequest): List<OpenSeaOrder> {
        var lastError: OpenSeaError? = null
        var retries = 0

        while (retries++ < MAX_RETRIES) {
            when (val result = openSeaClient.getOrders(request)) {
                is OperationResult.Success -> return result.result.orders
                is OperationResult.Fail -> lastError = result.error
            }
        }
        throw IllegalStateException("Can't fetch OpenSea orders, number of attempts exceeded, last error: $lastError")
    }

    companion object {
        const val MAX_OFFSET = 10000
        const val MAX_RETRIES = 5
    }
}

