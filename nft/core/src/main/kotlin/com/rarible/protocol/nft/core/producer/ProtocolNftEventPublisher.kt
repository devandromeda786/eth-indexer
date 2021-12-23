package com.rarible.protocol.nft.core.producer

import com.rarible.core.apm.CaptureSpan
import com.rarible.core.apm.SpanType
import com.rarible.core.kafka.KafkaMessage
import com.rarible.core.kafka.RaribleKafkaProducer
import com.rarible.protocol.dto.ActivityDto
import com.rarible.protocol.dto.ActivityTopicProvider
import com.rarible.protocol.dto.NftActivityDto
import com.rarible.protocol.dto.NftCollectionEventDto
import com.rarible.protocol.dto.NftCollectionEventTopicProvider
import com.rarible.protocol.dto.NftItemDeleteEventDto
import com.rarible.protocol.dto.NftItemEventDto
import com.rarible.protocol.dto.NftItemEventTopicProvider
import com.rarible.protocol.dto.NftItemUpdateEventDto
import com.rarible.protocol.dto.NftOwnershipEventDto
import com.rarible.protocol.dto.NftOwnershipEventTopicProvider
import com.rarible.protocol.nft.core.model.OwnershipId
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
@CaptureSpan(type = SpanType.KAFKA)
class ProtocolNftEventPublisher(
    private val collectionEventProducer: RaribleKafkaProducer<NftCollectionEventDto>,
    private val itemEventsProducer: RaribleKafkaProducer<NftItemEventDto>,
    private val internalItemEventsProducer: RaribleKafkaProducer<NftItemEventDto>,
    private val ownershipEventProducer: RaribleKafkaProducer<NftOwnershipEventDto>,
    private val nftItemActivityProducer: RaribleKafkaProducer<ActivityDto>
) {
    private val collectionEventHeaders = mapOf("protocol.collection.event.version" to NftCollectionEventTopicProvider.VERSION)
    private val itemEventHeaders = mapOf("protocol.item.event.version" to NftItemEventTopicProvider.VERSION)
    private val ownershipEventHeaders =
        mapOf("protocol.ownership.event.version" to NftOwnershipEventTopicProvider.VERSION)
    private val itemActivityHeaders = mapOf("protocol.item.activity.version" to ActivityTopicProvider.VERSION)

    suspend fun publish(event: NftCollectionEventDto) {
        val message = KafkaMessage(
            key = event.id.hex(),
            value = event,
            headers = collectionEventHeaders,
            id = event.eventId
        )
        collectionEventProducer.send(message).ensureSuccess()
    }

    suspend fun publish(event: NftItemEventDto) {
        val message = KafkaMessage(
            key = event.itemId,
            value = event,
            headers = itemEventHeaders,
            id = event.eventId
        )
        itemEventsProducer.send(message).ensureSuccess()
        val shortEvent = when (event) {
            is NftItemUpdateEventDto -> event.copy(item = event.item.copy(owners = emptyList()))
            is NftItemDeleteEventDto -> event
        }
        logger.info("Sent item event ${event.itemId}: $shortEvent")
    }

    suspend fun publishInternalItem(event: NftItemEventDto) {
        val message = KafkaMessage(
            key = event.itemId,
            value = event,
            headers = itemEventHeaders,
            id = event.eventId
        )
        internalItemEventsProducer.send(message).ensureSuccess()
    }

    suspend fun publish(event: NftOwnershipEventDto) {
        val message = prepareOwnershipKafkaMessage(event)
        ownershipEventProducer.send(message).ensureSuccess()
    }

    suspend fun publish(events: List<NftOwnershipEventDto>) {
        val messages = events.map { event -> prepareOwnershipKafkaMessage(event) }
        ownershipEventProducer.send(messages).collect { result ->
            result.ensureSuccess()
        }
    }

    suspend fun publish(event: NftActivityDto) {
        val itemId = "${event.contract}:${event.tokenId}"
        val message = KafkaMessage(
            key = itemId,
            value = event as ActivityDto,
            headers = itemActivityHeaders,
            id = itemId
        )
        nftItemActivityProducer.send(message).ensureSuccess()
    }

    private fun prepareOwnershipKafkaMessage(event: NftOwnershipEventDto): KafkaMessage<NftOwnershipEventDto> {
        val ownershipId = OwnershipId.parseId(event.ownershipId)
        val itemId = "${ownershipId.token}:${ownershipId.tokenId.value}"

        return KafkaMessage(
            key = itemId,
            value = event,
            headers = ownershipEventHeaders,
            id = event.eventId
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ProtocolNftEventPublisher::class.java)
    }
}
