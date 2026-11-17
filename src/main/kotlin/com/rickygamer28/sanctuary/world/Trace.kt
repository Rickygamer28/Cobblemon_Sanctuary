package com.rickygamer28.sanctuary.world

/**
 * For the self-test only: remembers which part of the builder last changed each column, so a problem the test
 * finds can be traced to what made it. Off (and free) in normal play.
 */
object Trace {
    @JvmField var on = false
    private val events = HashMap<Long, ArrayDeque<String>>()
    var tick = 0L

    fun note(x: Int, z: Int, what: String) {
        if (!on) return
        val q = events.getOrPut((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)) { ArrayDeque() }
        q.addLast("t$tick $what")
        while (q.size > 4) q.removeFirst()
    }

    fun of(x: Int, z: Int): String = events[(x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)]?.joinToString(" | ") ?: "-"

    fun clear() = events.clear()
}
