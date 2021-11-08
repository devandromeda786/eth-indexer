package com.rarible.protocol.order.listener.service.descriptors.auction.v1

import com.rarible.contracts.test.erc20.TestERC20
import com.rarible.core.test.data.randomAddress
import com.rarible.core.test.wait.Wait
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.ethereum.listener.log.domain.LogEvent
import com.rarible.protocol.contracts.auction.v1.AuctionHouse
import com.rarible.protocol.contracts.common.TransferProxy
import com.rarible.protocol.contracts.common.erc721.TestERC721
import com.rarible.protocol.contracts.erc20.proxy.ERC20TransferProxy
import com.rarible.protocol.contracts.royalties.TestRoyaltiesProvider
import com.rarible.protocol.order.core.model.*
import com.rarible.protocol.order.core.repository.auction.AuctionHistoryRepository
import com.rarible.protocol.order.core.repository.auction.AuctionRepository
import com.rarible.protocol.order.listener.data.randomAuction
import com.rarible.protocol.order.listener.integration.AbstractIntegrationTest
import com.rarible.protocol.order.listener.misc.setField
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.reactive.awaitFirst
import org.apache.commons.lang3.RandomUtils
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.web3j.utils.Numeric
import reactor.core.publisher.Mono
import scalether.transaction.MonoGasPriceProvider
import scalether.transaction.MonoSigningTransactionSender
import scalether.transaction.MonoSimpleNonceProvider
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

@FlowPreview
abstract class AbstractAuctionDescriptorTest : AbstractIntegrationTest() {
    protected lateinit var userSender1: MonoSigningTransactionSender
    protected lateinit var userSender2: MonoSigningTransactionSender
    protected lateinit var token1: TestERC20
    protected lateinit var token2: TestERC20
    protected lateinit var token721: TestERC721
    protected lateinit var transferProxy: TransferProxy
    protected lateinit var erc20TransferProxy: ERC20TransferProxy
    protected lateinit var auctionHouse: AuctionHouse
    protected lateinit var privateKey1: BigInteger
    protected lateinit var privateKey2: BigInteger

    @Autowired
    protected lateinit var auctionHistoryRepository: AuctionHistoryRepository

    @Autowired
    protected lateinit var auctionRepository: AuctionRepository

    @Autowired
    private lateinit var auctionCreatedDescriptor: AuctionCreatedDescriptor

    @Autowired
    private lateinit var auctionBidDescriptor: AuctionBidDescriptor

    @Autowired
    private lateinit var auctionFinishedDescriptor: AuctionFinishedDescriptor

    @Autowired
    private lateinit var auctionCancelDescriptor: AuctionCancelDescriptor

    @BeforeEach
    fun before() {
        privateKey1 = Numeric.toBigInt(RandomUtils.nextBytes(32))
        privateKey2 = Numeric.toBigInt(RandomUtils.nextBytes(32))

        userSender1 = MonoSigningTransactionSender(
            ethereum,
            MonoSimpleNonceProvider(ethereum),
            privateKey1,
            BigInteger.valueOf(8000000),
            MonoGasPriceProvider { Mono.just(BigInteger.ZERO) }
        )
        userSender2 = MonoSigningTransactionSender(
            ethereum,
            MonoSimpleNonceProvider(ethereum),
            privateKey2,
            BigInteger.valueOf(8000000),
            MonoGasPriceProvider { Mono.just(BigInteger.ZERO) }
        )
        token1 = TestERC20.deployAndWait(sender, poller, "Test1", "TST1").block()!!
        token2 = TestERC20.deployAndWait(sender, poller, "Test2", "TST2").block()!!
        token721 = TestERC721.deployAndWait(sender, poller).block()!!
        transferProxy = TransferProxy.deployAndWait(sender, poller).block()!!
        erc20TransferProxy = ERC20TransferProxy.deployAndWait(sender, poller).block()!!
        val royaltiesProvider = TestRoyaltiesProvider.deployAndWait(sender, poller).block()!!
        auctionHouse = AuctionHouse.deployAndWait(sender, poller).block()!!
        auctionHouse.__AuctionHouse_init(
            transferProxy.address(),
            erc20TransferProxy.address(),
            BigInteger.ZERO,
            sender.from(),
            royaltiesProvider.address()
        ).execute().verifySuccess()

        transferProxy.addOperator(auctionHouse.address()).execute().verifySuccess()
        erc20TransferProxy.addOperator(auctionHouse.address()).execute().verifySuccess()
        token1.approve(erc20TransferProxy.address(), BigInteger.TEN.pow(10)).withSender(userSender1).execute()
            .verifySuccess()
        token2.approve(erc20TransferProxy.address(), BigInteger.TEN.pow(10)).withSender(userSender2).execute()
            .verifySuccess()
        token721.setApprovalForAll(transferProxy.address(), true).withSender(userSender1).execute().verifySuccess()
        token721.setApprovalForAll(transferProxy.address(), true).withSender(userSender2).execute().verifySuccess()

        listOf(
            auctionCreatedDescriptor,
            auctionBidDescriptor,
            auctionFinishedDescriptor,
            auctionCancelDescriptor
        ).forEach(::setAuctionContract)
    }

