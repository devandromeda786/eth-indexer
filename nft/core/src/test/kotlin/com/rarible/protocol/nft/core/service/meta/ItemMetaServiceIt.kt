package com.rarible.protocol.nft.core.service.meta

import com.rarible.core.test.wait.Wait
import com.rarible.protocol.nft.core.data.createRandomItemId
import com.rarible.protocol.nft.core.data.randomItemMeta
import com.rarible.protocol.nft.core.integration.AbstractIntegrationTest
import com.rarible.protocol.nft.core.integration.IntegrationTest
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

@FlowPreview
@IntegrationTest
class ItemMetaServiceIt : AbstractIntegrationTest() {

    @Test
    fun `get available or schedule loading`() = runBlocking<Unit> {
        val itemMeta = randomItemMeta()
        val itemId = createRandomItemId()
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } returns itemMeta
        assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isNull()
        Wait.waitAssert {
            assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isEqualTo(itemMeta)
            coVerify(exactly = 1) { mockItemMetaResolver.resolveItemMeta(itemId) }
        }
    }

    @Test
    fun `error on loading - then update`() = runBlocking<Unit> {
        val itemId = createRandomItemId()
        val error = RuntimeException("loading error")
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } throws error
        itemMetaService.scheduleMetaUpdate(itemId)
        Wait.waitAssert {
            coVerify(exactly = 1) { mockItemMetaResolver.resolveItemMeta(itemId) }
        }

        // Must not be re-scheduled, because the meta loading has already failed.
        assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isNull()
        delay(1000)
        coVerify(exactly = 1) { mockItemMetaResolver.resolveItemMeta(itemId) }

        // Schedule a successful update.
        val itemMeta = randomItemMeta()
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } returns itemMeta
        itemMetaService.scheduleMetaUpdate(itemId)
        Wait.waitAssert {
            coVerify(exactly = 2) { mockItemMetaResolver.resolveItemMeta(itemId) }
            assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isEqualTo(itemMeta)
        }
    }

    @Test
    fun `load and wait`() = runBlocking<Unit> {
        val itemMeta = randomItemMeta()
        val itemId = createRandomItemId()
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } coAnswers {
            delay(300)
            itemMeta
        }
        assertThat(
            itemMetaService.getAvailableMetaOrScheduleLoadingAndWaitWithTimeout(
                itemId = itemId,
                timeout = Duration.ofSeconds(5)
            )
        ).isEqualTo(itemMeta)
        coVerify(exactly = 1) { mockItemMetaResolver.resolveItemMeta(itemId) }
    }

    @Test
    fun `load and wait - return null if timeout`() = runBlocking<Unit> {
        val itemMeta = randomItemMeta()
        val itemId = createRandomItemId()
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } coAnswers {
            delay(1000L)
            itemMeta
        }
        assertThat(
            itemMetaService.getAvailableMetaOrScheduleLoadingAndWaitWithTimeout(
                itemId = itemId,
                timeout = Duration.ofMillis(500)
            )
        ).isNull()
    }

    @Test
    fun `load synchronously - return earlier than timeout if initial loading failed`() = runBlocking<Unit> {
        val itemId = createRandomItemId()
        val error = RuntimeException("loading-error")
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } throws error

        // Throws TimeoutCancellationException if waiting for too long.
        withTimeout(Duration.ofMillis(5000)) {
            assertThat(
                itemMetaService.getAvailableMetaOrScheduleLoadingAndWaitWithTimeout(
                    itemId = itemId,
                    timeout = Duration.ofMillis(10000)
                )
            ).isNull()
        }

        assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isNull()
    }

    @Test
    fun `remove meta`() = runBlocking<Unit> {
        val itemMeta = randomItemMeta()
        val itemId = createRandomItemId()
        coEvery { mockItemMetaResolver.resolveItemMeta(itemId) } returns itemMeta
        itemMetaService.scheduleMetaUpdate(itemId)
        Wait.waitAssert {
            assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isEqualTo(itemMeta)
            coVerify(exactly = 1) { mockItemMetaResolver.resolveItemMeta(itemId) }
        }
        itemMetaService.removeMeta(itemId)
        assertThat(itemMetaService.getAvailableMetaOrScheduleLoading(itemId)).isNull()
    }
}
