package com.rarible.protocol.order.core.repository.auction

import com.rarible.core.mongo.util.div
import com.rarible.core.reduce.repository.DataRepository
import com.rarible.protocol.dto.Continuation
import com.rarible.protocol.order.core.misc.isSingleton
import com.rarible.protocol.order.core.model.*
import com.rarible.protocol.order.core.repository.auction.AuctionRepository.AuctionIndexes.ALL_INDEXES
import com.rarible.protocol.order.core.repository.auction.AuctionRepository.AuctionIndexes.BY_LAST_UPDATE_AND_ID_DEFINITION
import io.daonomic.rpc.domain.Word
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.bson.Document
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query
import org.springframework.data.mongodb.core.query.*
import scalether.domain.Address

class AuctionRepository(
    private val template: ReactiveMongoTemplate
) : DataRepository<Auction> {

    suspend fun createIndexes() {
        ALL_INDEXES.forEach { index ->
            template.indexOps(Auction::class.java).ensureIndex(index).awaitFirst()
        }
    }

    suspend fun save(data: Auction): Auction {
        return template.save(data).awaitFirst()
    }

    override suspend fun saveReduceResult(data: Auction) {
        save(data)
    }

    suspend fun findById(hash: Word): Auction? {
        return template.findById<Auction>(hash).awaitFirstOrNull()
    }

    suspend fun search(filter: AuctionFilter, size: Int, continuation: String?): List<Auction> {
        val query = filter.toQuery(continuation, size)
        return template.query<Auction>()
            .matching(query)
            .all()
            .collectList()
            .awaitFirst()
    }

    private fun AuctionFilter.toQuery(continuation: String?, size: Int): Query {
        val (criteria, hint) = when (this) {
            is AuctionFilter.All -> getCriteria() withHint BY_LAST_UPDATE_AND_ID_DEFINITION.indexKeys
            is AuctionFilter.ByItem -> getCriteria().withNoHint()
            is AuctionFilter.ByCollection -> getCriteria().withNoHint()
            is AuctionFilter.BySeller -> getCriteria().withNoHint()
        }
        val query = Query(
            criteria
                .forPlatform(platform)
                .forStatus(status)
                .fromOrigin(origin)
                .fromCurrency(currency)
                .scrollTo(continuation, sort, currency)
        ).limit(size).with(sort(sort, currency))

        if (hint != null) {
            query.withHint(hint)
        }
        return query
    }

    private infix fun Criteria.fromOrigin(origin: Address?) = origin?.let {
        and(Auction::data / RaribleAuctionV1DataV1::originFees)
            .elemMatch(Criteria.where(Part::account.name).isEqualTo(origin))
    } ?: this

    private infix fun Criteria.fromCurrency(currency: Address?) = currency?.let {
        and(Auction::buy / Erc20AssetType::token).isEqualTo(currency)
    } ?: this

    private infix fun Criteria.forPlatform(platform: List<Platform>) = let {
        if (platform.isNotEmpty()) and(Auction::platform).inValues(platform) else this
    }

    private infix fun Criteria.forStatus(status: List<AuctionStatus>?) = let {
        when {
            status == null || status.isEmpty() -> this
            status.isSingleton -> and(Auction::status).isEqualTo(status.single())
            else -> and(Auction::status).inValues(status)
        }
    }

    private fun AuctionFilter.ByItem.getCriteria(): Criteria {
        var criteria = (Auction::sell / Asset::type / NftAssetType::token).isEqualTo(token)
            .and(Auction::sell / Asset::type / NftAssetType::tokenId).isEqualTo(tokenId)

        if (seller != null) {
            criteria = criteria.and(Auction::seller).isEqualTo(seller)
        }
        return criteria
    }

    private fun AuctionFilter.ByCollection.getCriteria(): Criteria {
        var criteria = (Auction::sell / Asset::type / NftAssetType::token).isEqualTo(token)

        if (seller != null) {
            criteria = criteria.and(Auction::seller).isEqualTo(seller)
        }
        return criteria
    }

    private fun AuctionFilter.BySeller.getCriteria(): Criteria {
        return (Auction::seller).isEqualTo(seller)
    }

    private fun getCriteria(): Criteria {
        return Criteria()
    }

    private fun sort(sort: AuctionFilter.AuctionSort, currency: Address?): Sort {
        return when (sort) {
            AuctionFilter.AuctionSort.LAST_UPDATE_DESC -> Sort.by(
                Sort.Direction.DESC,
                Auction::lastUpdateAt.name,
                Auction::hash.name
            )
            AuctionFilter.AuctionSort.LAST_UPDATE_ASC -> Sort.by(
                Sort.Direction.ASC,
                Auction::lastUpdateAt.name,
                Auction::hash.name
            )
            AuctionFilter.AuctionSort.BUY_PRICE_ASC -> Sort.by(
                Sort.Direction.ASC,
                (currency?.let { Auction::buyPrice } ?: Auction::buyPriceUsd).name,
                Auction::hash.name
            )
        }
    }

    private fun Criteria.scrollTo(continuation: String?, sort: AuctionFilter.AuctionSort, currency: Address?) =
        when (sort) {
            AuctionFilter.AuctionSort.LAST_UPDATE_DESC -> {
                val lastDate = Continuation.parse<Continuation.LastDate>(continuation)
                lastDate?.let { c ->
                    this.orOperator(
                        Auction::lastUpdateAt lt c.afterDate,
                        Criteria().andOperator(
                            Auction::lastUpdateAt isEqualTo c.afterDate,
                            Auction::hash lt c.afterId
                        )
                    )
                } ?: this
            }
            AuctionFilter.AuctionSort.LAST_UPDATE_ASC -> {
                val lastDate = Continuation.parse<Continuation.LastDate>(continuation)
                lastDate?.let { c ->
                    this.orOperator(
                        Auction::lastUpdateAt gt c.afterDate,
                        Criteria().andOperator(
                            Auction::lastUpdateAt isEqualTo c.afterDate,
                            Auction::hash gt c.afterId
                        )
                    )
                } ?: this
            }
            AuctionFilter.AuctionSort.BUY_PRICE_ASC -> {
                val price = Continuation.parse<Continuation.Price>(continuation)
                price?.let { c ->
                    if (currency != null) {
                        this.orOperator(
                            Auction::buyPrice gt c.afterPrice,
                            Criteria().andOperator(
                                Auction::buyPrice isEqualTo c.afterPrice,
                                Auction::hash gt c.afterId
                            )
                        )
                    } else {
                        this.orOperator(
                            Auction::buyPriceUsd gt c.afterPrice,
                            Criteria().andOperator(
                                Auction::buyPriceUsd isEqualTo c.afterPrice,
                                Auction::hash gt c.afterId
                            )
                        )
                    }
                } ?: this
            }
        }

    private infix fun Criteria.withHint(index: Document) = Pair(this, index)

    private fun Criteria.withNoHint() = Pair<Criteria, Document?>(this, null)

    private object AuctionIndexes {
        val BY_LAST_UPDATE_AND_ID_DEFINITION: Index = Index()
            .on(Auction::lastUpdateAt.name, Sort.Direction.ASC)
            .on("_id", Sort.Direction.ASC)
            .background()

        val ALL_INDEXES = listOf(
            BY_LAST_UPDATE_AND_ID_DEFINITION
        )
    }
}

