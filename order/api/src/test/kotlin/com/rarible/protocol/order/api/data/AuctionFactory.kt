package com.rarible.protocol.order.api.data

import com.rarible.core.test.data.randomAddress
import com.rarible.core.test.data.randomBigDecimal
import com.rarible.core.test.data.randomBigInt
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.protocol.order.core.model.*
import java.math.BigDecimal
import java.time.Instant

fun randomAuction(): Auction {
    return Auction(
        type = AuctionType.RARIBLE_V1,
        status = AuctionStatus.ACTIVE,
        seller = randomAddress(),
        buyer = randomAddress(),
        sell = Asset(EthAssetType, EthUInt256.of(randomBigInt())),
        buy = EthAssetType,
        lastBid = null,
        endTime = Instant.EPOCH,
        minimalStep = EthUInt256.ZERO,
        minimalPrice = EthUInt256.ZERO,
        finished = false,
        cancelled = false,
        data = randomAuctionV1DataV1(),
        createdAt = Instant.EPOCH,
        lastUpdateAt = Instant.EPOCH,
        lastEventId = null,
        auctionId = EthUInt256.of(randomBigInt()),
        protocolFee = EthUInt256.ZERO,
        contract = randomAddress(),
        pending = emptyList(),
        buyPrice = randomBigDecimal(),
        buyPriceUsd = randomBigDecimal(),
        platform = Platform.RARIBLE
    )
}

fun randomAuctionV1DataV1(): RaribleAuctionV1DataV1 {
    return RaribleAuctionV1DataV1(
        originFees = emptyList(),
        payouts = emptyList(),
        duration = EthUInt256.ZERO,
        startTime = EthUInt256.ZERO,
        buyOutPrice = EthUInt256.ZERO
    )
}
