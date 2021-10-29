package com.rarible.protocol.nft.migration.mongock.mongo

import com.github.cloudyrock.mongock.ChangeLog
import com.github.cloudyrock.mongock.ChangeSet
import com.github.cloudyrock.mongock.driver.mongodb.springdata.v3.decorator.impl.MongockTemplate
import com.rarible.protocol.nft.core.model.LazyItemHistory
import com.rarible.protocol.nft.core.repository.history.LazyNftItemHistoryRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.index.Index

@ChangeLog(order = "00005")
class ChangeLog00005LazyHistoryIndexes {

    private val logger = LoggerFactory.getLogger(ChangeLog00005LazyHistoryIndexes::class.java)

    @ChangeSet(
        id = "ChangeLog00005LazyHistoryIndexes.extendTokenTokenIdIndexWithId",
        order = "1",
        author = "protocol"
    )
    fun extendTokenTokenIdIndexWithId(template: MongockTemplate) = runBlocking {
        val indexOps = template.indexOps(LazyNftItemHistoryRepository.COLLECTION)
        val existingIndexes = indexOps.indexInfo.map { it.name }
        logger.info("Existing indexes: $existingIndexes")
        indexOps.ensureIndex(
            Index()
                .on(LazyItemHistory::token.name, Sort.Direction.ASC)
                .on(LazyItemHistory::tokenId.name, Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)
        )
        indexOps.dropIndex("token_1_tokenId_1")
    }
}