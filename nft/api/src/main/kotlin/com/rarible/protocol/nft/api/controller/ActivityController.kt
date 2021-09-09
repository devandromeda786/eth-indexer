package com.rarible.protocol.nft.api.controller

import com.rarible.protocol.dto.NftActivitiesDto
import com.rarible.protocol.dto.NftActivityFilterDto
import com.rarible.protocol.dto.mapper.ContinuationMapper
import com.rarible.protocol.nft.api.converter.ActivityHistoryFilterConverter
import com.rarible.protocol.nft.core.converters.dto.NftActivityConverter
import com.rarible.protocol.nft.api.service.activity.NftActivityService
import com.rarible.protocol.nft.core.repository.history.ActivitySort
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class ActivityController(
    private val nftActivityService: NftActivityService,
    private val historyFilterConverter: ActivityHistoryFilterConverter
) : NftActivityControllerApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun getNftActivities(
        request: NftActivityFilterDto,
        continuation: String?,
        size: Int?,
        sort: String?
    ): ResponseEntity<NftActivitiesDto> {
        val requestSize = size.limit()
        val continuationDto = ContinuationMapper.toActivityContinuationDto(continuation)
        val activitySort = ActivitySort.fromString(sort)
        val historyFilters = historyFilterConverter.convert(activitySort, request, continuationDto)
        logger.info("Filters: ${historyFilters.joinToString { it.javaClass.simpleName }}")

        val result = nftActivityService
            .search(historyFilters, activitySort, requestSize)
            .mapNotNull { NftActivityConverter.convert(it.value) }

        val nextContinuation = if (result.isEmpty() || result.size < requestSize) {
            null
        } else {
            ContinuationMapper.toString(result.last())
        }
        return ResponseEntity.ok(NftActivitiesDto(nextContinuation, result))
    }

    companion object {
        private const val DEFAULT_SIZE = 1_000
        private fun Int?.limit() = Integer.min(this ?: DEFAULT_SIZE, DEFAULT_SIZE)
    }
}
