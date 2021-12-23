package com.rarible.protocol.nft.core.service.token.reduce.status

import com.rarible.blockchain.scanner.ethereum.model.EthereumLogStatus
import com.rarible.core.entity.reducer.service.Reducer
import com.rarible.protocol.nft.core.model.Token
import com.rarible.protocol.nft.core.model.TokenEvent
import com.rarible.protocol.nft.core.service.token.reduce.forward.ForwardChainTokenReducer
import com.rarible.protocol.nft.core.service.token.reduce.inactive.InactiveChainTokenReducer
import com.rarible.protocol.nft.core.service.token.reduce.pending.PendingChainTokenReducer
import com.rarible.protocol.nft.core.service.token.reduce.reverted.ReversedChainTokenReducer
import org.springframework.stereotype.Component

@Component
class EventStatusTokenReducer(
    private val forwardChainTokenReducer: ForwardChainTokenReducer,
    private val reversedChainTokenReducer: ReversedChainTokenReducer,
    private val pendingChainTokenReducer: PendingChainTokenReducer,
    private val inactiveChainTokenReducer: InactiveChainTokenReducer
) : Reducer<TokenEvent, Token> {

    override suspend fun reduce(entity: Token, event: TokenEvent): Token {
        return when (event.log.status) {
            EthereumLogStatus.CONFIRMED -> forwardChainTokenReducer.reduce(entity, event)
            EthereumLogStatus.PENDING -> pendingChainTokenReducer.reduce(entity, event)
            EthereumLogStatus.REVERTED -> reversedChainTokenReducer.reduce(entity, event)
            EthereumLogStatus.INACTIVE,
            EthereumLogStatus.DROPPED -> inactiveChainTokenReducer.reduce(entity, event)
        }
    }
}
