package com.rarible.protocol.nft.core.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rarible.protocol.nft.core.model.CryptoPunksMeta
import com.rarible.protocol.nft.core.repository.CryptoPunksRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.math.BigInteger

@Service
class CryptoPunksMetaService(
    val repository: CryptoPunksRepository,
    val mapper: ObjectMapper
) {

    fun get(id: BigInteger): Mono<CryptoPunksMeta> {
        return repository.findById(id)
    }

    suspend fun save(punk: CryptoPunksMeta): CryptoPunksMeta? {
        return repository.save(punk).awaitFirstOrNull()
    }
}
