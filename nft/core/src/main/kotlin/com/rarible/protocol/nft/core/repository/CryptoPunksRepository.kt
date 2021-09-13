package com.rarible.protocol.nft.core.repository

import com.rarible.protocol.nft.core.model.CryptoPunksMeta
import com.rarible.protocol.nft.core.repository.item.ItemPropertyRepository
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.math.BigInteger

@Component
class CryptoPunksRepository(private val mongo: ReactiveMongoOperations) {

    fun findById(id: BigInteger): Mono<CryptoPunksMeta> {
        return mongo.findById(id)
    }

    fun save(punk: CryptoPunksMeta): Mono<CryptoPunksMeta> {
        return mongo.save(punk)
    }

}
