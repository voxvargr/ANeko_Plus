package org.nqmgaming.aneko.core.scroll

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.nqmgaming.aneko.core.media.MediaRect

class ScrollImpulseStoreTest {
    private fun impulse() = ScrollImpulse(MediaRect(0f, 0f, 500f, 500f), 0f, 40f, 1000, "org.example.reader", 1)

    @Test fun clearIsAnOrderedCancellationAfterPublishedMovement() = runBlocking {
        val received = mutableListOf<ScrollImpulse?>()
        val collect = launch(start = CoroutineStart.UNDISPATCHED) { ScrollImpulseStore.events.take(2).toList(received) }
        assertTrue(ScrollImpulseStore.publish(impulse()))
        ScrollImpulseStore.clear()
        withTimeout(1_000) { collect.join() }
        assertEquals(impulse(), received[0])
        assertNull(received[1])
    }

    @Test fun aNewObserverDoesNotReplayAnOldGesture() = runBlocking {
        ScrollImpulseStore.publish(impulse())
        val received = mutableListOf<ScrollImpulse?>()
        val collect = launch(start = CoroutineStart.UNDISPATCHED) { ScrollImpulseStore.events.take(1).toList(received) }
        ScrollImpulseStore.clear()
        withTimeout(1_000) { collect.join() }
        assertEquals(1, received.size)
        assertNull(received.single())
    }

    @Test fun invalidOrZeroEventsAreRejectedAtTheSharedBoundary() {
        assertFalse(ScrollImpulseStore.publish(impulse().copy(deltaY = 0f)))
        assertFalse(ScrollImpulseStore.publish(impulse().copy(deltaY = Float.POSITIVE_INFINITY)))
        assertFalse(ScrollImpulseStore.publish(impulse().copy(windowId = -1)))
        assertFalse(ScrollImpulseStore.publish(impulse().copy(packageName = "")))
        assertFalse(ScrollImpulseStore.publish(impulse().copy(bounds = MediaRect(2f, 0f, 1f, 10f))))
    }
}
