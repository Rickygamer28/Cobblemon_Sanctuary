package com.rickygamer28.sanctuary

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.world.BiomeDresser
import com.rickygamer28.sanctuary.world.Caves
import com.rickygamer28.sanctuary.world.Dens
import com.rickygamer28.sanctuary.world.Landmarks
import com.rickygamer28.sanctuary.world.SanctuaryGenerator
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.levelgen.Heightmap
import java.io.File
import java.util.UUID

/**
 * A test of the whole valley builder, for development only. It runs when the server is started with
 * `-Dsanctuary.selftest=<SIZE>` and does nothing otherwise.
 *
 * It makes a sanctuary with every habitat awake, lets the builder make it (all waves, cleanup, dens, landmarks),
 * scans every column of the result for problems, then reshapes the valley, builds it again and scans again (most
 * problems come from rebuilding over an older valley). The report goes to `selftest-report.txt`, then the server
 * stops.
 */
object SelfTest {
    private val sizeArg: String? = System.getProperty("sanctuary.selftest")
    val active get() = sizeArg != null

    private var s: Sanctuary? = null
    private var stage = 0
    private var stageStart = 0L
    private var idleSince = -1L
    private var startedAt = 0L
    private val report = StringBuilder()
    private const val MAX_STAGE_TICKS = 20L * 60 * 12

    fun isTest(sanctuary: Sanctuary) = s?.id == sanctuary.id

    private fun log(line: String) {
        report.appendLine(line)
        CobblemonSanctuary.LOGGER.info("[selftest] {}", line)
    }

    /** Called every server tick (while active). */
    fun tick(server: MinecraftServer) {
        if (!active) return
        val level = SanctuaryDimension.level(server) ?: return
        val data = SanctuaryData.get(server)
        val now = level.gameTime
        val sanctuary = s ?: create(server, level, data, now).also { s = it }
        try {
            SanctuaryGenerator.tick(level, data, sanctuary)
        } catch (e: Exception) {
            log("EXCEPTION in generator: $e")
            CobblemonSanctuary.LOGGER.error("[selftest] generator failed", e)
        }
        val busy = SanctuaryGenerator.isGrowing(sanctuary) || SanctuaryGenerator.isRepairing(sanctuary)
        if (busy) idleSince = -1L else if (idleSince < 0) idleSince = now
        val timedOut = now - stageStart > MAX_STAGE_TICKS
        // finished: nothing building, cleaned up, and a minute for dens and landmarks to go up
        val settled = idleSince >= 0 && now - idleSince > 20L * 60
        if (!settled && !timedOut) return
        val secs = (now - stageStart) / 20.0
        when (stage) {
            0 -> {
                log("== build 1 (fresh valley) finished in ${"%.0f".format(secs)} s${if (timedOut) " (TIMED OUT)" else ""}")
                scan(level, sanctuary)
                // reshape and build again over it
                sanctuary.reshape()
                plan(sanctuary)
                stage = 1
                stageStart = now
                idleSince = -1L
            }
            1 -> {
                log("== build 2 (after Reshape) finished in ${"%.0f".format(secs)} s${if (timedOut) " (TIMED OUT)" else ""}")
                scan(level, sanctuary)
                // repair an area, as the button does, and check again
                val c = sanctuary.center
                val n = SanctuaryGenerator.repairArea(level, sanctuary, c.x + sanctuary.size.radius / 2, c.z, 40)
                log("repair-here test: $n columns redone")
                stage = 2
                stageStart = now
                idleSince = -1L
            }
            else -> {
                log("== after Repair here, finished in ${"%.0f".format(secs)} s${if (timedOut) " (TIMED OUT)" else ""}")
                scan(level, sanctuary)
                log("total test time ${(now - startedAt) / 20} s")
                File("selftest-report.txt").writeText(report.toString())
                CobblemonSanctuary.LOGGER.info("[selftest] done, report written")
                server.halt(false)
            }
        }
    }

