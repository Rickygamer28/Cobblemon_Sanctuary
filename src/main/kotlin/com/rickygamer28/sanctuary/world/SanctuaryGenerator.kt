package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.BlockTags
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import kotlin.math.sqrt

/** Every column offset within the largest sanctuary and its mountain rim, sorted from the centre outwards. */
object ColumnOrder {
    val R = SanctuarySize.MAX_RADIUS + Sanctuaries.RIM
    val dx: IntArray
    val dz: IntArray
    private val distSq: IntArray
    private val reverse: IntArray
    private val side = R * 2 + 1

    /** Index of the column at offset (dx, dz) from the centre, or -1 if outside. */
    fun indexOf(dx: Int, dz: Int): Int {
        if (dx < -R || dx > R || dz < -R || dz > R) return -1
        return reverse[(dx + R) * side + (dz + R)]
    }

    init {
        val list = ArrayList<IntArray>()
        for (x in -R..R) for (z in -R..R) {
            val d = x * x + z * z
            if (d <= R * R) list.add(intArrayOf(x, z, d))
        }
        list.sortWith(compareBy<IntArray> { it[2] }.thenBy { it[0] }.thenBy { it[1] })
        dx = IntArray(list.size) { list[it][0] }
        dz = IntArray(list.size) { list[it][1] }
        distSq = IntArray(list.size) { list[it][2] }
        reverse = IntArray(side * side) { -1 }
        for (i in dx.indices) reverse[(dx[i] + R) * side + (dz[i] + R)] = i
    }