    private fun setAuctionContract(descriptor: AbstractAuctionDescriptor<*>) {
        setField(descriptor, "auctionContract", auctionHouse.address())
    }

    private fun mintErc721(sender: MonoSigningTransactionSender): Erc721AssetType {
        val tokenId = BigInteger.valueOf((0L..10000).random())
        token721.mint(sender.from(), tokenId).execute().verifySuccess()
        return Erc721AssetType(token721.address(), EthUInt256.of(tokenId))
    }

    protected suspend fun withStartedAuction(
        seller: MonoSigningTransactionSender,
        startTime: EthUInt256 = EthUInt256.of(Instant.now().epochSecond + 60),
        checkAction: suspend (StartedAuction) -> Unit
    ) {
        val erc721AssetType = mintErc721(seller)

        val auctionParameters = AuctionStartParameters(
            sell = Asset(erc721AssetType, EthUInt256.ONE),
            buy = EthAssetType,
            minimalStep = EthUInt256.of(1),
            minimalPrice = EthUInt256.of(1),
            data = RaribleAuctionV1DataV1(
                originFees = listOf(
                    Part(randomAddress(), EthUInt256.of(5000)),
                    Part(randomAddress(), EthUInt256.of(5000))
                ),
                payouts = listOf(
                    Part(randomAddress(), EthUInt256.of(5000)),
                    Part(randomAddress(), EthUInt256.of(5000))
                ),
                duration = Duration.ofHours(1).let { EthUInt256.of(it.seconds) },
                startTime = startTime,
                buyOutPrice = EthUInt256.TEN
            )
        )
        auctionParameters.forTx().let { forTx ->
            auctionHouse.startAuction(
                forTx._1(),
                forTx._2(),
                forTx._3(),
                forTx._4(),
                forTx._5(),
                forTx._6()
            ).withSender(userSender1).execute().verifySuccess()

        }

        var events: List<LogEvent> = emptyList()
        Wait.waitAssert {
            events = auctionHistoryRepository.findByType(AuctionHistoryType.ON_CHAIN_AUCTION).collectList().awaitFirst()
            Assertions.assertThat(events).hasSize(1)
        }
        val chainAuction = events.map { event -> event.data as OnChainAuction }.single()
        checkAction(StartedAuction(auctionParameters, chainAuction))
    }

    protected data class StartedAuction(
        val startedParams: AuctionStartParameters,
        val chainAuction: OnChainAuction
    )

    protected class AuctionStartParameters(
        val sell: Asset,
        val buy: AssetType,
        val minimalStep: EthUInt256,
        val minimalPrice: EthUInt256,
        val data: AuctionData
    ) {
        fun forTx() = run {
            randomAuction().copy(
                sell = sell,
                buy = buy,
                minimalStep = minimalStep,
                minimalPrice = minimalPrice,
                data = data
            ).forTx()
        }
    }
}
