package com.rarible.protocol.nft.core.service.ownership

import com.rarible.core.common.orNull
import com.rarible.core.common.toOptional
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.protocol.nft.core.model.Ownership
import com.rarible.protocol.nft.core.model.OwnershipId
import com.rarible.protocol.nft.core.model.OwnershipSaveResult
import com.rarible.protocol.nft.core.repository.ownership.OwnershipRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import scalether.domain.Address

@Service
class OwnershipService(
    private val ownershipRepository: OwnershipRepository
) {
    fun get(id: OwnershipId): Mono<Ownership> {
        return ownershipRepository.findById(id)
    }

    fun get(token: Address, tokenId: EthUInt256, owner: Address): Mono<Ownership> {
        return get(OwnershipId(token, tokenId, owner))
    }

    suspend fun getAll(ids: Collection<OwnershipId>): List<Ownership> {
        return ownershipRepository.findAll(ids)
    }

    suspend fun saveAll(ownerships: Collection<Ownership>): List<Ownership> {
        return ownershipRepository.saveAll(ownerships)
    }

    suspend fun removeAll(ids: Collection<OwnershipId>): List<Ownership> {
        return ownershipRepository.removeAll(ids)
    }

    fun save(marker: Marker, ownership: Ownership): Mono<OwnershipSaveResult> {
        return ownershipRepository.findById(ownership.id).toOptional()
            .flatMap { opt ->
                val found = opt.orNull()
                when {
                    found == null || found != ownership -> saveInternal(marker, ownership).map { OwnershipSaveResult(it, true) }
                    else -> Mono.just(OwnershipSaveResult(ownership, false))
                }
            }
    }

    fun delete(marker: Marker, ownership: Ownership): Mono<Ownership> {
        return ownershipRepository.deleteById(ownership.id)
    }

    private fun saveInternal(marker: Marker, ownership: Ownership): Mono<Ownership> {
        logger.info(marker, "Saving Ownership ${ownership.id}")
        return ownershipRepository.save(ownership)
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OwnershipService::class.java)
    }
}
