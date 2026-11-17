package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import net.minecraft.server.MinecraftServer

/**
 * Keeps the server from falling behind because of too many Pokémon out. A server gets 50 ms per tick; when it
 * needs longer, every Pokémon moves in jumps (inside the sanctuary they're the only mobs, so that's what you see).
 * While the server is over its time, fewer residents are allowed out (the extra ones go home where nobody sees);
 * when it has room again, they come back out bit by bit.
 */
object LoadGuard {
    /** Share of the usual number of Pokémon out that's allowed right now (1 = all of them). */
    @Volatile
    var share = 1.0
        private set
    /** The server's time per tick (smoothed), in ms. */
    @Volatile
    var tickMs = 0.0
        private set
    private var lastAt = 0L

    /** Set while a sanctuary is being built. */
    @Volatile
    var building = false

    /** Called once a second. */
    fun tick(server: MinecraftServer, now: Long) {
        if (now - lastAt < 20) return
        lastAt = now
        tickMs = runCatching { server.currentSmoothedTickTime.toDouble() }.getOrDefault(0.0)
        if (!SanctuaryConfig.autoLimitCrowd.get()) { share = 1.0; return }
        // while the valley is being built fast, the busy server is the builder's doing (and stops when it's done):
        // don't send Pokémon home for it
        if (building && SanctuaryConfig.buildFast.get()) return
        share = when {
            tickMs > 48 -> (share - 0.08).coerceAtLeast(0.3)  // falling behind: send some home
            tickMs > 42 -> (share - 0.03).coerceAtLeast(0.3)  // close to the limit: ease off a little
            tickMs < 34 -> (share + 0.03).coerceAtMost(1.0)   // room to spare: let more out again
            else -> share
        }
    }

    /** The number of residents allowed out, given the usual [cap]. */
    fun limit(cap: Int): Int = if (share >= 1.0) cap else maxOf(12, (cap * share).toInt())
}
