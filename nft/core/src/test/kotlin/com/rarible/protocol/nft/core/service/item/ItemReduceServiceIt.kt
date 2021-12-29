package com.rarible.protocol.nft.core.service.item

import com.rarible.core.common.nowMillis
import com.rarible.core.test.data.randomAddress
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.ethereum.listener.log.domain.LogEvent
import com.rarible.ethereum.listener.log.domain.LogEventStatus
import com.rarible.protocol.dto.NftItemDeleteEventDto
import com.rarible.protocol.dto.NftItemMetaDto
import com.rarible.protocol.dto.NftItemUpdateEventDto
import com.rarible.protocol.dto.NftOwnershipDeleteEventDto
import com.rarible.protocol.dto.NftOwnershipUpdateEventDto
import com.rarible.protocol.nft.core.configuration.NftIndexerProperties
import com.rarible.protocol.nft.core.integration.AbstractIntegrationTest
import com.rarible.protocol.nft.core.integration.IntegrationTest
import com.rarible.protocol.nft.core.model.Item
import com.rarible.protocol.nft.core.model.ItemCreators
import com.rarible.protocol.nft.core.model.ItemId
import com.rarible.protocol.nft.core.model.ItemLazyMint
import com.rarible.protocol.nft.core.model.ItemProperties
import com.rarible.protocol.nft.core.model.ItemRoyalty
import com.rarible.protocol.nft.core.model.ItemTransfer
import com.rarible.protocol.nft.core.model.Ownership
import com.rarible.protocol.nft.core.model.OwnershipId
import com.rarible.protocol.nft.core.model.Part
import com.rarible.protocol.nft.core.model.Token
import com.rarible.protocol.nft.core.model.TokenStandard
import com.rarible.protocol.nft.core.repository.history.NftItemHistoryRepository.Companion.COLLECTION
import com.rarible.protocol.nft.core.repository.ownership.OwnershipRepository
import io.daonomic.rpc.domain.WordFactory
import io.mockk.coEvery
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import scalether.domain.Address
import scalether.domain.AddressFactory
import java.time.Instant
import java.util.stream.Stream

@FlowPreview
@IntegrationTest
internal class ItemReduceServiceIt : AbstractIntegrationTest() {

    @Autowired
    private lateinit var historyService: ItemReduceService

    @Autowired
    private lateinit var ownershipRepository: OwnershipRepository

    @Autowired
    private lateinit var featureFlags: NftIndexerProperties.FeatureFlags

    @BeforeEach
    fun setUpMeta() {
        coEvery { mockItemPropertiesResolver.resolve(any()) } returns itemProperties
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun mintItem(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)
        val owner = AddressFactory.create()
        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.ONE
        )
        saveItemHistory(transfer, from = owner)

        historyService.update(token, tokenId).awaitFirstOrNull()

        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()

        assertThat(item.creators).isEqualTo(listOf(Part.fullPart(owner)))
        assertThat(item.supply).isEqualTo(EthUInt256.ONE)