    private fun create(server: MinecraftServer, level: ServerLevel, data: SanctuaryData, now: Long): Sanctuary {
        val size = runCatching { SanctuarySize.valueOf(sizeArg!!.uppercase()) }.getOrDefault(SanctuarySize.MEDIUM)
        val seed = System.getProperty("sanctuary.selftest.seed")?.toLongOrNull() ?: 12345L
        val slot = data.nextSlot++
        val sanctuary = Sanctuary(UUID.randomUUID(), UUID(0, 1), "SelfTest", SanctuaryDimension.centerForSlot(slot), size, SanctuaryState.ACTIVE, seed, slot)
        data.sanctuaries[sanctuary.id] = sanctuary
        plan(sanctuary)
        sanctuary.rebuild()
        startedAt = now
        stageStart = now
        log("selftest: size $size (radius ${size.radius}), seed $seed, habitats ${sanctuary.habitats.keys}")
        return sanctuary
    }

    /** Every habitat awake, with made-up resident numbers (busier ones bigger), and the region laid out. */
    private fun plan(s: Sanctuary) {
        val counts = linkedMapOf<String, Int>()
        ZoneLayout.HABITATS.forEachIndexed { i, z -> counts[z] = 40 + (i * 37) % 160 }
        s.typeCounts = counts
        s.unlocked = linkedSetOf(ZoneLayout.HUB) + ZoneLayout.HABITATS
        s.habitats.clear()
        s.habitats.putAll(ZoneLayout.plan(s, counts))
        s.planned = true
        s.planRadius = s.size.radius
        s.generationDirty = true
    }

    // ------------------------------------------------------------------ the scan

    private class Finding(val name: String) {
        var count = 0
        val samples = ArrayList<String>()
        fun add(s: Sanctuary, x: Int, y: Int, z: Int, note: String = "") {
            count++
            if (samples.size < 8) samples += "(${x - s.center.x}, ${y}, ${z - s.center.z})${if (note.isNotEmpty()) " $note" else ""}"
        }
    }

