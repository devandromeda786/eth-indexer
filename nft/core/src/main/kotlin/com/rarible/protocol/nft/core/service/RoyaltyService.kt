package com.rarible.protocol.nft.core.service

import com.rarible.ethereum.domain.EthUInt256
import com.rarible.protocol.contracts.external.royalties.IRoyaltiesProvider
import com.rarible.protocol.nft.core.configuration.NftIndexerProperties
import com.rarible.protocol.nft.core.model.Part
import com.rarible.protocol.nft.core.model.Royalty
import com.rarible.protocol.nft.core.repository.RoyaltyRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.stereotype.Service
import scalether.domain.Address
import scalether.transaction.MonoTransactionSender

@Service
class RoyaltyService(
    private val sender: MonoTransactionSender,
    private val nftIndexerProperties: NftIndexerProperties,
    private val royaltyRepository: RoyaltyRepository
) {

    suspend fun getRoyalty(address: Address, tokenId: EthUInt256): List<Part> {
        var record = royaltyRepository.findByTokenAndId(address, tokenId).awaitFirstOrNull()
        return when {
            record != null -> record.royalty
            else -> {
                val provider = IRoyaltiesProvider(Address.apply(nftIndexerProperties.royaltyRegistryAddress), sender)
                val answer = provider.getRoyalties(address, tokenId.value).call().awaitSingle()
                val royalty = answer.map { Part(it._1, it._2.intValueExact()) }.toList()
                royaltyRepository.save(
                    Royalty(
                        address = address,
                        tokenId = tokenId,
                        royalty = royalty
                    )
                ).awaitSingle().royalty
            }
        }
    }
}
