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
    private val retryAt = HashMap<UUID, Long>()
    private val missedUnloaded = HashMap<UUID, Boolean>()
    private val recheckMeadow = HashSet<UUID>()

    fun forget(id: UUID) { recheckMeadow.remove(id); cursors.remove(id); retryAt.remove(id); missedUnloaded.remove(id); BiomePainter.forget(id) }

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
        return (c * 100L / total(s).coerceAtLeast(1)).toInt()
    }

    private val repairCursor = HashMap<UUID, Int>()
    private val repaired = HashMap<UUID, Int>()
    private val repairMissed = HashSet<UUID>()
    private val repairAgainAt = HashMap<UUID, Long>()

    /** Check the valley once after loading (and after each rebuild). */
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
        val deadline = System.nanoTime() + SanctuaryConfig.generationMillis.get() * 1_000_000L
        var scanned = 0
        var found = repaired[s.id] ?: 0
        while (i < total) {
            if ((++scanned and 31) == 0 && System.nanoTime() > deadline) break
            val idx = i++
            if (!s.dressed.get(idx)) continue
            val x = s.center.x + ColumnOrder.dx[idx]
            val z = s.center.z + ColumnOrder.dz[idx]
            if (!level.hasChunk(x shr 4, z shr 4)) { repairMissed += s.id; continue }
            if (Landmarks.near(s, x, z)) continue
            looseBlocks(level, x, z)
            if (s.meadow.get(idx)) continue
            Dens.maybeBuild(level, s, x, z, ring)
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            val target = BiomeDresser.targetHeight(s, x, z, ring)
            if (!tooTall(level, s, x, z, ring, top, target)) continue
            // a real leftover is wide (decorations like ice spikes and rock spires are thin)
            val wide = listOf(4 to 0, -4 to 0, 0 to 4, 0 to -4).count { (ox, oz) ->
                val nx = x + ox
                val nz = z + oz
                level.hasChunk(nx shr 4, nz shr 4) &&
                    tooTall(level, s, nx, nz, ring, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1, BiomeDresser.targetHeight(s, nx, nz, ring))
            }
            if (wide < 3) continue
            // cut the natural blocks above the target height, then dress the column again
            for (y in target + 1..top) {
                val p = BlockPos(x, y, z)
                if (BiomeDresser.isNatural(level.getBlockState(p))) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
            s.dressed.clear(idx)
            if (found++ < 3) CobblemonSanctuary.LOGGER.info("Sanctuary: cut a leftover pillar at {} {} (was {}, should be {})", x, z, top, target)
            s.generationDirty = true
        }
        repaired[s.id] = found
        if (i >= total) {
            repairCursor.remove(s.id)
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

    /** Solid natural ground standing more than 10 blocks above where the terrain should be (not a floating island). */
    private fun tooTall(level: ServerLevel, s: Sanctuary, x: Int, z: Int, ring: List<String>, top: Int, target: Int): Boolean {
        if (top <= target + 10) return false
        if (Landmarks.near(s, x, z)) return false
        for (y in target + 1..top) if (!BiomeDresser.isNatural(level.getBlockState(BlockPos(x, y, z)))) return false
        @Suppress("UNUSED_VARIABLE") val r = ring
        return true
    }

    fun tick(level: ServerLevel, data: SanctuaryData, s: Sanctuary) {
        if (s.state != SanctuaryState.ACTIVE) return
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
            val retry = retryAt[s.id] ?: return
            if (now < retry) return
            retryAt.remove(s.id)
            s.generationDirty = true
        }
        if (s.generationDirty) {
            s.generationDirty = false
            // after every building pass: check for leftovers, loose plants, and add dens to new ground
            s.needsCheck = false
            scheduleCheck(s.id)
            cursors[s.id] = 0
            missedUnloaded[s.id] = false
            if (s.unlockedChanged) { s.unlockedChanged = false; recheckMeadow += s.id }
        }
        val recheck = s.id in recheckMeadow
        val ring = ZoneLayout.ring()
        val total = total(s)
        val radius = s.size.radius
        var i = cursors[s.id] ?: 0
        var budget = SanctuaryConfig.generationSpeed.get()
        val deadline = System.nanoTime() + SanctuaryConfig.generationMillis.get() * 1_000_000L
        var scanned = 0
        while (budget > 0 && i < total) {
            scanned++
            if ((scanned and 31) == 0 && System.nanoTime() > deadline) break
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
                val touched = hit != null && (hit.zone in s.rebuildZones || (hit.neighbour in s.rebuildZones && hit.edgeDistance < 26))
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
            if (!level.hasChunk(x shr 4, z shr 4)) { missedUnloaded[s.id] = true; continue }
            val ok = try {
                when {
                    !inside -> BiomeDresser.dressRim(level, s, x, z, dist, ring)
                    asleep -> BiomeDresser.dressMeadow(level, s, x, z, hit!!, ring)
                    else -> BiomeDresser.dress(level, s, x, z, hit!!, ring)
                }
            } catch (e: Exception) {
                CobblemonSanctuary.LOGGER.warn("Couldn't build column $x,$z", e)
                true
            }
            if (ok) {
                s.dressed.set(idx)
                if (asleep) s.meadow.set(idx) else s.meadow.clear(idx)
                BiomePainter.mark(s, x, z)
            } else missedUnloaded[s.id] = true
            budget--
        }
        if (i >= total) {
            cursors.remove(s.id)
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