    private fun scan(level: ServerLevel, s: Sanctuary) {
        val f = linkedMapOf<String, Finding>()
        fun find(name: String) = f.getOrPut(name) { Finding(name) }
        val r = s.size.radius - 3
        var columns = 0
        var unloaded = 0
        val water = BiomeDresser.WATER_Y
        val seenLeaves = HashSet<Long>()
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            val x = s.center.x + dx
            val z = s.center.z + dz
            if (!level.hasChunk(x shr 4, z shr 4)) { unloaded++; continue }
            columns++
            val hit = ZoneLayout.locate(s, x, z) ?: continue
            if (Landmarks.near(s, x, z) || Dens.covers(s, x, z)) continue
            val target = BiomeDresser.targetHeight(s, x, z, ZoneLayout.HABITATS)
            val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
            // the ground: the highest natural block, not counting plants, leaves, logs and decorations
            var ground = top
            while (ground > level.minBuildHeight) {
                val st = level.getBlockState(BlockPos(x, ground, z))
                if (BiomeDresser.isNatural(st) && st.fluidState.isEmpty) break
                ground--
            }
            val cave = Caves.at(s, x, z)
            val expected = if (cave != null && cave.mouth) cave.floor - 1 else target
            val diff = ground - expected
            if (diff > 3) find("ground too high (>3 over its height)").add(s, x, ground, z, "zone ${hit.zone} +$diff")
            if (diff < -3) find("ground too low (>3 under its height)").add(s, x, ground, z, "zone ${hit.zone} $diff")
            // water where the ground is under the water line
            if (target < water && cave == null) {
                val st = level.getBlockState(BlockPos(x, water, z))
                if (!st.`is`(Blocks.WATER) && !st.`is`(Blocks.ICE) && !st.fluidState.isSource && !st.`is`(Blocks.LILY_PAD)) find("missing water").add(s, x, water, z, "zone ${hit.zone} has ${st.block.name.string}")
            }
            // water standing above the water line with air beside it (it would spill)
            for (y in water + 1..top) {
                val p = BlockPos(x, y, z)
                if (!level.getBlockState(p).`is`(Blocks.WATER)) continue
                if (Direction.Plane.HORIZONTAL.any { level.getBlockState(p.relative(it)).isAir } || level.getBlockState(p.below()).isAir) { find("spilling water").add(s, x, y, z); break }
            }
            // things in the air: the lowest block of each hanging piece
            var y = top
            var checks = 0
            while (y > ground && checks < 4) {
                val p = BlockPos(x, y, z)
                y--
                val st = level.getBlockState(p)
                if (st.isAir || !st.fluidState.isEmpty) continue
                if (!level.getBlockState(p.below()).isAir) continue
                checks++
                if (st.`is`(BlockTags.LEAVES)) {
                    if (st.getOptionalValue(LeavesBlock.PERSISTENT).orElse(true) || p.asLong() in seenLeaves) continue
                    if (!leavesHaveTrunk(level, p, seenLeaves)) find("leaves with no trunk").add(s, x, p.y, z)
                } else if (!st.`is`(BlockTags.LOGS) && !BiomeDresser.isClutter(st) && floating(level, p)) {
                    find("floating block").add(s, x, p.y, z, st.block.name.string)
                }
            }
            // a lone pillar of ground
            if (ground >= target + 3) {
                var lower = 0
                for (d in Direction.Plane.HORIZONTAL) if (level.getHeight(Heightmap.Types.OCEAN_FLOOR, x + d.stepX, z + d.stepZ) - 1 <= ground - 2) lower++
                if (lower >= 3) find("lone pillar").add(s, x, ground, z)
            }
            // the cave must have rock over it (except at its mouths)
            if (cave != null && !cave.mouth) {
                val roof = BlockPos(x, cave.ceil + 1, z)
                if (level.getBlockState(roof).isAir) find("cave open to the sky").add(s, x, roof.y, z)
            }
        }
        // dens: their way in must be open
        var dens = 0
        for (d in Dens.all(s)) {
            dens++
            if (!level.isLoaded(d.mouth)) continue
            val side = d.dir.clockWise
            var blocked = 0
            for (i in 0..3) for (w in -1..1) for (dy in 0..2) {
                val p = d.mouth.relative(d.dir, i).relative(side, w).above(dy)
                val st = level.getBlockState(p)
                if (!st.isAir && !st.canBeReplaced() && st.fluidState.isEmpty) blocked++
            }
            if (blocked > 3) find("den entrance blocked").add(s, d.mouth.x, d.mouth.y, d.mouth.z, "$blocked blocks")
        }
        log("scanned $columns columns ($unloaded not loaded); ${s.landmarks.count { it.value != Landmarks.NONE }} landmarks, $dens dens, ${ZoneLayout.pockets(s).count { it.live(s) }} pockets")
        if (f.isEmpty()) log("  no problems found")
        for (finding in f.values) {
            log("  ${finding.name}: ${finding.count}")
            for (sample in finding.samples) log("      at $sample")
        }
    }

    private fun leavesHaveTrunk(level: ServerLevel, start: BlockPos, seen: HashSet<Long>): Boolean {
        val queue = ArrayDeque<BlockPos>()
        val visited = HashSet<Long>()
        queue += start; visited += start.asLong()
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            seen += p.asLong()
            if (visited.size > 4000) return true
            for (d in Direction.entries) {
                val n = p.relative(d)
                if (!visited.add(n.asLong())) continue
                val st = level.getBlockState(n)
                if (st.`is`(BlockTags.LOGS)) return true
                if (st.`is`(BlockTags.LEAVES)) queue += n
            }
        }
        return false
    }

    /** Is the piece of blocks joined to [start] small and touching nothing else (hanging in the air)? */
    private fun floating(level: ServerLevel, start: BlockPos): Boolean {
        val queue = ArrayDeque<BlockPos>()
        val seen = HashSet<Long>()
        queue += start; seen += start.asLong()
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (seen.size > 400) return false
            for (d in Direction.entries) {
                val n = p.relative(d)
                if (n.asLong() in seen) continue
                val st = level.getBlockState(n)
                if (st.isAir) continue
                if (!st.fluidState.isEmpty) return false
                seen += n.asLong(); queue += n
            }
        }
        return true
    }
}
