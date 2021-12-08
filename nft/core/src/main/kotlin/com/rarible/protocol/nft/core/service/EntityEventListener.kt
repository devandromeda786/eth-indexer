package com.rarible.protocol.nft.core.service

import com.rarible.blockchain.scanner.ethereum.model.ReversedEthereumLogRecord
import com.rarible.protocol.nft.core.model.SubscriberGroup

interface EntityEventListener {
    val groupId: SubscriberGroup

    suspend fun onEntityEvents(events: List<ReversedEthereumLogRecord>)
}
