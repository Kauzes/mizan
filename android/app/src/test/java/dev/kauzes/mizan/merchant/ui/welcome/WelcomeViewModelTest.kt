package dev.kauzes.mizan.merchant.ui.welcome

import dev.kauzes.mizan.merchant.data.PlatformClient
import dev.kauzes.mizan.merchant.domain.PlatformHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** A platform that answers whatever it is told to, when it is told to. */
    private class FakePlatform(var answer: PlatformHealth = PlatformHealth.Up) : PlatformClient {
        override val gatewayUrl = "http://10.0.2.2:8080"
        var asked = 0
        var holdUntil: CompletableDeferred<Unit>? = null

        override suspend fun health(): PlatformHealth {
            asked++
            holdUntil?.await()
            return answer
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `it names the platform it will talk to before anything is asked`() {
        val platform = FakePlatform()
        val viewModel = WelcomeViewModel(platform)

        assertEquals("http://10.0.2.2:8080", viewModel.state.value.gatewayUrl)
        assertNull(viewModel.state.value.health)
        assertEquals(0, platform.asked)
    }

    @Test
    fun `checking reports what the platform said`() = runTest(dispatcher) {
        val viewModel = WelcomeViewModel(FakePlatform(PlatformHealth.Up))

        viewModel.check()
        advanceUntilIdle()

        assertEquals(PlatformHealth.Up, viewModel.state.value.health)
        assertFalse(viewModel.state.value.checking)
    }

    @Test
    fun `a platform that cannot be reached is reported as unreachable, not as down`() = runTest(dispatcher) {
        val viewModel = WelcomeViewModel(FakePlatform(PlatformHealth.Unreachable("ConnectException")))

        viewModel.check()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.health is PlatformHealth.Unreachable)
    }

    @Test
    fun `a second tap while the first is still asking asks nothing more`() = runTest(dispatcher) {
        val platform = FakePlatform().apply { holdUntil = CompletableDeferred() }
        val viewModel = WelcomeViewModel(platform)

        viewModel.check()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.checking)

        viewModel.check()
        viewModel.check()
        advanceUntilIdle()
        assertEquals(1, platform.asked)

        platform.holdUntil!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.checking)
    }
}
