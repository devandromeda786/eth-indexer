package com.rarible.protocol.order.core.converters.dto

import com.rarible.ethereum.domain.EthUInt256
import com.rarible.ethereum.listener.log.domain.LogEvent
import com.rarible.protocol.dto.*
import com.rarible.protocol.order.core.model.*
import com.rarible.protocol.order.core.repository.order.OrderRepository
import com.rarible.protocol.order.core.service.PriceNormalizer
import io.daonomic.rpc.domain.Word
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class OrderActivityConverter(
    private val priceNormalizer: PriceNormalizer,
    private val assetDtoConverter: AssetDtoConverter,
    private val orderRepository: OrderRepository
) {

    suspend fun convert(ar: ActivityResult): OrderActivityDto? {
        return when (ar) {
            is ActivityResult.History -> convertHistory(ar.value)
            is ActivityResult.Version -> convertVersion(ar.value)
        }
    }

    private suspend fun convertHistory(history: LogEvent): OrderActivityDto? {
        val transactionHash = history.transactionHash
        val blockHash = history.blockHash ?: DEFAULT_BLOCK_HASH
        val blockNumber = history.blockNumber ?: DEFAULT_BLOCK_NUMBER
        val logIndex = history.logIndex ?: DEFAULT_LOG_INDEX
        val data = history.data as OrderExchangeHistory

        if (data.maker == null || data.make == null || data.take == null) {
            return null
        }

        return when (data) {
            is OrderSideMatch -> {
                // TODO: we will re-implement this using a dedicated field in OrderSideMatch event.
                val leftType = if (orderRepository.findById(data.hash) != null) {
                    if (data.isBid()) {
                        OrderActivityMatchSideDto.Type.BID
                    } else {
                        OrderActivityMatchSideDto.Type.SELL
                    }
                } else {
                    null
                }
                val rightType = if (data.counterHash?.let { orderRepository.findById(it) } != null) {
                    if (data.isBid()) {
                        OrderActivityMatchSideDto.Type.SELL
                    } else {
                        OrderActivityMatchSideDto.Type.BID
                    }
                } else {
                    null
                }
                OrderActivityMatchDto(
                    id = history.id.toString(),
                    date = data.date,
                    left = OrderActivityMatchSideDto(
                        maker = data.maker,
                        asset = assetDtoConverter.convert(data.make),
                        hash = data.hash,
                        type = leftType
                    ),
                    right = OrderActivityMatchSideDto(
                        maker = data.taker,
                        asset = assetDtoConverter.convert(data.take),
                        hash = data.counterHash ?: Word.apply(ByteArray(32)),
                        type = rightType
                    ),
                    price = if (data.isBid()) {
                        price(data.make, data.take /* NFT */)
                    } else {
                        price(data.take, data.make /* NFT */)
                    },
                    priceUsd = data.takePriceUsd ?: data.makePriceUsd,
                    transactionHash = transactionHash,
                    blockHash = blockHash,
                    blockNumber = blockNumber,
                    logIndex = logIndex,
                    source = convert(data.source)
                )
            }
            is OrderCancel -> if (data.isBid()) {
                OrderActivityCancelBidDto(
                    id = history.id.toString(),
                    hash = data.hash,
                    maker = data.maker!!,
                    make = AssetTypeDtoConverter.convert(data.make!!.type),
                    take = AssetTypeDtoConverter.convert(data.take!!.type),
                    date = data.date,
                    transactionHash = transactionHash,
                    blockHash = blockHash,
                    blockNumber = blockNumber,
                    logIndex = logIndex,
                    source = convert(data.source)
                )
            } else {
                OrderActivityCancelListDto(
                    id = history.id.toString(),
                    hash = data.hash,
                    maker = data.maker!!,
                    make = AssetTypeDtoConverter.convert(data.make!!.type),
                    take = AssetTypeDtoConverter.convert(data.take!!.type),
                    date = data.date,
                    transactionHash = transactionHash,
                    blockHash = blockHash,
                    blockNumber = blockNumber,
                    logIndex = logIndex,
                    source = convert(data.source)
                )
            }
            is OnChainOrder -> if (data.isBid()) {
                OrderActivityBidDto(
                    date = data.date,
                    id = history.id.toString(),
                    hash = data.hash,
                    maker = data.maker,
                    make = assetDtoConverter.convert(data.make),
                    take = assetDtoConverter.convert(data.take),
                    price = price(data.make, data.take),
                    source = convert(data.source),
                    priceUsd = data.priceUsd
                )
            } else if (data.taker != null) {
                //TODO[punk]: Sell orders (as for CryptoPunks sell orders) which are dedicated to only a concrete address (via "offer for sale to address" method call)
                // are not supported by frontend, and thus the backend should not return them.
                null
            } else {
                OrderActivityListDto(
                    date = data.date,
                    id = history.id.toString(),
                    hash = data.hash,
                    maker = data.maker,
                    make = assetDtoConverter.convert(data.make),
                    take = assetDtoConverter.convert(data.take),
                    price = price(data.take, data.make),
                    source = convert(data.source),
                    priceUsd = data.priceUsd
                )
            }
        }
    }

    private suspend fun convertVersion(version: OrderVersion): OrderActivityDto {
        return when {
            version.isBid() -> OrderActivityBidDto(
                date = version.createdAt,
                id = version.id.toString(),
                hash = version.hash,
                maker = version.maker,
                make = assetDtoConverter.convert(version.make),
                take = assetDtoConverter.convert(version.take),
                price = price(version.make, version.take),
                priceUsd = version.takePriceUsd ?: version.makePriceUsd,
                source = convert(version.platform)
            )
            else -> OrderActivityListDto(
                date = version.createdAt,
                id = version.id.toString(),
                hash = version.hash,
                maker = version.maker,
                make = assetDtoConverter.convert(version.make),
                take = assetDtoConverter.convert(version.take),
                price = price(version.take, version.make),
                priceUsd = version.takePriceUsd ?: version.makePriceUsd,
                source = convert(version.platform)
            )
        }
    }

    private fun convert(source: Platform): OrderActivityDto.Source {
        return when (source) {
            Platform.RARIBLE -> OrderActivityDto.Source.RARIBLE
            Platform.OPEN_SEA -> OrderActivityDto.Source.OPEN_SEA
            Platform.CRYPTO_PUNKS -> OrderActivityDto.Source.CRYPTO_PUNKS
        }
    }

    private fun convert(source: HistorySource): OrderActivityDto.Source {
        return when (source) {
            HistorySource.RARIBLE -> OrderActivityDto.Source.RARIBLE
            HistorySource.OPEN_SEA -> OrderActivityDto.Source.OPEN_SEA
            HistorySource.CRYPTO_PUNKS -> OrderActivityDto.Source.CRYPTO_PUNKS
        }
    }

    private suspend fun price(left: Asset, right: Asset): BigDecimal {
        return if (right.value != EthUInt256.ZERO) {
            priceNormalizer.normalize(left) / priceNormalizer.normalize(right)
        } else BigDecimal.ZERO
    }

    private companion object {
        val DEFAULT_BLOCK_HASH: Word = Word.apply(ByteArray(32))
        const val DEFAULT_BLOCK_NUMBER: Long = 0
        const val DEFAULT_LOG_INDEX: Int = 0
    }
}
