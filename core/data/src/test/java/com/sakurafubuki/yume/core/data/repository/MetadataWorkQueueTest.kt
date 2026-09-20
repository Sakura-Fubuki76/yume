package com.sakurafubuki.yume.core.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MetadataWorkQueueTest {
    @Test
    fun slowFileDoesNotBlockAnotherDirectoryAndCancelledWorkCanRetry() = runTest {
        val queue = MetadataWorkQueue(2, backgroundScope)
        val blocked = CompletableDeferred<Unit>()
        val slow = async {
            queue.process(listOf("slow"), { it }, MetadataRequestPriority.BACKGROUND) {
                blocked.await()
                true
            }
        }
        runCurrent()
        val refreshed = async { queue.process(listOf("new"), { it }, MetadataRequestPriority.FOREGROUND) { true } }
        runCurrent()
        assertTrue(refreshed.isCompleted)
        assertTrue(refreshed.await())
        slow.cancelAndJoin()
        assertTrue(queue.process(listOf("slow"), { it }, MetadataRequestPriority.FOREGROUND) { true })
    }

    @Test
    fun overlappingRequestsSerializeSameFileAndProcessEveryItem() = runTest {
        val queue = MetadataWorkQueue(3, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var entered = 0
        val first = async {
            queue.process(listOf("same"), { it }, MetadataRequestPriority.BACKGROUND) {
                entered++
                gate.await()
                true
            }
        }
        runCurrent()
        val second = async {
            queue.process(listOf("same"), { it }, MetadataRequestPriority.FOREGROUND) {
                entered++
                true
            }
        }
        runCurrent()
        assertEquals(1, entered)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, entered)
        val completed = mutableSetOf<Int>()
        queue.process((0 until 2500).toList(), { it.toString() }, MetadataRequestPriority.FOREGROUND) { completed.add(it) }
        assertEquals(2500, completed.size)
    }

    @Test
    fun foregroundOvertakesQueuedBackgroundWork() = runTest {
        val queue = MetadataWorkQueue(1, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val oldBatch = async {
            queue.process(listOf("running", "old-1", "old-2"), { it }, MetadataRequestPriority.BACKGROUND) {
                if (it == "running") gate.await()
                order += it
                true
            }
        }
        runCurrent()
        val currentPage = async {
            queue.process(listOf("visible"), { it }, MetadataRequestPriority.FOREGROUND) {
                order += it
                true
            }
        }
        gate.complete(Unit)
        oldBatch.await()
        currentPage.await()
        assertEquals(listOf("running", "visible", "old-1", "old-2"), order)
    }
}
