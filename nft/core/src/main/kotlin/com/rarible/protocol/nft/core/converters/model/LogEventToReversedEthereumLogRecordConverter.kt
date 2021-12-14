package com.rarible.protocol.nft.core.converters.model

import com.rarible.blockchain.scanner.ethereum.model.EventData
import com.rarible.blockchain.scanner.ethereum.model.ReversedEthereumLogRecord
import com.rarible.ethereum.listener.log.domain.LogEvent

object LogEventToReversedEthereumLogRecordConverter {
    fun convert(source: LogEvent): ReversedEthereumLogRecord {
        return ReversedEthereumLogRecord(
            id = source.id.toHexString(),
            version = source.version,
            transactionHash = source.transactionHash.prefixed(),
            status = BlockchainStatusConverter.convert(source.status),
            topic = source.topic,
            minorLogIndex = source.minorLogIndex,
            index = source.index,
            address = source.address,
            blockHash = source.blockHash,
            blockNumber = source.blockNumber,
            logIndex = source.logIndex,
            visible = source.visible,
            createdAt = source.createdAt,
            updatedAt = source.updatedAt,
            data = source.data as EventData
        )
    }
}
