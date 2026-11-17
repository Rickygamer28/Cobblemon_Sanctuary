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
import com.rickygamer28.sanctuary.world.Trace
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

    private var announced = false

    /** Called every server tick (while active). */
    fun tick(server: MinecraftServer) {
        if (!active) return
        val level = SanctuaryDimension.level(server)
        Trace.on = true
        Trace.tick = server.tickCount.toLong()
        if (!announced) { announced = true; CobblemonSanctuary.LOGGER.info("[selftest] active, size {}, dimension {}", sizeArg, if (level == null) "MISSING" else "ok") }
        if (level == null) return
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
        if ((now - stageStart) % 600L == 0L) CobblemonSanctuary.LOGGER.info("[selftest] stage {} at {} s: building {}%, repairing {}, tick {} ms, rebuilt because: {} {}",
            stage, (now - stageStart) / 20, SanctuaryGenerator.progress(sanctuary), SanctuaryGenerator.isRepairing(sanctuary), "%.1f".format(server.currentSmoothedTickTime), Trace.reasons, Trace.lastAt)
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
            2 -> {
                log("== after Repair here, finished in ${"%.0f".format(secs)} s${if (timedOut) " (TIMED OUT)" else ""}")
                scan(level, sanctuary)
                // as in play: a valley with a few habitats, the rest waking up later
                sanctuary.reshape()
                plan(sanctuary, FIRST)
                log("few habitats: ${sanctuary.habitats.keys}")
                stage = 3; stageStart = now; idleSince = -1L
            }
            3 -> {
                log("== build 3 (only ${FIRST.size} habitats awake) finished in ${"%.0f".format(secs)} s${if (timedOut) " (TIMED OUT)" else ""}")
                scan(level, sanctuary)
                wakeTheRest(sanctuary)
                log("woke up: ${ZoneLayout.HABITATS - FIRST.toSet()} (no room for: ${ZoneLayout.HABITATS - sanctuary.habitats.keys})")
                stage = 4; stageStart = now; idleSince = -1L
            }
            else -> {
                log("== after the rest woke up, finished in ${"%.0f".format(secs)} s${if (timedOut) " (TIMED OUT)" else ""}")
                scan(level, sanctuary)
                log("total test time ${(now - startedAt) / 20} s")
                File("selftest-report.txt").writeText(report.toString())
                CobblemonSanctuary.LOGGER.info("[selftest] done, report written")
                server.halt(false)
            }
        }
    }

    private val DECOR_ROCK = setOf(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.PACKED_ICE, Blocks.BASALT, Blocks.POLISHED_ANDESITE,
        Blocks.SMOOTH_STONE, Blocks.BONE_BLOCK, Blocks.CALCITE, Blocks.AMETHYST_BLOCK, Blocks.STONE, Blocks.ANDESITE, Blocks.WEATHERED_COPPER,
        Blocks.EXPOSED_COPPER, Blocks.RAW_IRON_BLOCK, Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE, Blocks.SNOW_BLOCK)

    /** Plain rock alone above the ground could be a leftover, so it isn't excused as a decoration. */
    private val PLAIN_ROCK = setOf(Blocks.STONE, Blocks.ANDESITE, Blocks.SNOW_BLOCK, Blocks.BLACKSTONE)

    private val FIRST = listOf("water", "grass", "fire", "rock")

    private fun counts(zones: List<String>): LinkedHashMap<String, Int> {
        val counts = linkedMapOf<String, Int>()
        ZoneLayout.HABITATS.forEachIndexed { i, z -> if (z in zones) counts[z] = 40 + (i * 37) % 160 }
        return counts
    }

    /** What Residents does when new types move in: wake them, fit new habitats into free meadows, rebuild. */
    private fun wakeTheRest(s: Sanctuary) {
        val counts = counts(ZoneLayout.HABITATS)
        val newTypes = ZoneLayout.HABITATS.filter { it !in s.unlocked }
        s.typeCounts = counts
        s.unlocked = linkedSetOf(ZoneLayout.HUB) + ZoneLayout.HABITATS
        s.unlockedChanged = true
        SanctuaryGenerator.redoCaves(s)
        s.rebuildZones += newTypes.filter { z -> ZoneLayout.pockets(s).any { it.zone == z } }
        s.rebuildZones += newTypes.filter { it in s.habitats }
        for (zone in ZoneLayout.HABITATS) {
            if (zone in s.habitats) continue
            val hb = ZoneLayout.fitIn(s, zone, counts[zone] ?: 1) ?: continue
            s.habitats[zone] = hb
            s.rebuildZones += zone
        }
        s.generationDirty = true
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
        log("around the town: " + sanctuary.habitats.entries.sortedBy { Math.floorMod((it.value.angle * 1000).toLong(), (2 * Math.PI * 1000).toLong()) }.joinToString(" > ") { it.key })
        log("selftest: size $size (radius ${size.radius}), seed $seed, habitats ${sanctuary.habitats.keys}")
        return sanctuary
    }

    /** The given habitats awake (all by default), with made-up resident numbers, and the region laid out. */
    private fun plan(s: Sanctuary, zones: List<String> = ZoneLayout.HABITATS) {
        val counts = counts(zones)
        s.typeCounts = counts
        s.unlocked = linkedSetOf(ZoneLayout.HUB) + zones
        s.habitats.clear()
        s.habitats.putAll(ZoneLayout.plan(s, counts))
        s.planned = true
        s.planRadius = s.size.radius
        s.generationDirty = true
    }

    // ------------------------------------------------------------------ the scan

    private var scanLevel: ServerLevel? = null
    /** What each column's top was at the previous scan ("y block"), to see what a problem column used to be. */
    private var before = HashMap<Long, String>()
    private var now = HashMap<Long, String>()

    /** What the column is made of, from a little under [from] up to its top: "stone x3, packed_ice x5, air…". */
    private fun profile(level: ServerLevel, x: Int, z: Int, from: Int): String {
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        val out = ArrayList<String>()
        var last = ""
        var n = 0
        for (y in from..top) {
            val name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).path
            if (name == last) { n++; continue }
            if (n > 0) out += if (n > 1) "$last x$n" else last
            last = name; n = 1
        }
        if (n > 0) out += if (n > 1) "$last x$n" else last
        return "[from y$from: ${out.joinToString(", ")}]"
    }

    private class Finding(val name: String) {
        var count = 0
        val samples = ArrayList<String>()
        val kinds = HashMap<String, Int>()
        fun add(s: Sanctuary, x: Int, y: Int, z: Int, note: String = "") {
            count++
            val level = scanLevel
            val zone = ZoneLayout.locate(s, x, z)?.zone ?: "-"
            val pocket = ZoneLayout.pocketAt(s, x, z)?.takeIf { it.first.live(s) && it.second <= it.first.r + 1.5 }?.first?.zone
            val key = zone + (if (pocket != null) "/pocket:$pocket" else "") + (if (level != null) "/" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).path else "")
            kinds[key] = (kinds[key] ?: 0) + 1
            val idx = com.rickygamer28.sanctuary.world.ColumnOrder.indexOf(x - s.center.x, z - s.center.z)
            val bits = if (idx >= 0) " dressed=${s.dressed.get(idx)} nature=${s.decorated.get(idx)} extras=${s.furnished.get(idx)} meadow=${s.meadow.get(idx)}" else ""
            val was = before[BlockPos.asLong(x, 0, z)]?.let { " was: $it" } ?: ""
            // two examples of each kind (zone and block), with what the column is made of
            if (kinds[key]!! <= 2 && samples.size < 16) samples += "[$key] (${x - s.center.x}, ${y}, ${z - s.center.z})${if (note.isNotEmpty()) " $note" else ""}" +
                (if (level != null) " " + profile(level, x, z, y - 6) + bits + was + " trace: " + com.rickygamer28.sanctuary.world.Trace.of(x, z) else "")
        }
    }

    private fun scan(level: ServerLevel, s: Sanctuary) {
        scanLevel = level
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
            // load it (the builder's chunks have unloaded since; a test may wait for the disk)
            if (!level.hasChunk(x shr 4, z shr 4)) { unloaded++; level.getChunk(x shr 4, z shr 4) }
            columns++
            val hit = ZoneLayout.locate(s, x, z) ?: continue
            if (Landmarks.near(s, x, z) || Dens.covers(s, x, z)) continue
            val target = BiomeDresser.targetHeight(s, x, z, ZoneLayout.HABITATS)
            val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
            now[BlockPos.asLong(x, 0, z)] = "$top " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, top, z)).block).path +
                "/" + (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1)
            // the ground: the highest natural block, not counting plants, leaves, logs and decorations
            var ground = top
            while (ground > level.minBuildHeight) {
                val st = level.getBlockState(BlockPos(x, ground, z))
                if (BiomeDresser.isNatural(st) && st.fluidState.isEmpty) break
                ground--
            }
            val cave = Caves.at(s, x, z)
            // an open cave mouth is dug down to the cave floor (a roofed one keeps its ground)
            val expected = if (cave != null && cave.mouth && target - cave.ceil < 4) cave.floor - 1 else target
            // decorations standing on the ground (boulders, spikes, pylons, basalt columns) aren't ground
            val decorOnly = ground > target && (target + 1..ground).map { yy -> level.getBlockState(BlockPos(x, yy, z)).block }.let { bl ->
                bl.all { it in DECOR_ROCK } && bl.any { it !in PLAIN_ROCK } }
            val diff = if (decorOnly) 0 else ground - expected
            fun why(): String {
                val base = BiomeDresser.baseHeight(s, x, z)
                val cover = Caves.cover(s, x, z)
                Caves.forget(s.id)
                val fresh = BiomeDresser.targetHeight(s, x, z, ZoneLayout.HABITATS)
                val c2 = Caves.at(s, x, z)
                return "target=$target base=$base cover=${"%.1f".format(cover)} fresh=$fresh cave=${cave?.let { "${it.floor}..${it.ceil}${if (it.mouth) " mouth" else ""}" }}/${c2?.let { "${it.floor}..${it.ceil}" }} nb=${hit.neighbour} edge=${"%.0f".format(hit.edgeDistance)}"
            }
            if (diff > 3) find("ground too high (>3 over its height)").add(s, x, ground, z, "zone ${hit.zone} +$diff ${why()}")
            if (diff < -3) find("ground too low (>3 under its height)").add(s, x, ground, z, "zone ${hit.zone} $diff ${why()}")
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
                    if (p.asLong() in seenLeaves) continue
                    if (!leavesHaveTrunk(level, p, seenLeaves)) find("leaves with no trunk").add(s, x, p.y, z)
                } else if (!st.`is`(BlockTags.LOGS) && !BiomeDresser.isClutter(st) && floating(level, p)) {
                    find("floating block").add(s, x, p.y, z, st.block.name.string)
                }
            }
            // a lone pillar of ground
            if (ground >= target + 3 && !decorOnly) {
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
        caveCheck(level, s, ::find)
        // dens: their way in must be open
        var dens = 0
        for (d in Dens.all(s)) {
            dens++
            level.getChunk(d.mouth)
            val side = d.dir.clockWise
            var blocked = 0
            for (i in 0..3) for (w in -1..1) for (dy in 0..2) {
                val p = d.mouth.relative(d.dir, i).relative(side, w).above(dy)
                val st = level.getBlockState(p)
                if (!st.isAir && !st.canBeReplaced() && st.fluidState.isEmpty) blocked++
            }
            if (blocked > 3) find("den entrance blocked").add(s, d.mouth.x, d.mouth.y, d.mouth.z, "$blocked blocks")
        }
        try { File("selftest-report.txt").writeText(report.toString()) } catch (_: Exception) {}
        ZoneLayout.why.clear(); ZoneLayout.forgetPockets(s.id)
        ZoneLayout.pockets(s)
        log("pocket spots turned down: ${ZoneLayout.why}")
        log("pockets: " + ZoneLayout.pockets(s).joinToString(", ") { "${it.zone} in ${it.host} r${it.r}${if (it.live(s)) "" else " (asleep)"}" })
        log("scanned $columns columns ($unloaded loaded from disk); ${s.landmarks.count { it.value != Landmarks.NONE }} landmarks, $dens dens, ${ZoneLayout.pockets(s).count { it.live(s) }} pockets")
        if (f.isEmpty()) log("  no problems found")
        for (finding in f.values) {
            log("  ${finding.name}: ${finding.count}")
            log("      by zone/block: " + finding.kinds.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "${it.key} ${it.value}" })
            for (sample in finding.samples) log("      at $sample")
        }
        try { File("selftest-report.txt").writeText(report.toString()) } catch (_: Exception) {}
        before = now
        now = HashMap()
    }

    /**
     * The caves: every hall and entrance can be walked to from the first hall (through the joining tunnels), nothing
     * blocks the passages, no lava runs anywhere and no water got in.
     */
    private fun caveCheck(level: ServerLevel, s: Sanctuary, find: (String) -> Finding) {
        val halls = Caves.halls(s)
        if (halls.isEmpty()) { log("caves: none"); return }
        val network = Caves.network(s)
        log("caves: " + halls.joinToString(", ") { "${it.zone} hall r${it.hallR.toInt()} at (${(it.hallX - s.center.x).toInt()}, ${(it.hallZ - s.center.z).toInt()})" } +
            "; ${Caves.entrances(s).size} entrances, ${network?.links?.size ?: 0} joining tunnels (${network?.links?.joinToString { "${it.link!!.a}-${it.link.b} ${it.length.toInt()} long" }})")
        fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        fun open(x: Int, z: Int, spot: Caves.Spot): Boolean {
            level.getChunk(x shr 4, z shr 4)
            val a = level.getBlockState(BlockPos(x, spot.floor, z))
            val b = level.getBlockState(BlockPos(x, spot.floor + 1, z))
            return !a.isSolid && !b.isSolid
        }
        // walk the caves from each hall: its own entrances must be reachable, and the hall at the other end of each
        // open joining tunnel
        val comp = HashMap<Long, Int>()
        var blocked = 0
        var lava = 0
        for ((ci, h) in halls.withIndex()) {
            val sx = h.hallX.toInt()
            val sz = h.hallZ.toInt()
            if (key(sx, sz) in comp) continue
            val queue = ArrayDeque<Triple<Int, Int, Caves.Spot>>()
            val first = Caves.at(s, sx, sz)
            if (first == null || !open(sx, sz, first)) { find("cave hall floor blocked").add(s, sx, first?.floor ?: 0, sz, h.zone); continue }
            queue += Triple(sx, sz, first); comp[key(sx, sz)] = ci
            while (queue.isNotEmpty() && comp.size < 400_000) {
                val (x, z, spot) = queue.removeFirst()
                if (level.getBlockState(BlockPos(x, spot.floor - 1, z)).`is`(Blocks.LAVA)) lava++
                for (d in Direction.Plane.HORIZONTAL) {
                    val nx = x + d.stepX
                    val nz = z + d.stepZ
                    if (key(nx, nz) in comp) continue
                    val o = Caves.at(s, nx, nz) ?: continue
                    if (kotlin.math.abs(o.floor - spot.floor) > 1) continue
                    comp[key(nx, nz)] = ci
                    if (!open(nx, nz, o)) {
                        if (!Dens.covers(s, nx, nz) && !Landmarks.near(s, nx, nz)) { blocked++; if (blocked <= 3) find("cave passage blocked").add(s, nx, o.floor, nz, o.zone) }
                        continue
                    }
                    queue += Triple(nx, nz, o)
                }
            }
        }
        val reached = comp.keys
        log("caves: walked over ${comp.size} columns, ${comp.values.toSet().size} separate parts, $lava lava pools, $blocked blocked columns")
        fun compOf(x: Int, z: Int) = (-3..3).firstNotNullOfOrNull { dx -> (-3..3).firstNotNullOfOrNull { dz -> comp[key(x + dx, z + dz)] } }
        for ((zone, ex, ez) in Caves.entrances(s)) {
            val h = halls.indexOfFirst { it.zone == zone }
            val c = compOf(ex.toInt(), ez.toInt())
            if (c == null || c != comp[key(halls[h].hallX.toInt(), halls[h].hallZ.toInt())]) find("cave entrance can't be reached from its hall").add(s, ex.toInt(), 0, ez.toInt(), zone)
        }
        for (t in network?.links ?: emptyList()) {
            val l = t.link!!
            val a = halls.firstOrNull { it.zone == l.a } ?: continue
            val b = halls.firstOrNull { it.zone == l.b } ?: continue
            val ca = comp[key(a.hallX.toInt(), a.hallZ.toInt())]
            val cb = comp[key(b.hallX.toInt(), b.hallZ.toInt())]
            if (ca == null || ca != cb) find("joining tunnel doesn't get through").add(s, a.hallX.toInt(), 0, a.hallZ.toInt(), "${l.a}-${l.b}")
        }
        // lava running loose, or water inside the caves
        for (k in reached) {
            val x = (k shr 32).toInt(); val z = k.toInt()
            val spot = Caves.at(s, x, z) ?: continue
            for (y in spot.floor - 2..spot.ceil + 1) {
                val fs = level.getFluidState(BlockPos(x, y, z))
                if (fs.isEmpty) continue
                if (fs.`is`(net.minecraft.tags.FluidTags.LAVA) && !fs.isSource) { find("lava flowing").add(s, x, y, z); break }
                if (fs.`is`(net.minecraft.tags.FluidTags.WATER)) { find("water in the cave").add(s, x, y, z, spot.zone); break }
            }
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
