package com.rarible.protocol.nft.core.service.token

import com.rarible.core.common.retryOptimisticLock
import com.rarible.ethereum.listener.log.domain.LogEvent
import com.rarible.ethereum.listener.log.domain.LogEventStatus
import com.rarible.ethereum.listener.log.domain.LogEventStatus.CONFIRMED
import com.rarible.protocol.nft.core.model.*
import com.rarible.protocol.nft.core.repository.TokenRepository
import com.rarible.protocol.nft.core.repository.history.NftHistoryRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import scalether.domain.Address
import scalether.util.Hash

@Service
class TokenReduceService(
    private val tokenRepository: TokenRepository,
    private val tokenHistoryRepository: NftHistoryRepository
) {

    suspend fun updateToken(address: Address): Token? = update(address).awaitFirstOrNull()

    fun update(address: Address?): Flux<Token> =
        tokenHistoryRepository.findAllByCollection(address)
            .windowUntilChanged { (it.data as CollectionEvent).id }
            .concatMap { updateOneToken(it) }

    private fun updateOneToken(logs: Flux<LogEvent>): Mono<Token> {
        return logs.reduce(Token.empty()) { token, log -> reduce(token, log) }
            .flatMap {
                logger.info("reduce result: $it")
                if (it.id != Address.ZERO()) {
                    insertOrUpdate(it)
                } else {
                    Mono.empty()
                }
            }
    }

    private fun reduce(token: Token, log: LogEvent): Token {
        val status = when (log.status) {
            LogEventStatus.PENDING -> ContractStatus.PENDING
            CONFIRMED -> ContractStatus.CONFIRMED
            else -> ContractStatus.ERROR
        }
        return when (val data = log.data as CollectionEvent) {
            is CreateCollection -> {
                val (standard, features) = TokenStandard.CREATE_TOPIC_MAP[log.topic] ?: return token
                token.copy(
                    id = data.id,
                    owner = data.owner,
                    name = data.name,
                    symbol = data.symbol,
                    features = features,
                    standard = standard,
                    status = maxOf(token.status, status),
                    lastEventId = accumulateEventId(token.lastEventId, log.id.toHexString())
                )
            }
            is CollectionOwnershipTransferred -> token.copy(
                owner = data.newOwner,
                status = maxOf(token.status, status),
                lastEventId = accumulateEventId(token.lastEventId, log.id.toHexString())
            )
        }
    }

    private fun accumulateEventId(lastEventId: String?, eventId: String): String =
        Hash.sha3((lastEventId ?: "") + eventId)

    private fun insertOrUpdate(token: Token): Mono<Token> {
        return tokenRepository.findById(token.id)
            .flatMap {
                logger.info("found token with id ${token.id}. updating")
                tokenRepository.save(token.copy(version = it.version))
            }
            .switchIfEmpty {
                logger.info("not found token with id ${token.id}. inserting")
                tokenRepository.save(token)
            }
            .retryOptimisticLock(3)
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(TokenReduceService::class.java)
    }
}
