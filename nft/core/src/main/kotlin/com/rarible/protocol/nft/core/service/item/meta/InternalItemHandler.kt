package com.rarible.protocol.nft.core.service.item.meta

import com.rarible.core.apm.CaptureSpan
import com.rarible.core.apm.SpanType
import com.rarible.core.application.ApplicationEnvironmentInfo
import com.rarible.core.common.convert
import com.rarible.core.daemon.sequential.ConsumerEventHandler
import com.rarible.core.kafka.RaribleKafkaConsumer
import com.rarible.core.kafka.json.JsonDeserializer
import com.rarible.ethereum.domain.Blockchain
import com.rarible.protocol.dto.NftItemDeleteEventDto
import com.rarible.protocol.dto.NftItemEventDto
import com.rarible.protocol.dto.NftItemMetaDto
import com.rarible.protocol.dto.NftItemUpdateEventDto
import com.rarible.protocol.nft.core.configuration.NftIndexerProperties
import com.rarible.protocol.nft.core.model.ItemId
import com.rarible.protocol.nft.core.model.ItemMeta
import com.rarible.protocol.nft.core.producer.ProtocolNftEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Component
import java.util.*

/**
 * Kafka consumer responsible for attaching metadata to NFT Item events DTOs (consumed from an internal topic)
 * and forwarding the extended events to the public topic.
 *
 * This component is used in the 'nft-indexer-listener' module, which is running a background job (ConsumerWorker)
 * that is reading from the internal queue and publishes to the public queue.
 */
@Component
@CaptureSpan(SpanType.APP)
class InternalItemHandler(
    private val itemMetaService: ItemMetaService,
    private val conversionService: ConversionService,
    private val protocolNftEventPublisher: ProtocolNftEventPublisher,
    private val nftIndexerProperties: NftIndexerProperties
) : ConsumerEventHandler<NftItemEventDto> {

    override suspend fun handle(event: NftItemEventDto) = try {
        when (event) {
            is NftItemUpdateEventDto -> {
                val meta = getItemMeta(event)
                val metaDto = conversionService.convert<NftItemMetaDto>(meta)
                val extendedItem = event.item.copy(meta = metaDto)
                protocolNftEventPublisher.publish(event.copy(item = extendedItem))
            }
            is NftItemDeleteEventDto -> protocolNftEventPublisher.publish(event)
        }
    } catch (e: Throwable) {
        logger.error("Failed to handle $event", e)
        if (e is Error) {
            throw RuntimeException(e)
        }
        throw e
    }

    private suspend fun getItemMeta(event: NftItemUpdateEventDto): ItemMeta {
        val itemMeta = itemMetaService.getItemMetadata(
            ItemId.parseId(event.item.id),
            nftIndexerProperties.returnOnlyCacheItemMeta
        )
        if (nftIndexerProperties.returnOnlyCacheItemMeta && itemMeta == ItemMeta.EMPTY) {
            logger.info("Meta for item ${event.itemId} is not available, sending empty meta")
        }
        return itemMeta
    }

    companion object {
        private val logger = LoggerFactory.getLogger(InternalItemHandler::class.java)

        fun getInternalTopic(environment: String, blockchain: String): String =
            "protocol.$environment.$blockchain.indexer.nft.item.internal"

        fun createInternalItemConsumer(
            applicationEnvironmentInfo: ApplicationEnvironmentInfo,
            blockchain: Blockchain,
            kafkaReplicaSet: String
        ): RaribleKafkaConsumer<NftItemEventDto> {
            val environment = applicationEnvironmentInfo.name
            val host = applicationEnvironmentInfo.host
            val consumerGroup = "$environment.protocol.${blockchain.value}.nft.indexer.item.internal"
            val clientIdPrefix = "$environment.${blockchain.value}.$host.${UUID.randomUUID()}"
            return RaribleKafkaConsumer(
                clientId = "$clientIdPrefix.nft.indexer.item.internal",
                valueDeserializerClass = JsonDeserializer::class.java,
                valueClass = NftItemEventDto::class.java,
                consumerGroup = consumerGroup,
                defaultTopic = getInternalTopic(environment, blockchain.value),
                bootstrapServers = kafkaReplicaSet
            )
        }
    }
}
