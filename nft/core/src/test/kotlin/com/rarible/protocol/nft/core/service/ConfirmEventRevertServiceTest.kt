package com.rarible.protocol.nft.core.service

import com.rarible.protocol.nft.core.data.createRandomMintItemEvent
import com.rarible.protocol.nft.core.model.BlockchainEntityEvent
import com.rarible.protocol.nft.core.model.ItemEvent
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ConfirmEventRevertServiceTest {
    private val entityEventRevertService = ConfirmEventRevertService<ItemEvent>(
        mockk { every { confirmationBlocks } returns 5 }
    )

    @Test
    fun `should mark event as not reverted`() {
        val current = createRandomMintItemEvent().copy(status = BlockchainEntityEvent.Status.CONFIRMED, blockNumber = 1)
        val last = createRandomMintItemEvent().copy(status = BlockchainEntityEvent.Status.CONFIRMED, blockNumber = 10)

        assertThat(entityEventRevertService.canBeReverted(last = last, current = current)).isTrue()
    }

    @Test
    fun `current pending block is not revertable`() {
        val current = createRandomMintItemEvent().copy(status = BlockchainEntityEvent.Status.PENDING)
        val last = createRandomMintItemEvent().copy(status = BlockchainEntityEvent.Status.CONFIRMED, blockNumber = 10)

        assertThat(entityEventRevertService.canBeReverted(last = last, current = current)).isFalse()
    }
}
