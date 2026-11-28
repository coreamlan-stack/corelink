package dev.coreamlan.corelink

import org.junit.Assert.*
import org.junit.Test

class OrderedMoveQueueTest {
    private data class Command(val kind: String, val dx: Int = 0, val dy: Int = 0)
    private fun queue(capacity: Int = 128) = OrderedMoveQueue<Command>(capacity,
        move = { if (it.kind == "move") it.dx to it.dy else null },
        merged = { item, dx, dy -> item.copy(dx = dx, dy = dy) })

    @Test fun movesCoalesceWithoutCrossingClickOrKey() {
        val q=queue()
        listOf(Command("move",2,3),Command("move",4,5),Command("click"),Command("move",6,7),Command("key"),Command("move",1,2)).forEach { assertTrue(q.offer(it)) }
        assertEquals(Command("move",6,8),q.poll()); assertEquals(Command("click"),q.poll())
        assertEquals(Command("move",6,7),q.poll()); assertEquals(Command("key"),q.poll())
        assertEquals(Command("move",1,2),q.poll()); assertNull(q.poll())
    }
    @Test fun queueIsBoundedAndNeverProducesAnOutOfRangeMove() {
        val q=queue(2)
        assertTrue(q.offer(Command("move",5000,0))); assertTrue(q.offer(Command("move",1,0)))
        assertFalse(q.offer(Command("click")))
        assertEquals(Command("move",5000,0),q.poll()); assertEquals(Command("move",1,0),q.poll())
        q.offer(Command("click")); q.clear(); assertNull(q.poll())
    }
}