    /** Number of columns within [radius]. */
    fun countWithin(radius: Int): Int {
        val target = radius * radius
        var lo = 0
        var hi = distSq.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (distSq[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Builds the valley from the centre outwards, a time-limited number of columns per tick and only in loaded chunks:
 * the biomes, then the wall, then the mountain rim. When a new type arrives or the sanctuary grows it's swept again;
 * finished columns are skipped.
 */
object SanctuaryGenerator {
    private val cursors = HashMap<UUID, Int>()
    /** Where the nature wave (trees, plants…) and the extras wave (resident spots) have got to. */
    private val natureCursors = HashMap<UUID, Int>()
    private val extrasCursors = HashMap<UUID, Int>()
    /** Columns set aside because their chunks weren't loaded yet (column index, and since when). */
    private val deferred = HashMap<UUID, ArrayDeque<Pair<Int, Long>>>()
    private val retryAt = HashMap<UUID, Long>()
    private val missedUnloaded = HashMap<UUID, Boolean>()
    private val recheckMeadow = HashSet<UUID>()

    fun forget(id: UUID) { cuts.remove(id); natureCursors.remove(id); extrasCursors.remove(id); BiomeDresser.forgetPending(id); deferred.remove(id); com.rickygamer28.sanctuary.ui.RegionMap.forget(id); Caves.forget(id); grounded.remove(id); recheckMeadow.remove(id); cursors.remove(id); retryAt.remove(id); missedUnloaded.remove(id); requested.remove(id); waiting.remove(id); BiomePainter.forget(id) }

    // ------------------------------------------------------------------ loading the chunks it builds in
    // Building only where players happen to have chunks loaded (their render distance) split the valley into
    // separate passes, and things that cross from one part into another (trees, boulders, dens) came out cut or
    // half-built. Now the builder loads the chunks it's about to build in, a little ahead of itself, and keeps
    // them loaded for a short while, so the whole valley is built in one go whatever the render distance.

    private val BUILD_TICKET: net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> =
        net.minecraft.server.level.TicketType.create("cobblemon_sanctuary_build", Comparator.comparingLong<net.minecraft.world.level.ChunkPos> { it.toLong() }, 20 * 45)
    private val requested = HashMap<UUID, HashMap<Long, Long>>()
    /** The column the builder is waiting on to load, and since when. */
    private val waiting = HashMap<UUID, Pair<Int, Long>>()

    private fun loadChunks() = runCatching { SanctuaryConfig.loadChunksToBuild.get() }.getOrDefault(true)

    /** Keeps the chunk (and the ones around it) loaded for a while. */
    private fun request(level: ServerLevel, s: Sanctuary, cx: Int, cz: Int, now: Long) {
        val m = requested.getOrPut(s.id) { HashMap() }
        val k = net.minecraft.world.level.ChunkPos.asLong(cx, cz)
        val t = m[k]
        if (t != null && now - t < 20L * 30) return
        m[k] = now
        val pos = net.minecraft.world.level.ChunkPos(cx, cz)
        level.chunkSource.addRegionTicket(BUILD_TICKET, pos, 1, pos)
    }

    /** Time the builder used per tick lately (smoothed). */
    private var usedMillis = 0.0

    /**
     * How long the builder may work this tick: at least [SanctuaryConfig.generationMillis], and more while the rest of
     * the server leaves time to spare (keeping the whole tick under ~40 ms of its 50), up to generationMillisMax.
     */
    private fun buildMillis(level: ServerLevel): Long {
        val max = SanctuaryConfig.generationMillisMax.get().toDouble().coerceAtLeast(2.0)
        val start = SanctuaryConfig.generationMillis.get().toDouble().coerceIn(2.0, max)
        if (budgetMs <= 0) budgetMs = start
        // Steer by the whole tick, not just the builder's own part: every block placed also costs lighting, sending
        // the change to players and loading chunks, which happens elsewhere in the tick. Keep the tick well under
        // its 50 ms so Pokémon keep moving smoothly; speed up while there's room, back off fast when it gets close.
        val tick = runCatching { level.server.currentSmoothedTickTime.toDouble() }.getOrDefault(0.0)
        if (SanctuaryConfig.buildFast.get()) {
            // fast: use the full time; only ease off if the server gets badly behind (over ~70 ms a tick)
            budgetMs = when {
                tick > 70 -> budgetMs * 0.85
                else -> budgetMs * 1.1 + 0.5
            }.coerceIn(2.0, max)
            return budgetMs.toLong().coerceAtLeast(2)
        }
        budgetMs = when {
            tick > 45 -> budgetMs * 0.8
            tick > 38 -> budgetMs * 0.95
            tick in 0.1..30.0 -> budgetMs * 1.04 + 0.1
            else -> budgetMs
        }.coerceIn(2.0, max)
        return budgetMs.toLong().coerceAtLeast(2)
    }

    /** The builder's time per tick right now (adjusted as the server gets busier or quieter). */
    private var budgetMs = 0.0

    /** Asks for the chunks of the next columns to be loaded. */
    private fun prefetch(level: ServerLevel, s: Sanctuary, from: Int, total: Int, now: Long) {
        if (!loadChunks() || now % 2L != 0L) return
        var last = Long.MIN_VALUE
        for (idx in from until minOf(total, from + 12000)) {
            val x = s.center.x + ColumnOrder.dx[idx]
            val z = s.center.z + ColumnOrder.dz[idx]
            val k = net.minecraft.world.level.ChunkPos.asLong(x shr 4, z shr 4)
            if (k == last) continue
            last = k
            request(level, s, x shr 4, z shr 4, now)
        }
    }

    /**
     * The column's chunk (and its neighbours) are loaded and ready to build in. If not, they're asked for and
     * this returns false; after waiting too long it gives up on the column for this pass.
     */
    private fun ready(level: ServerLevel, s: Sanctuary, idx: Int, x: Int, z: Int, now: Long): Boolean? {
        val cx = x shr 4
        val cz = z shr 4
        var all = true
        for (dx in -1..1) for (dz in -1..1) if (!level.hasChunk(cx + dx, cz + dz)) { all = false; break }
        if (all) { waiting.remove(s.id); return true }
        if (!loadChunks()) return if (level.hasChunk(cx, cz)) true else null
        request(level, s, cx, cz, now)
        val w = waiting[s.id]
        if (w == null || w.first != idx) { waiting[s.id] = idx to now; return false }
        // waited ten seconds: build it anyway if its own chunk is there, otherwise skip it for now
        if (now - w.second > 200) { waiting.remove(s.id); return if (level.hasChunk(cx, cz)) true else null }
        return false
    }

    /**
     * Are the column's chunk and its neighbours loaded? If not, all the missing ones are asked for. Null when they
     * can't be loaded (chunk loading for building is off and its own chunk isn't there).
     */
    private fun chunksReady(level: ServerLevel, s: Sanctuary, x: Int, z: Int, now: Long): Boolean? {
        val cx = x shr 4
        val cz = z shr 4
        var all = true
        for (dx in -1..1) for (dz in -1..1) if (!level.hasChunk(cx + dx, cz + dz)) {
            all = false
            if (loadChunks()) request(level, s, cx + dx, cz + dz, now)
        }
        if (all) return true
        if (!loadChunks()) return if (level.hasChunk(cx, cz)) true else null
        return false
    }

    fun isGrowing(s: Sanctuary) = s.generationDirty || cursors.containsKey(s.id)

    /** A chunk of this sanctuary just loaded: if parts were skipped earlier, try again soon. */
    fun nudge(s: Sanctuary, now: Long) {
        if (missedUnloaded[s.id] != true || cursors.containsKey(s.id)) return
        val soon = now + 40
        val cur = retryAt[s.id]
        if (cur == null || cur > soon) retryAt[s.id] = soon
    }

    private fun total(s: Sanctuary) = ColumnOrder.countWithin(s.size.radius + Sanctuaries.RIM)

    /** Progress of the current building sweep, 0..100, or null when not building. */
    fun progress(s: Sanctuary): Int? {
        val c = cursors[s.id] ?: return if (s.generationDirty) 0 else null
        val all = c.toLong() + (natureCursors[s.id] ?: 0) + (extrasCursors[s.id] ?: 0)
        return (all * 100L / (3L * total(s).coerceAtLeast(1))).toInt().coerceAtMost(99)
    }

    /** How fast the builder is getting through the valley: [cursor, time (ms), columns per second]. */
    private val pace = HashMap<UUID, DoubleArray>()

    private fun trackPace(s: Sanctuary) {
        val c = (cursors[s.id] ?: return).toDouble()
        val t = System.currentTimeMillis().toDouble()
        val p = pace.getOrPut(s.id) { doubleArrayOf(c, t, 0.0) }
        val dt = (t - p[1]) / 1000.0
        if (dt < 2.0) return
        val rate = ((c - p[0]) / dt).coerceAtLeast(0.0)
        p[2] = if (p[2] == 0.0) rate else p[2] * 0.7 + rate * 0.3
        p[0] = c; p[1] = t
    }

    /** Roughly how many minutes are left in this building pass (null when not building or not known yet). */
    fun minutesLeft(s: Sanctuary): Int? {
        val c = cursors[s.id] ?: return null
        val rate = pace[s.id]?.get(2)?.takeIf { it > 1 } ?: return null
        return Math.ceil((total(s) - c) / rate / 60.0).toInt().coerceAtLeast(1)
    }

    private val repairCursor = HashMap<UUID, Int>()
    private val repaired = HashMap<UUID, Int>()
    private val repairMissed = HashSet<UUID>()
    private val repairAgainAt = HashMap<UUID, Long>()

    /** Check the valley once after loading (and after each rebuild). */
    /**
     * Rebuilds the land in a circle of [radius] around (x, z): its trees come down whole (so anything left cut in
     * half goes), and every column is dressed again on the next pass. Returns how many columns will be redone.
     */
    fun repairArea(level: ServerLevel, s: Sanctuary, x: Int, z: Int, radius: Int): Int {
        BiomeDresser.removeTreesIn(level, BlockPos(x, 0, z), radius + 4, com.rickygamer28.sanctuary.SanctuaryDimension.GROUND_Y - 6, com.rickygamer28.sanctuary.SanctuaryDimension.GROUND_Y + 140)
        var n = 0
        for (dx in -radius..radius) for (dz in -radius..radius) {
            if (dx * dx + dz * dz > radius * radius) continue
            val idx = ColumnOrder.indexOf(x + dx - s.center.x, z + dz - s.center.z)
            if (idx < 0 || !s.dressed.get(idx)) continue
            s.dressed.clear(idx)
            s.meadow.clear(idx)
            n++
        }
        run { Trace.rebuild("repair here"); s.generationDirty = true }
        return n
    }

    fun isRepairing(s: Sanctuary) = repairCursor.containsKey(s.id)

    /**
     * How often each column was cut down by the cleanup since the server started. A column that keeps coming back
     * (the builder makes it that way on purpose, or something rebuilds it) is left alone after two tries, so the
     * builder and the cleanup can never undo each other's work forever.
     */
    private val cuts = HashMap<UUID, HashMap<Int, Int>>()
    private fun mayCut(s: Sanctuary, idx: Int): Boolean {
        val m = cuts.getOrPut(s.id) { HashMap() }
        val n = (m[idx] ?: 0) + 1
        m[idx] = n
        return n <= 2
    }

    /**
     * A mountain woke up or fell asleep: the caves change shape (its part opens or closes, and the tunnels joining
     * it to its neighbours), so the ground over all of them is built again (old tunnels filled, new ones dug).
     */
    fun redoCaves(s: Sanctuary) {
        val net = Caves.network(s) ?: return
        var n = 0
        val total = ColumnOrder.countWithin(s.size.radius)
        for (x in net.minX..net.maxX) for (z in net.minZ..net.maxZ) {
            val idx = ColumnOrder.indexOf(x - s.center.x, z - s.center.z)
            if (idx < 0 || idx >= total || !s.dressed.get(idx)) continue
            if (!Caves.near(s, x, z)) continue
            s.dressed.clear(idx); s.meadow.clear(idx); n++
        }
        if (n > 0) { Trace.rebuild("caves changed"); s.generationDirty = true }
    }

    fun scheduleCheck(id: UUID) { repairCursor[id] = 0; repaired[id] = 0 }

    /**
     * Safety net: walks over the finished valley and cuts down any column that stands far above where the terrain
     * should be (a leftover pillar of an old volcano, peak or mountain, whatever left it behind), then lets the
     * builder dress it again. Returns true while it's still busy.
     */
    private fun repair(level: ServerLevel, s: Sanctuary): Boolean {
        var i = repairCursor[s.id] ?: return false
        val ring = ZoneLayout.ring()
        val total = ColumnOrder.countWithin(s.size.radius - 2)
        val started = System.nanoTime()
        val deadline = started + buildMillis(level) * 1_000_000L
        var scanned = 0
        var found = repaired[s.id] ?: 0
        val now = level.gameTime
        prefetch(level, s, i, total, now)
        BiomeDresser.beginHeights(s)
        try {
        while (i < total) {
            if ((++scanned and 31) == 0 && System.nanoTime() > deadline) break
            val idx = i++
            if (!s.dressed.get(idx)) continue
            val x = s.center.x + ColumnOrder.dx[idx]
            val z = s.center.z + ColumnOrder.dz[idx]
            when (ready(level, s, idx, x, z, now)) {
                false -> { i = idx; break }
                null -> { repairMissed += s.id; continue }
                true -> {}
            }
            if (Landmarks.near(s, x, z)) continue
            val inDen = Dens.covers(s, x, z)
            looseBlocks(level, x, z)
            floatingBits(level, s, x, z)
            orphanLeaves(level, x, z)
            strayWater(level, s, x, z)
            if (s.meadow.get(idx)) continue
            Dens.maybeBuild(level, s, x, z, ring)
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            val target = BiomeDresser.targetHeight(s, x, z, ring)
            // lava out in the open (left from an older layout's cave): only the caves' own pools may have it
            run {
                val pool = Caves.at(s, x, z)?.floor?.minus(1)
                for (y in target..top + 1) {
                    val p = BlockPos(x, y, z)
                    if (y != pool && level.getFluidState(p).`is`(net.minecraft.tags.FluidTags.LAVA))
                        level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL)
                }
            }
            // the coarse dirt older versions sprinkled on dirt paths: path again
            if (top == target && BiomeDresser.routeDistance(s, x, z) < 1.3) {
                val here = BlockPos(x, top, z)
                if (level.getBlockState(here).`is`(net.minecraft.world.level.block.Blocks.COARSE_DIRT) &&
                    net.minecraft.core.Direction.Plane.HORIZONTAL.count { d -> level.getBlockState(here.relative(d)).`is`(net.minecraft.world.level.block.Blocks.DIRT_PATH) } >= 1)
                    level.setBlock(here, net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
            // old ground left lying on a route (it should be level path): lower it and lay the path again
            if (!inDen && top > target && top <= target + 4 && BiomeDresser.routeDistance(s, x, z) < 1.3 && mayCut(s, idx) &&
                (target + 1..top).all { y -> BiomeDresser.isNatural(level.getBlockState(BlockPos(x, y, z))) && level.getBlockState(BlockPos(x, y, z)).fluidState.isEmpty }) {
                Trace.note(x, z, "route lowered $top->$target")
                for (y in target + 1..top) level.setBlock(BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
                s.dressed.clear(idx)
                run { Trace.rebuild("route lowered", x, z); s.generationDirty = true }
                continue
            }
            // a thin leftover: a lone column of old ground (soil in it, so not a rock spire or ice spike on purpose)
            // standing over the ground around it
            if (!inDen && top >= target + 2 && thinLeftover(level, x, z, top, target) && mayCut(s, idx)) {
                Trace.note(x, z, "thin leftover cut $top->$target")
                for (y in target + 1..top) {
                    val p = BlockPos(x, y, z)
                    if (BiomeDresser.isNatural(level.getBlockState(p))) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
                }
                s.dressed.clear(idx)
                run { Trace.rebuild("thin leftover", x, z); s.generationDirty = true }
                continue
            }
            if (!tooTall(level, s, x, z, ring, top, target)) continue
            // a real leftover is wide (decorations like ice spikes and rock spires are thin)
            val wide = listOf(4 to 0, -4 to 0, 0 to 4, 0 to -4).count { (ox, oz) ->
                val nx = x + ox
                val nz = z + oz
                level.hasChunk(nx shr 4, nz shr 4) &&
                    tooTall(level, s, nx, nz, ring, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1, BiomeDresser.targetHeight(s, nx, nz, ring))
            }
            if (wide < 3 || inDen || !mayCut(s, idx)) continue
            Trace.note(x, z, "too tall cut $top->$target")
            // cut the natural blocks above the target height, then dress the column again
            for (y in target + 1..top) {
                val p = BlockPos(x, y, z)
                if (BiomeDresser.isNatural(level.getBlockState(p))) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
            s.dressed.clear(idx)
            if (found++ < 3) CobblemonSanctuary.LOGGER.info("Sanctuary: cut a leftover pillar at {} {} (was {}, should be {})", x, z, top, target)
            run { Trace.rebuild("too tall", x, z); s.generationDirty = true }
        }
        } finally {
            BiomeDresser.endHeights()
            usedMillis = usedMillis * 0.8 + (System.nanoTime() - started) / 1_000_000.0 * 0.2
        }
        repaired[s.id] = found
        if (i >= total) {
            repairCursor.remove(s.id)
            grounded.remove(s.id)
            // parts of the valley weren't loaded: look again later, while someone is there to load them
            if (repairMissed.remove(s.id)) repairAgainAt[s.id] = level.gameTime + 2400L else repairAgainAt.remove(s.id)
            if (found > 0) CobblemonSanctuary.LOGGER.info("Sanctuary: repaired {} columns", found)
            return false
        }
        repairCursor[s.id] = i
        return true
    }

    /**
     * Plants and crystals left hanging in the air after the ground under them was lowered (they have no collision,
     * so older versions didn't see them) are removed, and Cobblemon crystal clusters on the ground stand upright.
     */
    private fun looseBlocks(level: ServerLevel, x: Int, z: Int) {
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        var y = top
        while (y > ground && y > top - 64) {
            val p = BlockPos(x, y, z)
            val st = level.getBlockState(p)
            y--
            if (st.isAir || st.`is`(BlockTags.LEAVES) || st.`is`(BlockTags.LOGS)) continue
            if (!level.getBlockState(p.below()).isAir) continue
            val cob = BiomeDresser.isCobblemonDecor(st)
            val floating = if (cob) level.getBlockState(p.above()).isAir else (BiomeDresser.isClutter(st) && !st.canSurvive(level, p))
            if (floating) level.setBlock(p, air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
        }
        // Cobblemon crystal clusters placed by 1.0.0 were upside down: stand the ones on the ground up
        for (yy in ground..ground + 2) {
            val p = BlockPos(x, yy, z)
            val st = level.getBlockState(p)
            if (!BuiltInRegistries.BLOCK.getKey(st.block).let { it.namespace == "cobblemon" && it.path.endsWith("_cluster") }) continue
            val upright = BiomeDresser.pointUp(st)
            if (upright != st) level.setBlock(p, upright, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
        }
    }

    /**
     * Pieces left hanging in the air after the ground under them was lowered or a landmark was taken down: the top
     * of an old spire, a lamp post, a boulder, a bit of an old tree. Anything solid with nothing under it is followed
     * through everything it touches; if that's a small lump touching nothing else, it's floating and goes.
     * Big things (anything joined to the ground, or a player's sky base) are left alone, and so is anything holding
     * items (chests, the basket…).
     */
    /** Blocks this repair pass already found joined to the ground (a tree's canopy is traced once, not per column). */
    private val grounded = HashMap<UUID, HashSet<Long>>()

    private fun floatingBits(level: ServerLevel, s: Sanctuary, x: Int, z: Int) {
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
        var y = top
        val bottom = maxOf(level.minBuildHeight + 1, top - 100)
        var checked = 0
        val known = grounded.getOrPut(s.id) { HashSet() }
        while (y > bottom && checked < 6) {
            val p = BlockPos(x, y, z)
            y--
            val st = level.getBlockState(p)
            if (st.isAir || !st.fluidState.isEmpty) continue
            val below = level.getBlockState(p.below())
            if (!below.isAir) {
                if (!below.`is`(BlockTags.LEAVES) && !below.`is`(BlockTags.LOGS) && below.fluidState.isEmpty) return // reached solid ground under it
                continue
            }
            if (p.asLong() in known) continue
            checked++
            val lump = lumpAround(level, p, 400, known) ?: continue
            val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            // top down, so nothing drops or pops off as items
            Trace.note(x, z, "floating lump of ${lump.size} removed at ${p.y}")
            for (q in lump.sortedByDescending { it.y }) level.setBlock(q, air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            CobblemonSanctuary.LOGGER.debug("Sanctuary: removed a floating lump of {} blocks at {}", lump.size, p)
            return
        }
    }

    /**
     * Water standing above the lake level with open air beside it (a wall of water left by an older layout or a
     * leaky pond): it would spill, so it's taken away, from the top down.
     */
    private fun strayWater(level: ServerLevel, s: Sanctuary, x: Int, z: Int) {
        // in the caves: water left over inside the mountain, with the cave beside or under it, goes too
        Caves.at(s, x, z)?.let { spot ->
            val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            for (y in spot.ceil + 3 downTo spot.floor - 1) {
                val p = BlockPos(x, y, z)
                if (!level.getBlockState(p).`is`(net.minecraft.world.level.block.Blocks.WATER)) continue
                val open = level.getBlockState(p.below()).isAir || net.minecraft.core.Direction.Plane.HORIZONTAL.any { d ->
                    val n = p.relative(d)
                    level.hasChunk(n.x shr 4, n.z shr 4) && level.getBlockState(n).isAir
                }
                if (open) level.setBlock(p, air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
        }
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
        var y = top
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        while (y > BiomeDresser.WATER_Y) {
            val p = BlockPos(x, y, z)
            val st = level.getBlockState(p)
            if (!st.`is`(net.minecraft.world.level.block.Blocks.WATER)) {
                if (st.isAir || st.`is`(net.minecraft.world.level.block.Blocks.LILY_PAD)) { y--; continue }
                return
            }
            val open = net.minecraft.core.Direction.Plane.HORIZONTAL.any { d ->
                val n = p.relative(d)
                level.hasChunk(n.x shr 4, n.z shr 4) && level.getBlockState(n).isAir
            }
            if (!open) return
            level.setBlock(p, air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            if (level.getBlockState(p.above()).`is`(net.minecraft.world.level.block.Blocks.LILY_PAD)) level.setBlock(p.above(), air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            y--
        }
    }

    /**
     * Leaves with no trunk left (their tree was taken away, or cut by a rebuild): a tree's own leaves would rot away
     * once their trunk is gone, but only when the game notices the change, which it doesn't for the builder's work.
     * So from the lowest leaves of the column, the leaves joined to them are followed; if no log is found among or
     * next to them, they all go. Leaves placed on purpose (persistent ones) are left alone.
     */
    private fun orphanLeaves(level: ServerLevel, x: Int, z: Int) {
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1
        if (top <= ground) return
        var start: BlockPos? = null
        for (y in ground + 1..top) {
            val st = level.getBlockState(BlockPos(x, y, z))
            if (st.`is`(BlockTags.LEAVES)) { start = BlockPos(x, y, z); break }
        }
        val first = start ?: return
        val firstState = level.getBlockState(first)
        if (firstState.getOptionalValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT).orElse(true)) return
        val seen = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        val leaves = ArrayList<BlockPos>()
        seen += first.asLong(); queue += first
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            leaves += p
            if (leaves.size > 4000) return // huge: leave it be
            for (d in net.minecraft.core.Direction.entries) {
                val n = p.relative(d)
                if (!seen.add(n.asLong())) continue
                if (!level.hasChunk(n.x shr 4, n.z shr 4)) return
                val st = level.getBlockState(n)
                if (st.`is`(BlockTags.LOGS)) return // still has its tree
                if (st.`is`(BlockTags.LEAVES)) queue += n
            }
        }
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        for (p in leaves.sortedByDescending { it.y }) level.setBlock(p, air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
    }

    /** The lump of blocks joined to [start], or null if it's bigger than [max], holds items, or rests on anything. */
    private fun lumpAround(level: ServerLevel, start: BlockPos, max: Int, known: HashSet<Long>): List<BlockPos>? {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        val out = ArrayList<BlockPos>()
        fun joined(): List<BlockPos>? {
            // it's held up by something: remember all of it so the next columns don't trace it again
            if (known.size < 2_000_000) for (q in out) known += q.asLong()
            return null
        }
        seen += start.asLong(); queue += start
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (!level.hasChunk(p.x shr 4, p.z shr 4)) return null
            if (p.asLong() in known) return joined()
            if (level.getBlockEntity(p) != null) return joined()
            out += p
            if (out.size > max) return joined()
            for (d in net.minecraft.core.Direction.entries) {
                val n = p.relative(d)
                if (n.asLong() in seen) continue
                val ns = level.getBlockState(n)
                if (ns.isAir) continue
                // resting in water (a lily pad, a boat dock) isn't floating
                if (!ns.fluidState.isEmpty) return joined()
                seen += n.asLong(); queue += n
            }
        }
        return out
    }

    /** A lone column with soil in it, standing at least 2 above (nearly) all its neighbours. */
    private fun thinLeftover(level: ServerLevel, x: Int, z: Int, top: Int, target: Int): Boolean {
        var soil = false
        for (y in target + 1..top) {
            val st = level.getBlockState(BlockPos(x, y, z))
            if (!st.fluidState.isEmpty) return false
            if (st.`is`(BlockTags.DIRT) || st.`is`(BlockTags.SAND) || st.`is`(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) ||
                st.`is`(net.minecraft.world.level.block.Blocks.GRAVEL) || st.`is`(net.minecraft.world.level.block.Blocks.CLAY)) soil = true
            else if (!BiomeDresser.isNatural(st)) return false // something built on it: leave it
        }
        if (!soil) return false
        var lower = 0
        for ((ox, oz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            if (!level.hasChunk((x + ox) shr 4, (z + oz) shr 4)) return false
            // solid ground (a lake's water isn't ground: a pillar standing in a lake counts)
            if (level.getHeight(Heightmap.Types.OCEAN_FLOOR, x + ox, z + oz) - 1 <= top - 2) lower++
        }
        return lower >= 3
    }

    /** Solid natural ground standing more than 10 blocks above where the terrain should be (not a floating island). */
    private fun tooTall(level: ServerLevel, s: Sanctuary, x: Int, z: Int, ring: List<String>, top: Int, target: Int): Boolean {
        if (top <= target + 10) return false
        if (Landmarks.near(s, x, z)) return false
        for (y in target + 1..top) {
            val st = level.getBlockState(BlockPos(x, y, z))
            // water over a deep lake bed is the lake, not a leftover (cutting it made the water blink: cut, refilled, cut…)
            if (!st.fluidState.isEmpty) return false
            if (!BiomeDresser.isNatural(st)) return false
        }
        @Suppress("UNUSED_VARIABLE") val r = ring
        return true
    }

    fun tick(level: ServerLevel, data: SanctuaryData, s: Sanctuary) {
        if (s.state != SanctuaryState.ACTIVE) return
        // the region map is laid out once the residents are counted: wait for it
        if (!s.planned) return
        val now = level.gameTime
        if (now % 10L == 0L) BiomePainter.tick(level, s)
        if (now % 40L == 0L) Landmarks.maintain(level, s)
        if (!s.generationDirty && !cursors.containsKey(s.id)) {
            // the valley is built: check for leftovers, then put up the landmarks of awake biomes
            if (repair(level, s)) return
            repairAgainAt[s.id]?.let { at ->
                if (now >= at && level.players().isNotEmpty()) { repairAgainAt.remove(s.id); scheduleCheck(s.id) }
            }
            if (now % 40L == 0L) Landmarks.tick(level, s)
            if (now % 40L == 20L) Dens.upgrade(level, s)
            val retry = retryAt[s.id] ?: return
            if (now < retry) return
            retryAt.remove(s.id)
            run { Trace.rebuild("retry after missed columns"); s.generationDirty = true }
        }
        // once per server start: sweep the finished valley for leftovers (floating bits, stray water…)
        if (s.needsCheck && !s.generationDirty && s.id !in cursors) {
            s.needsCheck = false
            scheduleCheck(s.id)
        }
        if (s.generationDirty) {
            s.generationDirty = false
            // after every building pass: check for leftovers, loose plants, and add dens to new ground
            s.needsCheck = false
            scheduleCheck(s.id)
            cursors[s.id] = 0
            natureCursors[s.id] = 0
            extrasCursors[s.id] = 0
            deferred.remove(s.id)
            missedUnloaded[s.id] = false
            if (s.unlockedChanged) { s.unlockedChanged = false; recheckMeadow += s.id }
        }
        val recheck = s.id in recheckMeadow
        val ring = ZoneLayout.ring()
        val total = total(s)
        val radius = s.size.radius
        var i = cursors[s.id] ?: 0
        var budget = SanctuaryConfig.generationSpeed.get()
        val started = System.nanoTime()
        val deadline = started + buildMillis(level) * 1_000_000L
        var scanned = 0
        prefetch(level, s, i, total, now)
        val queue = deferred.getOrPut(s.id) { ArrayDeque() }
        /** Builds one column (its chunks are loaded). */
        fun build(idx: Int) {
            val ox = ColumnOrder.dx[idx]
            val oz = ColumnOrder.dz[idx]
            val x = s.center.x + ox
            val z = s.center.z + oz
            val dist = sqrt((ox * ox + oz * oz).toDouble())
            val inside = dist <= radius
            val hit = if (inside) ZoneLayout.locate(s, x, z, ring) else null
            if (inside && hit == null) return
            val asleep = inside && hit!!.zone !in s.unlocked && dist <= radius - 1.6
            val ok = try {
                when {
                    !inside -> BiomeDresser.withPhase(BiomeDresser.Phase.ALL) { BiomeDresser.dressRim(level, s, x, z, dist, ring) }
                    asleep -> BiomeDresser.withPhase(BiomeDresser.Phase.ALL) { BiomeDresser.dressMeadow(level, s, x, z, hit!!, ring) }
                    else -> BiomeDresser.withPhase(BiomeDresser.Phase.LAND) { BiomeDresser.dress(level, s, x, z, hit!!, ring) }
                }
            } catch (e: Exception) {
                CobblemonSanctuary.LOGGER.warn("Couldn't build column $x,$z", e)
                true
            }
            if (ok) {
                s.dressed.set(idx)
                if (asleep) s.meadow.set(idx) else s.meadow.clear(idx)
                // the land is new here: its trees and plants (and resident spots) come in the next waves
                // (the rim and sleeping meadows were done in one go)
                if (!inside || asleep) { s.decorated.set(idx); s.furnished.set(idx) } else { s.decorated.clear(idx); s.furnished.clear(idx) }
                BiomePainter.mark(s, x, z)
            } else run { Trace.rebuild("column failed", x, z); missedUnloaded[s.id] = true }
            budget--
        }
        BiomeDresser.beginHeights(s)
        try {
        // trees waiting for the ground around them to be finished
        if (now % 5L == 0L) BiomeDresser.plantPending(level, s, all = false)
        // first the columns set aside earlier whose chunks have loaded since
        var left = queue.size
        while (left-- > 0 && budget > 0 && System.nanoTime() < deadline) {
            val (idx, since) = queue.removeFirst()
            val x = s.center.x + ColumnOrder.dx[idx]
            val z = s.center.z + ColumnOrder.dz[idx]
            when (chunksReady(level, s, x, z, now)) {
                true -> build(idx)
                null -> run { Trace.rebuild("deferred chunk missing"); missedUnloaded[s.id] = true }
                false -> when {
                    now - since < 200 -> queue.addLast(idx to since)          // still loading
                    level.hasChunk(x shr 4, z shr 4) -> build(idx)              // waited 10 s: build with what's there
                    else -> run { Trace.rebuild("deferred gave up"); missedUnloaded[s.id] = true }                         // couldn't load it: next time
                }
            }
        }
        val landDeadline = if (i - (natureCursors[s.id] ?: 0) > 4000) started + (deadline - started) / 2 else deadline
        while (budget > 0 && i < total) {
            scanned++
            if ((scanned and 31) == 0 && System.nanoTime() > landDeadline) break
            val idx = i++
            val meadowDone = s.meadow.get(idx)
            val redo = s.rebuildZones.isNotEmpty()
            if (s.dressed.get(idx) && !(meadowDone && recheck) && !redo) continue
            val ox = ColumnOrder.dx[idx]
            val oz = ColumnOrder.dz[idx]
            val x = s.center.x + ox
            val z = s.center.z + oz
            val dist = sqrt((ox * ox + oz * oz).toDouble())
            val inside = dist <= radius
            val hit = if (inside) ZoneLayout.locate(s, x, z, ring) else null
            if (redo && s.dressed.get(idx) && !(meadowDone && recheck)) {
                // only the biomes being rebuilt (and the strip next to them, whose shape blends with them)
                val pocket = if (hit != null) ZoneLayout.pocketAt(s, x, z)?.first else null
                val touched = hit != null && (hit.zone in s.rebuildZones || (hit.neighbour in s.rebuildZones && hit.edgeDistance < 26) ||
                    (pocket != null && (pocket.zone in s.rebuildZones || pocket.host in s.rebuildZones)))
                if (!touched || meadowDone) continue
                s.dressed.clear(idx)
            }
            if (inside && hit == null) continue
            // a biome that hasn't woken up is a plain meadow until a Pokémon of its type moves in (the wall is always built)
            val asleep = inside && hit!!.zone !in s.unlocked && dist <= radius - 1.6
            if (meadowDone && s.dressed.get(idx)) {
                if (asleep) continue // still asleep: stays a meadow
                s.dressed.clear(idx); s.meadow.clear(idx) // it woke up: build the real biome here
            }
            when (chunksReady(level, s, x, z, now)) {
                // not loaded yet: set it aside and carry on with the next ones (only stop when too many are waiting)
                false -> { if (queue.size < 4096) { queue.addLast(idx to now); continue } else { i = idx; break } }
                null -> { run { Trace.rebuild("chunk not loadable"); missedUnloaded[s.id] = true }; continue }        // couldn't load it: later
                true -> build(idx)
            }
        }
        // ---- the waves that follow the land: nature, then the residents' spots
        val landDone = i >= total && queue.isEmpty()
        val waiting = queue.mapTo(HashSet()) { it.first }
        /** The land of column [idx] is final (built, or passed and nothing left to do there). */
        fun landFinal(idx: Int) = landDone || s.dressed.get(idx) || (idx < i && idx !in waiting)
        /** All columns within [r] of [idx] pass [ok]. */
        fun around(idx: Int, r: Int, ok: (Int) -> Boolean): Boolean {
            val ox = ColumnOrder.dx[idx]
            val oz = ColumnOrder.dz[idx]
            for (dx in -r..r) for (dz in -r..r) {
                val n = ColumnOrder.indexOf(ox + dx, oz + dz)
                if (n < 0 || n >= total) continue
                if (!ok(n)) return false
            }
            return true
        }
        /** Runs one wave over the next columns it may work on. Returns its new cursor. */
        fun wave(start: Int, wavePhase: BiomeDresser.Phase, done: java.util.BitSet, before: (Int) -> Boolean, beforeAll: Boolean, gap: Int): Int {
            var c = start
            var n = 0
            while (c < total) {
                if ((++n and 15) == 0 && System.nanoTime() > deadline) break
                if (done.get(c)) { c++; continue }
                // wait until everything around has had the wave before
                if (!beforeAll && !around(c, gap, before)) break
                val ox = ColumnOrder.dx[c]
                val oz = ColumnOrder.dz[c]
                val x = s.center.x + ox
                val z = s.center.z + oz
                val dist = sqrt((ox * ox + oz * oz).toDouble())
                val hit = if (dist <= radius) ZoneLayout.locate(s, x, z, ring) else null
                // nothing to add on the rim, in sleeping meadows, outside the map, or where the land isn't built
                if (hit == null || s.meadow.get(c) || !s.dressed.get(c)) { done.set(c); c++; continue }
                when (chunksReady(level, s, x, z, now)) {
                    false -> break
                    null -> { done.set(c); c++; continue }
                    true -> {}
                }
                try {
                    BiomeDresser.withPhase(wavePhase) { BiomeDresser.dress(level, s, x, z, hit, ring) }
                } catch (e: Exception) {
                    CobblemonSanctuary.LOGGER.warn("Couldn't decorate column $x,$z", e)
                }
                done.set(c)
                c++
            }
            return c
        }
        val j = wave(natureCursors[s.id] ?: 0, BiomeDresser.Phase.NATURE, s.decorated, ::landFinal, landDone, 9)
        natureCursors[s.id] = j
        val natureDone = landDone && j >= total
        val k = wave(extrasCursors[s.id] ?: 0, BiomeDresser.Phase.EXTRAS, s.furnished, { idx -> s.decorated.get(idx) || idx < j }, natureDone, 7)
        extrasCursors[s.id] = k
        } finally {
            BiomeDresser.endHeights()
            usedMillis = usedMillis * 0.8 + (System.nanoTime() - started) / 1_000_000.0 * 0.2
        }
        if (now % 20L == 0L) trackPace(s)
        if (i >= total && queue.isEmpty() && (natureCursors[s.id] ?: 0) >= total && (extrasCursors[s.id] ?: 0) >= total) {
            natureCursors.remove(s.id)
            extrasCursors.remove(s.id)
            BiomeDresser.beginHeights(s)
            try { BiomeDresser.plantPending(level, s, all = true) } finally { BiomeDresser.endHeights() }
            pace.remove(s.id)
            cursors.remove(s.id)
            deferred.remove(s.id)
            requested.remove(s.id)
            BiomeDresser.endPass(s)
            s.rebuildZones.clear()
            recheckMeadow -= s.id
            if (missedUnloaded[s.id] == true) retryAt[s.id] = now + 600
            else if (s.oldWallRadius > 0) s.oldWallRadius = 0
        } else {
            cursors[s.id] = i
        }
        data.setDirty()
    }
}
