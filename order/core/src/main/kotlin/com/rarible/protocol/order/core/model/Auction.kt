package com.rarible.protocol.order.core.model

import com.rarible.ethereum.domain.EthUInt256
import com.rarible.protocol.contracts.Tuples
import io.daonomic.rpc.domain.Word
import org.springframework.data.annotation.AccessType
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.mongodb.core.mapping.Document
import scala.Tuple2
import scala.Tuple6
import scalether.domain.Address
import java.time.Instant

@Document("auction")
data class Auction(
    val type: AuctionType,
    val status: AuctionStatus,
    val seller: Address,
    val buyer: Address?,
    val sell: Asset,
    val buy: AssetType,
    val lastBid: Bid?,
    val endTime: Instant?,
    val minimalStep: EthUInt256,
    val minimalPrice: EthUInt256,
    val finished: Boolean,
    val canceled: Boolean,
    val data: AuctionData,
    val createdAt: Instant,
    val lastUpdatedAy: Instant,
    val auctionId: EthUInt256,
    val protocolFee: EthUInt256,
    val contract: Address,
    val pending: List<AuctionHistory>
) {
    @Transient
    private val _id: Word = hashKey(this)

    @get:Id
    @get:AccessType(AccessType.Type.PROPERTY)
    var hash: Word
        get() = _id
        set(_) {}

    fun forTx() = Tuple6(
        sell.forTx(),
        buy.forTx(),
        minimalStep.value,
        minimalPrice.value,
        data.getDataVersion(),
        data.toEthereum().bytes()
    )

    companion object {
        fun hashKey(auction: Auction): Word {
            return when (auction.type) {
                AuctionType.RARIBLE_V1 -> raribleV1HashKey(auction.contract, auction.auctionId)
            }
        }

        fun raribleV1HashKey(auction: Address, auctionId: EthUInt256): Word {
            return Tuples.keccak256(
                Tuples.raribleAuctionKeyHashType().encode(
                    Tuple2(
                        auction,
                        auctionId.value
                    )
                )
            )
        }
    }
}
