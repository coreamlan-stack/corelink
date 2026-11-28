package dev.coreamlan.corelink

import java.util.ArrayDeque

/** Coalesces only adjacent motion; other commands remain in their original order. */
internal class OrderedMoveQueue<T>(
    private val capacity: Int = 128,
    private val move: (T) -> Pair<Int, Int>?,
    private val merged: (T, Int, Int) -> T,
) {
    private val queue = ArrayDeque<T>()
    @Synchronized fun offer(value: T): Boolean {
        val previous = queue.peekLast()
        val a = previous?.let(move)
        val b = move(value)
        if (previous != null && a != null && b != null) {
            val dx = a.first.toLong() + b.first.toLong()
            val dy = a.second.toLong() + b.second.toLong()
            if (dx in -5000..5000 && dy in -5000..5000) {
                queue.removeLast(); queue.addLast(merged(previous, dx.toInt(), dy.toInt())); return true
            }
        }
        if (queue.size >= capacity) return false
        queue.addLast(value); return true
    }
    @Synchronized fun poll(): T? = queue.pollFirst()
    @Synchronized fun clear() = queue.clear()
}