        checkItemEventWasPublished(token, tokenId, expectedItemMeta, NftItemUpdateEventDto::class.java)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    @Disabled
    fun compareHandleTime(ownershipBatchHandle: Boolean) = runBlocking {
        nftItemHistoryRepository.createIndexes()
        featureFlags.isRoyaltyServiceEnabled = false
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721),
        )

        val timing = mutableListOf<Long>()

        val ownerships = 6000
        repeat((1..ownerships).count()) {
            val transfer = createTransfer(token, tokenId)
            saveItemHistory(transfer)
        }

        for (i in 1..3) {
            val start = nowMillis()
            historyService.update(token, tokenId).awaitFirstOrNull()
            val end = nowMillis()
            timing.add(end.toEpochMilli() - start.toEpochMilli())
        }
        timing.forEach {
            logger.info("$it ($ownershipBatchHandle)")
        }
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun `should get creator from tokenId for opensea tokenId`(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = Address.apply("0x495f947276749ce646f68ac8c248420045cb7b5e")

        val owner = AddressFactory.create()
        // https://opensea.io/assets/0x495f947276749ce646f68ac8c248420045cb7b5e/43635738831738903259797022654371755363838740687517624872331458295230642520065
        // https://ethereum-api.rarible.org/v0.1/nft/items/0x495f947276749ce646f68ac8c248420045cb7b5e:43635738831738903259797022654371755363838740687517624872331458295230642520065
        val tokenId = EthUInt256.of("43635738831738903259797022654371755363838740687517624872331458295230642520065")
        val creator = Address.apply("0x6078f3f4a50eec358790bdfae15b351647e9cbb4")

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.ONE
        )
        saveItemHistory(transfer)

        historyService.update(token, tokenId).awaitFirstOrNull()

        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()

        assertThat(item.creators).isEqualTo(listOf(Part.fullPart(creator)))
        assertThat(item.supply).isEqualTo(EthUInt256.ONE)

        checkItemEventWasPublished(token, tokenId, expectedItemMeta, NftItemUpdateEventDto::class.java)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun mintItemViaPending(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val owner = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        saveItemHistory(
            ItemTransfer(
                owner = owner,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = EthUInt256.ONE
            ), status = LogEventStatus.PENDING
        )
        historyService.update(token, tokenId).awaitFirstOrNull()
        checkItem(token = token, tokenId = tokenId, expSupply = EthUInt256.ZERO)

        checkItemEventWasPublished(token, tokenId, expectedItemMeta, NftItemUpdateEventDto::class.java)
        checkOwnershipEventWasPublished(token, tokenId, owner, NftOwnershipUpdateEventDto::class.java)

        val pendingMint = nftItemHistoryRepository.findAllItemsHistory().collectList().awaitFirst().single()
        mongo.remove(Query(Criteria("_id").isEqualTo(pendingMint.log.id)), LogEvent::class.java, COLLECTION).awaitFirst()
        assertThat(nftItemHistoryRepository.findAllItemsHistory().collectList().awaitFirst()).isEmpty()

        saveItemHistory(
            ItemTransfer(
                owner = owner,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = EthUInt256.ONE
            ), status = LogEventStatus.CONFIRMED
        )

        historyService.update(token, tokenId).then().block()
        checkItem(token = token, tokenId = tokenId, expSupply = EthUInt256.ONE)

        checkItemEventWasPublished(token, tokenId, expectedItemMeta, NftItemUpdateEventDto::class.java)
        checkOwnershipEventWasPublished(token, tokenId, owner, NftOwnershipUpdateEventDto::class.java)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun deleteErrorEntities(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val owner = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.ONE
        )
        nftItemHistoryRepository.save(LogEvent(data = transfer, address = AddressFactory.create(), topic = WordFactory.create(), transactionHash = WordFactory.create(), status = LogEventStatus.DROPPED, index = 0, minorLogIndex = 0)).awaitFirst()
        val id = OwnershipId(token, tokenId, owner)
        ownershipRepository.save(
            Ownership(
                token = token,
                tokenId = tokenId,
                owner = owner,
                value = EthUInt256.ONE,
                lazyValue = EthUInt256.ZERO,
                date = nowMillis(),
                creators = listOf(Part(AddressFactory.create(), 1000)),
                pending = emptyList()
            )
        ).awaitFirst()

        historyService.update(token, tokenId).awaitFirstOrNull()
        assertThat(ownershipRepository.findById(id).awaitFirstOrNull()).isNull()
        checkItem(token = token, tokenId = tokenId, expSupply = EthUInt256.ZERO, deleted = true)

        checkItemEventWasPublished(token, tokenId, expectedItemMeta, NftItemDeleteEventDto::class.java)
        checkOwnershipEventWasPublished(token, tokenId, owner, NftOwnershipDeleteEventDto::class.java)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun transferToSelf(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner = AddressFactory.create()
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.TEN
        )
        val transfer2 = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = owner,
            value = EthUInt256.ONE
        )
        saveItemHistory(transfer, token)
        saveItemHistory(transfer2, token)

        historyService.update(token, tokenId).awaitFirstOrNull()
        val ownership = ownershipRepository.findById(OwnershipId(token, tokenId, owner)).awaitFirst()
        assertThat(ownership.value).isEqualTo(EthUInt256.TEN)
        checkItem(token = token, tokenId = tokenId, expSupply = EthUInt256.TEN)
    }

    @ParameterizedTest
    @MethodSource("invalidLogEventStatus")
    fun deleteItemAfterLogEventChangeStatusFromPendingToInvalidStatus(
        invalidStatus: LogEventStatus,
        ownershipBatchHandle: Boolean
    ) = runBlocking<Unit> {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val owner = AddressFactory.create()
        val tokenId = EthUInt256.ONE

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.ONE
        )
        val logEvent = LogEvent(
            data = transfer,
            address = AddressFactory.create(),
            topic = WordFactory.create(),
            transactionHash = WordFactory.create(),
            status = LogEventStatus.PENDING,
            index = 0,
            minorLogIndex = 0
        )
        val savedLogEvent = nftItemHistoryRepository.save(logEvent).awaitFirst()
        historyService.update(token, tokenId).awaitFirstOrNull()

        val newItem = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(newItem.deleted).isFalse()

        nftItemHistoryRepository.save(savedLogEvent.copy(status = invalidStatus)).awaitFirst()
        historyService.update(token, tokenId).awaitFirstOrNull()

        val updatedItem = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(updatedItem.deleted).isTrue()
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun burnItem(ownershipBatchHandle: Boolean) = runBlocking<Unit> {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner = AddressFactory.create()
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.of(3)
        )
        saveItemHistory(transfer)

        val transfer2 = ItemTransfer(
            owner = Address.ZERO(),
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = owner,
            value = EthUInt256.of(3)
        )
        saveItemHistory(transfer2)
        ownershipRepository.save(Ownership(
            token = token, tokenId = tokenId, owner = owner, value = EthUInt256.ONE, date = Instant.now(), pending = emptyList()
        )).awaitFirst()

        historyService.update(token, tokenId).then().block()
        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(item.supply).isEqualTo(EthUInt256.ZERO)
        assertThat(item.deleted).isEqualTo(true)

        checkItemEventWasPublished(token, tokenId, expectedItemMeta, NftItemDeleteEventDto::class.java)
        checkOwnershipEventWasPublished(token, tokenId, owner, NftOwnershipDeleteEventDto::class.java)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun pendingItemTransfer(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner = AddressFactory.create()
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = AddressFactory.create(),
            value = EthUInt256.ZERO
        )
        saveItemHistory(transfer, token = token, status = LogEventStatus.PENDING)

        historyService.update(token, tokenId).then().block()
        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(item.creators).isEmpty()
        assertThat(item.supply).isEqualTo(EthUInt256.ZERO)
        checkOwnership(owner = owner, token = token, tokenId = tokenId, expValue = EthUInt256.ZERO, expLazyValue = EthUInt256.ZERO)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun confirmedItemTransfer(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = AddressFactory.create(),
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.Companion.of(2)
        )
        saveItemHistory(transfer)

        val owner = AddressFactory.create()
        val transfer2 = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.of(3)
        )
        saveItemHistory(transfer2)

        historyService.update(token, tokenId).awaitFirstOrNull()
        checkItem(token = token, tokenId = tokenId, expSupply = EthUInt256.Companion.of(5))
    }

    @Disabled
    @Test
    fun confirmedItemRoyalty() = runBlocking<Unit> {
        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner = AddressFactory.create()

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        val transfer = ItemTransfer(owner, token, tokenId, nowMillis(), Address.ZERO(), EthUInt256.TEN)
        saveItemHistory(transfer, token)

        val royalty =
            ItemRoyalty(token = token, tokenId = tokenId, date = nowMillis(), royalties = listOf(Part(owner, 2)))
        saveItemHistory(royalty, token)

        historyService.update(token, tokenId).then().block()
        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(item.royalties).isEqualTo(listOf(Part(owner, 2)))
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun confirmedItemMint(ownershipBatchHandle: Boolean) = runBlocking<Unit> {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val minter = AddressFactory.create()
        val tokenId = EthUInt256.ONE

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        val transfer = ItemTransfer(minter, token, tokenId, nowMillis(), Address.ZERO(), EthUInt256.TEN)
        saveItemHistory(transfer, token, logIndex = 1)

        val creatorsList = listOf(Part(AddressFactory.create(), 1), Part(AddressFactory.create(), 2))
        val creators = ItemCreators(token, tokenId, nowMillis(), creatorsList)
        saveItemHistory(creators, token, logIndex = 2)

        historyService.update(token, tokenId).then().block()

        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(item.creators).isEqualTo(creatorsList)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun `update ownership for ERC1155`(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner = AddressFactory.create()
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.TEN
        )
        saveItemHistory(transfer, token)

        historyService.update(token, tokenId).awaitFirstOrNull()
        checkItem(token, tokenId, EthUInt256.TEN)
        checkOwnership(owner, token, tokenId, expValue = EthUInt256.TEN, expLazyValue = EthUInt256.ZERO)

        val buyer = AddressFactory.create()

        val transferAsBuying = ItemTransfer(buyer, token, tokenId, nowMillis(), owner, EthUInt256.Companion.of(2))
        saveItemHistory(transferAsBuying, token)

        historyService.update(token, tokenId).awaitFirstOrNull()

        checkItem(token, tokenId, EthUInt256.TEN)
        checkOwnership(buyer, token, tokenId, expValue = EthUInt256.of(2), expLazyValue = EthUInt256.ZERO)
        checkOwnership(owner, token, tokenId, expValue = EthUInt256.of(8), expLazyValue = EthUInt256.ZERO)

        checkOwnershipEventWasPublished(token, tokenId, buyer, NftOwnershipUpdateEventDto::class.java)
    }

    /**
     * Check that ownership of ERC721 is removed for the previous owner and a new ownership for the new owner is created
     */
    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun `ownership transferred for ERC721`(ownershipBatchHandle: Boolean) = runBlocking {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner = AddressFactory.create()
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC721)
        )

        val transfer = ItemTransfer(
            owner = owner,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.ONE
        )
        saveItemHistory(transfer, token)

        historyService.update(token, tokenId).awaitFirstOrNull()
        checkItem(token, tokenId, EthUInt256.ONE)
        checkOwnership(owner, token, tokenId, expValue = EthUInt256.ONE, expLazyValue = EthUInt256.ZERO)

        val buyer = AddressFactory.create()

        val transferAsBuying = ItemTransfer(buyer, token, tokenId, nowMillis(), owner, EthUInt256.ONE)
        saveItemHistory(transferAsBuying, token)

        historyService.update(token, tokenId).awaitFirstOrNull()

        checkItem(token, tokenId, EthUInt256.ONE)
        checkOwnership(buyer, token, tokenId, expValue = EthUInt256.ONE, expLazyValue = EthUInt256.ZERO)
        checkEmptyOwnership(owner, token, tokenId)

        checkOwnershipEventWasPublished(token, tokenId, buyer, NftOwnershipUpdateEventDto::class.java)
        checkOwnershipEventWasPublished(token, tokenId, owner, NftOwnershipDeleteEventDto::class.java)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun `should set pending log only for target ownerships`(ownershipBatchHandle: Boolean) = runBlocking<Unit> {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = AddressFactory.create()
        val tokenId = EthUInt256.ONE
        val owner1 = AddressFactory.create()
        val owner2 = AddressFactory.create()
        val owner3 = AddressFactory.create()
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        val transfer1 = ItemTransfer(
            owner = owner1,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.TEN
        )
        val transfer2 = ItemTransfer(
            owner = owner2,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.TEN
        )
        val transfer3 = ItemTransfer(
            owner = owner3,
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = owner2,
            value = EthUInt256.of(2)
        )

        saveItemHistory(transfer1, token)
        saveItemHistory(transfer2, token)
        saveItemHistory(transfer3, token, status = LogEventStatus.PENDING)

        historyService.update(token, tokenId).awaitFirstOrNull()

        val ownership1 = ownershipRepository.findById(OwnershipId(token, tokenId, owner1)).awaitFirst()
        assertThat(ownership1.value).isEqualTo(EthUInt256.TEN)
        assertThat(ownership1.pending).isEmpty()

        val ownership2 = ownershipRepository.findById(OwnershipId(token, tokenId, owner2)).awaitFirst()
        assertThat(ownership1.value).isEqualTo(EthUInt256.TEN)
        assertThat(ownership2.pending).isNotEmpty

        val ownership3 = ownershipRepository.findById(OwnershipId(token, tokenId, owner3)).awaitFirst()
        assertThat(ownership3.value).isEqualTo(EthUInt256.ZERO)
        assertThat(ownership3.pending).isNotEmpty
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun ownershipsInfoOfItem(ownershipBatchHandle: Boolean) = runBlocking<Unit> {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = Address.ONE()
        val tokenId = EthUInt256.of(1)
        val creator = AddressFactory.create()
        val owner1 = AddressFactory.create()
        val owner2 = AddressFactory.create()
        val owner3 = AddressFactory.create()
        val owner4 = AddressFactory.create()
        val value = EthUInt256.of(20)
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )
        saveItemHistory(
            ItemTransfer(
                owner = creator,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = value
            ), token
        )
        saveItemHistory(
            ItemTransfer(
                owner = owner1,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = creator,
                value = EthUInt256.Companion.of(5)
            ), token
        )
        saveItemHistory(
            ItemTransfer(
                owner = owner2,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = creator,
                value = EthUInt256.Companion.of(5)
            ), token
        )
        saveItemHistory(
            ItemTransfer(
                owner = owner3,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = creator,
                value = EthUInt256.Companion.of(5)
            ), token
        )
        saveItemHistory(
            ItemTransfer(
                owner = owner4,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = creator,
                value = EthUInt256.Companion.of(5)
            ), token
        )

        historyService.update(token, tokenId).awaitFirstOrNull()

        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(item.owners).containsExactlyInAnyOrder(owner1, owner2, owner3, owner4)
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun `should lazy mint`(ownershipBatchHandle: Boolean) = runBlocking<Unit> {
        setOwnershipBatchHandle(ownershipBatchHandle)

        val token = Address.ONE()
        val tokenId = EthUInt256.ONE
        val creator = AddressFactory.create()

        val value = EthUInt256.of(20)
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        lazyNftItemHistoryRepository.save(
            ItemLazyMint(
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                standard = TokenStandard.ERC1155,
                value = value,
                uri = "test",
                creators = creators(creator),
                royalties = emptyList(),
                signatures = emptyList()
            )
        ).awaitFirst()

        historyService.update(token, tokenId).awaitFirstOrNull()

        checkOwnership(creator, token, tokenId, expValue = EthUInt256.of(20), expLazyValue = EthUInt256.of(20))
    }

    @ParameterizedTest
    @MethodSource("ownershipBatchHandle")
    fun `should calculate lazy after real mint`(ownershipBatchHandle: Boolean) = runBlocking<Unit> {
        val token = Address.ONE()
        val tokenId = EthUInt256.ONE
        val creator = AddressFactory.create()
        val owner1 = AddressFactory.create()

        val value = EthUInt256.of(10)
        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        lazyNftItemHistoryRepository.save(
            ItemLazyMint(
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                value = value,
                standard = TokenStandard.ERC1155,
                uri = "test",
                creators = creators(creator),
                royalties = emptyList(),
                signatures = emptyList()
            )
        ).awaitFirst()

        saveItemHistory(
            ItemTransfer(
                owner = owner1,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = EthUInt256.Companion.of(2)
            )
        )

        historyService.update(token, tokenId).awaitFirstOrNull()

        checkOwnership(
            creator,
            token,
            tokenId,
            expValue = EthUInt256.Companion.of(8),
            expLazyValue = EthUInt256.Companion.of(8)
        )
        checkOwnership(
            owner1,
            token,
            tokenId,
            expValue = EthUInt256.Companion.of(2),
            expLazyValue = EthUInt256.Companion.of(0)
        )
        checkItem(token, tokenId, expSupply = value, expLazySupply = EthUInt256.Companion.of(8), expCreator = creator)

        //transfer to owner1, to creator
        val owner2 = AddressFactory.create()
        saveItemHistory(
            ItemTransfer(
                owner = owner2,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = EthUInt256.Companion.of(5)
            )
        )
        saveItemHistory(
            ItemTransfer(
                owner = creator,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = EthUInt256.Companion.of(1)
            )
        )

        historyService.update(token, tokenId).awaitFirstOrNull()

        checkOwnership(
            creator,
            token,
            tokenId,
            expValue = EthUInt256.Companion.of(3),
            expLazyValue = EthUInt256.Companion.of(2)
        )
        checkOwnership(
            owner1,
            token,
            tokenId,
            expValue = EthUInt256.Companion.of(2),
            expLazyValue = EthUInt256.Companion.of(0)
        )
        checkOwnership(
            owner2,
            token,
            tokenId,
            expValue = EthUInt256.Companion.of(5),
            expLazyValue = EthUInt256.Companion.of(0)
        )
        checkItem(token, tokenId, expSupply = value, expLazySupply = EthUInt256.Companion.of(2))

        saveItemHistory(
            ItemTransfer(
                owner = owner1,
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                from = Address.ZERO(),
                value = EthUInt256.Companion.of(2)
            )
        )

        historyService.update(token, tokenId).awaitFirstOrNull()

        checkOwnership(creator, token, tokenId, expValue = EthUInt256.Companion.of(1), expLazyValue = EthUInt256.Companion.of(0))
        checkOwnership(owner1, token, tokenId, expValue = EthUInt256.Companion.of(4), expLazyValue = EthUInt256.Companion.of(0))
        checkOwnership(owner2, token, tokenId, expValue = EthUInt256.Companion.of(5), expLazyValue = EthUInt256.Companion.of(0))
        checkItem(token, tokenId, expSupply = value, expLazySupply = EthUInt256.Companion.of(0))
    }

    @Disabled
    @Test
    fun `should calculate royalties after real mint of lazy nft`() = runBlocking<Unit> {
        val token = Address.ONE()
        val tokenId = EthUInt256.ONE
        val royalties = listOf(Part(AddressFactory.create(), 1), Part(AddressFactory.create(), 2))
        val creator = AddressFactory.create()
        val value = EthUInt256.of(10)

        saveToken(
            Token(token, name = "TEST", standard = TokenStandard.ERC1155)
        )

        lazyNftItemHistoryRepository.save(
            ItemLazyMint(
                token = token,
                tokenId = tokenId,
                date = nowMillis(),
                value = value,
                standard = TokenStandard.ERC1155,
                uri = "test",
                creators = creators(creator),
                royalties = royalties,
                signatures = emptyList()
            )
        ).awaitFirst()

        historyService.update(token, tokenId).awaitFirstOrNull()

        val lazyItem = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(lazyItem.royalties).isEqualTo(royalties)

        val realRoyalties = listOf(Part(AddressFactory.create(), 10), Part(AddressFactory.create(), 20))
        saveItemHistory(
            ItemRoyalty(token = token, tokenId = tokenId, date = nowMillis(), royalties = realRoyalties),
            logIndex = 1
        )

        historyService.update(token, tokenId).awaitFirstOrNull()

        val realItem = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()
        assertThat(realItem.royalties).isEqualTo(realRoyalties)
    }

    private val itemProperties = ItemProperties(
        name = "Test Item",
        description = "Test Description",
        image = null,
        imagePreview = null,
        imageBig = null,
        animationUrl = null,
        attributes = emptyList(),
        rawJsonContent = null
    )

    private val expectedItemMeta = NftItemMetaDto(
        name = itemProperties.name,
        description = itemProperties.description,
        attributes = emptyList(),
        image = null,
        animation = null
    )

    private suspend fun saveToken(token: Token) {
        tokenRepository.save(token).awaitFirst()
    }

    private fun setOwnershipBatchHandle(ownershipBatchHandle: Boolean) {
        featureFlags.ownershipBatchHandle = ownershipBatchHandle
    }

    private suspend fun checkItem(
        token: Address,
        tokenId: EthUInt256,
        expSupply: EthUInt256,
        expLazySupply: EthUInt256 = EthUInt256.ZERO,
        expCreator: Address? = null,
        deleted: Boolean = false
    ) {
        val item = itemRepository.findById(ItemId(token, tokenId)).awaitFirst()

        assertThat(item)
            .hasFieldOrPropertyWithValue(Item::supply.name, expSupply)
            .hasFieldOrPropertyWithValue(Item::lazySupply.name, expLazySupply)
            .hasFieldOrPropertyWithValue(Item::deleted.name, deleted)

        expCreator?.run {
            assertThat(item.creators)
                .isEqualTo(listOf(Part(expCreator, 10000)))
        }
    }

    private suspend fun checkEmptyOwnership(
        owner: Address,
        token: Address,
        tokenId: EthUInt256
    ) {
        val ownershipId = OwnershipId(token, tokenId, owner)
        val found = ownershipRepository.findById(ownershipId).awaitFirstOrNull()
        assertThat(found).withFailMessage("Deleted ownership must not be found: $ownershipId").isNull()
    }

    private suspend fun checkOwnership(
        owner: Address,
        token: Address,
        tokenId: EthUInt256,
        expValue: EthUInt256,
        expLazyValue: EthUInt256
    ) = runBlocking {
        val ownership = ownershipRepository.findById(OwnershipId(token, tokenId, owner)).awaitFirst()
        assertThat(ownership.value).isEqualTo(expValue)
        assertThat(ownership.lazyValue).isEqualTo(expLazyValue)
    }

    private fun createTransfer(token: Address, tokenId: EthUInt256): ItemTransfer {
        return ItemTransfer(
            owner = randomAddress(),
            token = token,
            tokenId = tokenId,
            date = nowMillis(),
            from = Address.ZERO(),
            value = EthUInt256.ONE
        )
    }

    companion object {
        val logger = LoggerFactory.getLogger(ItemReduceServiceIt::class.java)

        @JvmStatic
        fun invalidLogEventStatus(): Stream<Arguments> = Stream.of(
            Arguments.of(LogEventStatus.DROPPED, true),
            Arguments.of(LogEventStatus.INACTIVE, true),
            Arguments.of(LogEventStatus.DROPPED, false),
            Arguments.of(LogEventStatus.INACTIVE, false)
        )

        @JvmStatic
        fun ownershipBatchHandle(): Stream<Boolean> = Stream.of(false, true)

        private fun creators(vararg creator: Address): List<Part> = creators(creator.toList())

        private fun creators(creators: List<Address>): List<Part> {
            val every = 10000 / creators.size
            return creators.map { Part(it, every) }
        }
    }
}
