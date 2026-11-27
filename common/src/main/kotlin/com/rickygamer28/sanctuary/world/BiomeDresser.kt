package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.registry.ModContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.features.MiscOverworldFeatures
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shapes and dresses one column of a sanctuary valley in the Sanctuary dimension.
 * The valley starts as flat grass; each biome gets its own terrain, ground and decorations,
 * a mountain rim closes it in, and a wall with four gates runs along the edge.
 */
object BiomeDresser {
    private const val FLAGS = Block.UPDATE_CLIENTS
    /**
     * For bulk work deep in the ground (filling a mountain with stone, cutting it away, hollowing a cave): no need
     * for the neighbours to re-check their shape, nothing there can change. Much cheaper per block.
     */
    private const val BULK = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE
    private const val G = SanctuaryDimension.GROUND_Y
    const val WATER_Y = G - 1

    private fun b(block: Block): BlockState = block.defaultBlockState()

    /**
     * Blocks this building pass placed as decorations (a boulder, a mushroom cap, an island…) that reach over into
     * columns not built yet. Those columns must not mistake them for old ground or clutter and cut them off.
     */
    private val placed = HashMap<java.util.UUID, HashSet<Long>>()

    private fun placedFor(s: Sanctuary) = placed.getOrPut(s.id) { HashSet() }

    // ------------------------------------------------------------------ trees planted once the ground around is final

    private class PendingFeature(val key: ResourceKey<ConfiguredFeature<*, *>>, val surface: BlockPos, val protectRadius: Int, val protectHeight: Int, val seed: Long)
    private val pendingFeatures = HashMap<java.util.UUID, ArrayDeque<PendingFeature>>()

    /** Is the ground within 3 blocks of (x, z) at (about) its final height? */
    private fun settled(level: ServerLevel, s: Sanctuary, x: Int, z: Int, r: Int = 3): Boolean {
        for (dx in -r..r) for (dz in -r..r) {
            val nx = x + dx
            val nz = z + dz
            if (!level.hasChunk(nx shr 4, nz shr 4)) return false
            val top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, nx, nz) - 1
            if (abs(top - targetHeight(s, nx, nz, ZoneLayout.HABITATS)) > 1) return false
        }
        return true
    }

    private fun placeFeature(level: ServerLevel, s: Sanctuary, key: ResourceKey<ConfiguredFeature<*, *>>, surface: BlockPos, random: RandomSource, protectRadius: Int, protectHeight: Int) {
        runCatching {
            level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent {
                it.value().place(level, level.chunkSource.generator, random, surface.above())
            }
        }
        // a swamp oak's vines don't hang down over the water (they'd trail in the pond)
        if (key == TreeFeatures.SWAMP_OAK) for (dx in -5..5) for (dz in -5..5) {
            var y = surface.y + 14
            val vines = ArrayList<BlockPos>()
            while (y > surface.y - 6) {
                val p = BlockPos(surface.x + dx, y, surface.z + dz)
                val st = level.getBlockState(p)
                if (st.`is`(Blocks.VINE)) vines += p
                else if (!st.isAir && !st.`is`(net.minecraft.tags.BlockTags.LEAVES)) {
                    if (!st.fluidState.isEmpty) for (v in vines) level.setBlock(v, b(Blocks.AIR), 2)
                    break
                } else if (st.`is`(net.minecraft.tags.BlockTags.LEAVES)) vines.clear()
                y--
            }
        }
        if (protectRadius > 0) {
            for (dx in -protectRadius..protectRadius) for (dz in -protectRadius..protectRadius) {
                if (dx == 0 && dz == 0) continue
                for (dy in 1..protectHeight) {
                    val p = surface.offset(dx, dy, dz)
                    if (!level.getBlockState(p).isAir && level.getBlockState(p).fluidState.isEmpty) protect(s, p)
                }
            }
        }
    }

    /**
     * Plants the trees (and other big features) that were waiting for the ground around them. [all] at the end of
     * a building pass: whatever still can't go is dropped.
     */
    fun plantPending(level: ServerLevel, s: Sanctuary, all: Boolean, max: Int = 40) {
        val queue = pendingFeatures[s.id] ?: return
        var n = queue.size.coerceAtMost(if (all) Int.MAX_VALUE else max)
        while (n-- > 0 && queue.isNotEmpty()) {
            val f = queue.removeFirst()
            val ground = level.getBlockState(f.surface)
            val stillThere = level.isLoaded(f.surface) && isNatural(ground) && ground.fluidState.isEmpty && level.getBlockState(f.surface.above()).isAir &&
                f.surface.y == targetHeight(s, f.surface.x, f.surface.z, ZoneLayout.HABITATS)
            if (!stillThere || Dens.covers(s, f.surface.x, f.surface.z)) continue
            if (settled(level, s, f.surface.x, f.surface.z)) placeFeature(level, s, f.key, f.surface, RandomSource.create(f.seed), f.protectRadius, f.protectHeight)
            else if (!all) queue.addLast(f)
        }
        if (queue.isEmpty()) pendingFeatures.remove(s.id)
    }

    fun forgetPending(id: java.util.UUID) { pendingFeatures.remove(id) }

    /** Can a pool's water sit at [y] in column (x, z) without pouring over into a lower column beside it? */
    fun poolSafe(s: Sanctuary, x: Int, z: Int, y: Int): Boolean =
        Direction.Plane.HORIZONTAL.all { d -> targetHeight(s, x + d.stepX, z + d.stepZ, ZoneLayout.HABITATS) >= y }

    /** Coral, alive or dead (blocks, plants, fans): the lake's decoration, never part of the ground. */
    fun isCoral(st: BlockState): Boolean {
        if (st.`is`(BlockTags.CORAL_BLOCKS) || st.`is`(BlockTags.CORALS) || st.`is`(BlockTags.WALL_CORALS)) return true
        val id = BuiltInRegistries.BLOCK.getKey(st.block)
        return id.namespace == "minecraft" && id.path.startsWith("dead_") && id.path.contains("coral")
    }

    /**
     * Wipes a column clean before it's built again: everything but the bare ground goes (trees, giant mushrooms,
     * plants, water, lava, lamps, rocks set on the ground, anything built), from its top down to well under the
     * valley floor, so nothing of what stood there before can be left over. The mod's own blocks (the Core, nests,
     * beds…) stay, and so does whatever this pass has already put there for a neighbour.
     */
    fun wipeColumn(level: ServerLevel, s: Sanctuary, x: Int, z: Int) {
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
        val bottom = max(level.minBuildHeight + 1, G - 30)
        val mine = placed[s.id]
        var n = 0
        for (y in top downTo bottom) {
            val p = BlockPos(x, y, z)
            val st = level.getBlockState(p)
            if (st.isAir || ValleyBlocks.isMods(st)) continue
            if (mine != null && p.asLong() in mine) continue
            if (isNatural(st) && st.fluidState.isEmpty && !isCoral(st)) continue // the ground: shaped next (coral goes with the water)
            ValleyBlocks.remove(level, p)
            n++
        }
        if (n > 0) Trace.note(x, z, "wiped $n blocks")
    }

    // ------------------------------------------------------------------ building in waves

    /**
     * The valley is built in waves, each following the one before a little way behind:
     * [LAND] shapes the ground (heights, ground blocks, water, caves); [NATURE] then adds trees, bushes, rocks,
     * flowers and Cobblemon's plants, only once the land all around is finished (so nothing is clipped or buried);
     * [EXTRAS] adds the residents' spots (props, berry trees, route lamps) once the nature around is done.
     * Dens and landmarks come last, when the valley is finished. [ALL] does everything at once (meadows, the rim).
     */
    enum class Phase { ALL, LAND, NATURE, EXTRAS }

    @JvmStatic
    var phase = Phase.ALL
    /** Runs [block] in [p] (then back to the wave that was running). */
    inline fun <T> withPhase(p: Phase, block: () -> T): T {
        val was = phase
        phase = p
        try { return block() } finally { phase = was }
    }

    /** Ground is being shaped now. */
    private val shaping get() = phase == Phase.ALL || phase == Phase.LAND
    /** Things are being placed on the ground now. */
    private val decorating get() = phase != Phase.LAND

    fun protect(s: Sanctuary, pos: BlockPos) {
        val set = placedFor(s)
        if (set.size < 3_000_000) set += pos.asLong()
    }

    /** The building pass is over: nothing left to protect. */
    fun endPass(s: Sanctuary) { placed.remove(s.id) }

    // ================================================================== context + helpers

    class Ctx(
        val level: ServerLevel,
        val s: Sanctuary,
        val surface: BlockPos,
        val random: RandomSource,
        val variant: Int,
        val hit: ZoneLayout.Hit,
        val exposure: Int,
        /** The biggest height step to a neighbouring column (0 on flat ground, 3+ on cliffs). */
        val slope: Int = 0,
    ) {
        val above: BlockPos get() = surface.above()
        /** The habitat's fourth look, if it's wearing it: other blocks and trees on the same land. */
        var remix: Remix? = null
        private fun mix(st: BlockState): BlockState = remix?.swap(st) ?: st
        val x get() = surface.x
        val z get() = surface.z
        val y get() = surface.y
        fun chance(p: Double) = random.nextDouble() < p
        fun noise(scale: Double, salt: Int = 0) = Noise.value(s.seed + salt * 7919L, x / scale, z / scale)

        /** Picks from [states] in smooth patches, so the ground never looks speckled. */
        fun patch(scale: Double, salt: Int, vararg states: BlockState): BlockState {
            val n = noise(scale, salt)
            return states[(n * states.size).toInt().coerceIn(0, states.size - 1)]
        }

        /** Things are being placed now (not just ground shaped). */
        val placing get() = decorating
        fun top(state: BlockState) { if (shaping) level.setBlock(surface, mix(state), FLAGS) }
        /** Fills below the top, deep enough to cover any exposed cliff side. */
        fun filler(state: BlockState, minDepth: Int = 3) {
            if (!shaping) return
            val shift = (noise(30.0, 77) * 3).toInt()
            for (i in 1..max(minDepth, exposure)) {
                val p = surface.below(i)
                if (!isNatural(level.getBlockState(p))) continue
                var st = mix(state)
                // soil only a few blocks deep; below it, rock
                if (i > 3 && (st.`is`(Blocks.DIRT) || st.`is`(Blocks.GRASS_BLOCK) || st.`is`(Blocks.MUD) || st.`is`(Blocks.PODZOL) || st.`is`(Blocks.ROOTED_DIRT))) st = b(Blocks.STONE)
                // tall cliffs show layers
                if (exposure > 4 && i > 1) {
                    val accent = STRATA[st.block]
                    if (accent != null && Math.floorMod(p.y + shift, 4) == 0) st = accent
                }
                level.setBlock(p, st, BULK)
            }
        }
        fun ground(top: BlockState, filler: BlockState = top) { top(top); filler(filler) }
        /** Height above the valley floor. */
        val rise get() = y - G
        /** Steep enough that only bare rock would hold. */
        val cliff get() = slope >= 3
        /**
         * Natural ground: [soil] on level ground, bare [rock] on cliffs, and on the rough bits in between, soil with
         * a few broad patches of rock showing through. [rockAlt] streaks the rock now and then.
         */
        fun natural(soil: BlockState, under: BlockState, rock: BlockState, rockAlt: BlockState = rock) {
            fun stone() = if (noise(13.0, 81) > 0.66) rockAlt else rock
            when {
                cliff -> ground(stone(), rock)
                slope == 2 && noise(9.0, 82) > 0.55 -> ground(stone(), rock)
                else -> ground(soil, under)
            }
        }
        /** Broad, soft patches: [rare] where a large-scale noise is above [above], [common] elsewhere. */
        fun broad(common: BlockState, rare: BlockState, above: Double, scale: Double = 24.0, salt: Int = 83): BlockState =
            if (noise(scale, salt) > above) rare else common
        /** Trees grow in clumps with clearings between them: [dense] cells where the woods are, [sparse] elsewhere. */
        fun tree(dense: Int, sparse: Int, salt: Int, woods: Double = 0.45): Boolean =
            if (noise(34.0, salt + 500) > woods) grid(dense, salt) else grid(sparse, salt + 1)
        /** Layered ground, e.g. the coloured bands of a mesa. */
        fun bands(bands: List<BlockState>, topOverride: BlockState? = null) {
            if (!shaping) return
            top(topOverride ?: bands[Math.floorMod(y, bands.size)])
            for (i in 1..max(3, exposure)) {
                val p = surface.below(i)
                if (isNatural(level.getBlockState(p))) level.setBlock(p, mix(bands[Math.floorMod(y - i, bands.size)]), FLAGS)
            }
        }
        fun at(pos: BlockPos, original: BlockState) {
            if (!decorating) return
            val state = mix(original)
            if (state.isAir) return
            val cur = level.getBlockState(pos)
            if (cur.isAir || (cur.canBeReplaced() && cur.fluidState.isEmpty)) {
                level.setBlock(pos, state, FLAGS)
                if (pos.x != x || pos.z != z) protect(s, pos)
            }
        }
        fun place(state: BlockState, offset: Int = 1) = at(surface.above(offset), state)
        fun column(state: BlockState, height: Int) { for (i in 1..height) place(state, i) }
        fun pick(vararg states: BlockState): BlockState = states[random.nextInt(states.size)]
        /**
         * Places a vanilla feature. For features that aren't trees (giant mushrooms, ice spikes, mossy rocks),
         * [protectRadius] marks what it built around the spot so neighbouring columns don't cut it off.
         */
        fun feature(original: ResourceKey<ConfiguredFeature<*, *>>, protectRadius: Int = 0, protectHeight: Int = 16) {
            val key = remix?.tree(original) ?: original
            // the ground around isn't at its final height yet (old higher ground would clip the tree, lower ground
            // would be filled up through it): plant it later, once it is
            if (!decorating || Dens.covers(s, x, z)) return
            if (!settled(level, s, x, z)) {
                pendingFeatures.getOrPut(s.id) { ArrayDeque() } += PendingFeature(key, surface, protectRadius, protectHeight, random.nextLong())
                return
            }
            placeFeature(level, s, key, surface, random, protectRadius, protectHeight)
        }
        fun placeNow(original: ResourceKey<ConfiguredFeature<*, *>>, protectRadius: Int = 0, protectHeight: Int = 16) {
            val key = remix?.tree(original) ?: original
            runCatching {
                level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent {
                    it.value().place(level, level.chunkSource.generator, random, above)
                }
            }
            if (protectRadius > 0) {
                for (dx in -protectRadius..protectRadius) for (dz in -protectRadius..protectRadius) {
                    if (dx == 0 && dz == 0) continue
                    for (dy in 1..protectHeight) {
                        val p = surface.offset(dx, dy, dz)
                        if (!level.getBlockState(p).isAir && level.getBlockState(p).fluidState.isEmpty) protect(s, p)
                    }
                }
            }
        }
        /** Keeps big decorations away from the wall and from biome borders. */
        fun roomy(margin: Int = 5) = hit.dist < s.size.radius - margin && (hit.zone == ZoneLayout.HUB || hit.edgeDistance > 3.0)
        /** True on one spot per [cell]×[cell] square, so big decorations are spread out and never overlap. */
        fun grid(cell: Int, salt: Int = 0): Boolean {
            val cx = Math.floorDiv(x, cell)
            val cz = Math.floorDiv(z, cell)
            val px = (Noise.hash(s.seed + salt, cx, cz) * cell).toInt()
            val pz = (Noise.hash(s.seed + salt + 1, cx, cz) * cell).toInt()
            return Math.floorMod(x, cell) == px && Math.floorMod(z, cell) == pz
        }

        // ---- little structures
        /** The top of the ground at (x, z) (ignoring plants and leaves), as it is now. */
        fun groundAt(px: Int, pz: Int): Int = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1

        /**
         * A rounded rock. Each part of it rests on the ground under it, so on a slope it sits into the hill
         * instead of hanging over the low side.
         */
        fun boulder(main: BlockState, accent: BlockState, size: Int = 1) {
            for (dx in -size..size) for (dz in -size..size) {
                val rr = size * size + 1 - dx * dx - dz * dz
                if (rr < 0) continue
                val top = y + 1 + floor(sqrt(rr.toDouble())).toInt()
                val base = if (dx == 0 && dz == 0) y else groundAt(x + dx, z + dz)
                // lower ground beside it (more than a step down): leave that side off rather than hang it in the air
                if (base < y - 1) continue
                for (yy in base + 1..top) at(BlockPos(x + dx, yy, z + dz), if (random.nextInt(4) == 0) accent else main)
            }
        }

        /**
         * A spike of [main] (ice spikes, rock spires): [height] tall with a base [radius] wide, built up from the
         * ground under each part of it, so nothing of it hangs off a slope.
         */
        fun spike(main: BlockState, radius: Int, height: Int, tip: BlockState = main) {
            if (!decorating) return
            for (dx in -radius..radius) for (dz in -radius..radius) {
                val d = sqrt((dx * dx + dz * dz).toDouble())
                if (d > radius + 0.4) continue
                val t = 1.0 - d / (radius + 0.6)
                val top = y + max(1, (height * Math.pow(t, 1.7)).toInt())
                val base = if (dx == 0 && dz == 0) y else groundAt(x + dx, z + dz)
                if (base >= top) continue
                for (yy in base + 1..top) at(BlockPos(x + dx, yy, z + dz), if (yy == top && dx == 0 && dz == 0) tip else main)
            }
        }
        /** A cluster of crystals or columns: a tall centre with shorter ones around it. */
        fun crystalCluster(state: BlockState, tip: BlockState?, height: Int) {
            column(state, height)
            tip?.let { place(it, height + 1) }
            for (d in Direction.Plane.HORIZONTAL) {
                if (random.nextInt(3) == 0) continue
                val h = max(1, height - 1 - random.nextInt(height))
                // from the ground beside it (lower on a slope: built up from there, much lower: left out)
                val side = surface.relative(d)
                val g = groundAt(side.x, side.z)
                if (g < y - 1 || g > y + h) continue
                for (yy in g + 1..y + h) at(BlockPos(side.x, yy, side.z), state)
                if (tip != null && random.nextBoolean()) at(BlockPos(side.x, y + h + 1, side.z), tip)
            }
        }
        fun lampPost(post: BlockState, lantern: BlockState, height: Int = 2) {
            column(post, height)
            place(lantern, height + 1)
        }
        fun gravestone() {
            place(pick(b(Blocks.COBBLESTONE_WALL), b(Blocks.MOSSY_COBBLESTONE_WALL), b(Blocks.STONE_BRICK_WALL), b(Blocks.MOSSY_STONE_BRICK_WALL)))
            if (chance(0.4)) place(b(Blocks.STONE_BUTTON), 2)
            if (chance(0.3)) at(above.south(), if (chance(0.5)) b(Blocks.WITHER_ROSE) else b(Blocks.POPPY))
        }
        fun fallenLog(log: BlockState) {
            val axis = if (random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z
            val len = 3 + random.nextInt(3)
            val state = log.setValue(RotatedPillarBlock.AXIS, axis)
            for (i in 0 until len) {
                val p = if (axis == Direction.Axis.X) above.east(i) else above.south(i)
                at(p, state)
                if (random.nextInt(3) == 0) at(p.above(), b(Blocks.MOSS_CARPET))
            }
        }
        /** A bare, gnarled tree: a trunk with crooked wooden branches (logs, so they read as a tree and stay put). */
        fun deadTree(log: BlockState, @Suppress("UNUSED_PARAMETER") branch: BlockState) {
            val h = 4 + random.nextInt(3)
            column(log, h)
            val dirs = Direction.Plane.HORIZONTAL.toList().shuffled(java.util.Random(random.nextLong())).take(2 + random.nextInt(2))
            for ((k, d) in dirs.withIndex()) {
                val yy = h - 1 - k % 2
                val axis = d.axis
                val side = log.trySetValue(RotatedPillarBlock.AXIS, axis)
                at(surface.above(yy).relative(d), side)
                // the branch bends upwards at its end
                if (random.nextBoolean()) at(surface.above(yy + 1).relative(d), log) else at(surface.above(yy).relative(d, 2), side)
            }
        }
        fun ruinWall(state: BlockState, length: Int, height: Int) {
            val dir = if (random.nextBoolean()) Direction.EAST else Direction.SOUTH
            for (i in 0 until length) {
                val h = (height - random.nextInt(height)).coerceAtLeast(1)
                for (yy in 1..h) at(surface.relative(dir, i).above(yy), if (random.nextInt(4) == 0) b(Blocks.CRACKED_STONE_BRICKS) else state)
            }
        }
        fun floatingIsland(main: BlockState, top: BlockState, radius: Int, height: Int) {
            for (dx in -radius..radius) for (dz in -radius..radius) {
                val d = sqrt((dx * dx + dz * dz).toDouble())
                if (d > radius + 0.3) continue
                val depth = ((radius - d) * 1.2).toInt() + 1
                for (dy in 0 until depth) at(surface.offset(dx, height - dy, dz), if (dy == 0) top else main)
            }
        }
        /** A Cobblemon berry tree (or a sweet berry bush without Cobblemon's), where residents meet for meals. */
        fun berryTree() {
            if (!decorating) return
            if (!level.getBlockState(above).isAir || !level.getBlockState(surface).`is`(BlockTags.DIRT)) return
            val ids = listOf("oran_berry", "pecha_berry", "cheri_berry", "rawst_berry", "sitrus_berry", "leppa_berry", "aspear_berry", "persim_berry")
            val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("cobblemon", ids[random.nextInt(ids.size)]))
            var state = if (block == Blocks.AIR) b(Blocks.SWEET_BERRY_BUSH) else block.defaultBlockState()
            val age = state.block.stateDefinition.getProperty("age") as? net.minecraft.world.level.block.state.properties.IntegerProperty
            if (age != null) state = state.setValue(age, age.possibleValues.max())
            level.setBlock(above, state, Block.UPDATE_ALL)
            if (s.berrySpots.size < 600) s.berrySpots += above.asLong()
        }
        fun tallPlant(state: BlockState) {
            if (!decorating) return
            val lower = above
            val upper = lower.above()
            if (level.getBlockState(lower).isAir && level.getBlockState(upper).isAir) {
                level.setBlock(lower, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), FLAGS)
                level.setBlock(upper, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), FLAGS)
            }
        }
        /**
         * A shallow pool flush with the ground: water on top, [bed] under it. Only where no ground beside it is lower
         * (on a slope, water would pour over the edge into the next column down, over and over): there the bed shows.
         */
        fun pool(bed: BlockState) {
            if (!shaping) return
            if (!poolSafe(s, x, z, y)) { level.setBlock(surface, bed, FLAGS); return }
            level.setBlock(surface, b(Blocks.WATER), FLAGS)
            level.setBlock(surface.below(), bed, FLAGS)
            for (i in 2..3) { val p = surface.below(i); if (isNatural(level.getBlockState(p))) level.setBlock(p, bed, FLAGS) }
        }
        /** Fills the column with water from above the ground up to the water line. */
        fun flood() { if (shaping) for (yy in y + 1..WATER_Y) level.setBlock(BlockPos(x, yy, z), b(Blocks.WATER), FLAGS) }
        fun waterTop(state: BlockState) = at(BlockPos(x, WATER_Y + 1, z), state)
        /** Places a block on the floor of a lake (into the water). */
        fun underwater(state: BlockState) { if (decorating && level.getBlockState(above).`is`(Blocks.WATER)) level.setBlock(above, state, FLAGS) }
    }

    // ================================================================== ground / clutter

    private val GROUND: Set<Block> = setOf(
        Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE,
        Blocks.PACKED_ICE, Blocks.ICE, Blocks.BLUE_ICE, Blocks.CALCITE, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SMOOTH_BASALT,
        Blocks.SOUL_SOIL, Blocks.SOUL_SAND, Blocks.AMETHYST_BLOCK, Blocks.PACKED_MUD, Blocks.MUD, Blocks.POLISHED_ANDESITE,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.DIRT_PATH, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
        Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_TILES,
        Blocks.MOSS_BLOCK, Blocks.PURPUR_BLOCK, Blocks.QUARTZ_BLOCK, Blocks.BONE_BLOCK, Blocks.MAGMA_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
        Blocks.ROOTED_DIRT, Blocks.COARSE_DIRT, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.WARPED_NYLIUM, Blocks.DRIPSTONE_BLOCK,
        Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER, Blocks.EXPOSED_COPPER,
        // (the custom types' habitats)
        Blocks.END_STONE, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.SLIME_BLOCK, Blocks.SEA_LANTERN,
        Blocks.CRIMSON_NYLIUM, Blocks.NETHERRACK, Blocks.SCULK,
        // route and landmark floors: they must count as ground too, or a rebuild leaves them (and a step) behind
        Blocks.MUD_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_DEEPSLATE,
        Blocks.DEEPSLATE_BRICKS, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.POLISHED_DIORITE,
        Blocks.BRAIN_CORAL_BLOCK, Blocks.TUBE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK,
    ) + Props.GROUND_BLOCKS + Remix.GROUND

    /**
     * Small decorations the sanctuary places (lamp posts, gravestones, dummies, rods, chimneys…). When the valley is
     * rebuilt or grows, the layout shifts, so old ones are cleared instead of being left standing next to new ones.
     */
    private val DECOR: Set<Block> = setOf(
        Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE, Blocks.DARK_OAK_FENCE, Blocks.LANTERN, Blocks.SOUL_LANTERN,
        Blocks.COBBLESTONE_WALL, Blocks.MOSSY_COBBLESTONE_WALL, Blocks.STONE_BRICK_WALL, Blocks.MOSSY_STONE_BRICK_WALL,
        Blocks.COBBLED_DEEPSLATE_WALL, Blocks.STONE_BUTTON, Blocks.END_ROD, Blocks.LIGHTNING_ROD, Blocks.IRON_BARS,
        Blocks.HAY_BLOCK, Blocks.CARVED_PUMPKIN, Blocks.JACK_O_LANTERN, Blocks.TARGET, Blocks.REDSTONE_LAMP,
        Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.BRICKS, Blocks.PURPUR_PILLAR, Blocks.WITHER_ROSE,
    ) + Props.DECOR_BLOCKS

    /**
     * Solid blocks the sanctuary's own decorations are made of (ruins, gravestones, posts, toadstools, giant
     * mushrooms, cave lights, dripstone…). When the valley is rebuilt they're taken away like plants, unless they
     * touch something that looks like a player's build.
     */
    private val MOD_SOLID: Set<Block> = setOf(
        Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB,
        Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.BONE_BLOCK,
        Blocks.MUSHROOM_STEM, Blocks.RED_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.SHROOMLIGHT, Blocks.OCHRE_FROGLIGHT,
        Blocks.AMETHYST_CLUSTER, Blocks.POINTED_DRIPSTONE, Blocks.BEE_NEST, Blocks.MELON, Blocks.CACTUS, Blocks.SUGAR_CANE, Blocks.BAMBOO,
        Blocks.COBWEB, Blocks.SPRUCE_FENCE_GATE, Blocks.STRIPPED_SPRUCE_LOG, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW,
        // the caves' lights and crystals
        Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT, Blocks.LARGE_AMETHYST_BUD, Blocks.GLOW_LICHEN,
    )

    fun isModSolid(st: BlockState) = st.block in MOD_SOLID

    /** Is this block touching something that looks like a player's build (planks, glass, a door, a chest…)? */
    /** Next to one of the mod's own blocks a player placed (a bed, a ball, a nest…)? Players build nothing else here. */
    private fun nextToBuild(level: ServerLevel, p: BlockPos): Boolean = Direction.entries.any { d ->
        ValleyBlocks.isMods(level.getBlockState(p.relative(d)))
    }

    // ================================================================== Cobblemon's own plants and crystals

    /** What each biome grows from Cobblemon: apricorn trees, mints and herbs, flowers, gem clusters, tumblestone… */
    private class CobDecor(
        val apricorn: String? = null,
        val patches: List<String> = emptyList(),
        val flowers: List<String> = emptyList(),
        val gem: String? = null,
        val rocks: List<String> = emptyList(),
        val chest: Boolean = false,
    )

    private val COBBLEMON_DECOR: Map<String, CobDecor> = mapOf(
        ZoneLayout.HUB to CobDecor("yellow", listOf("pink_mint", "white_mint", "revival_herb"), listOf("pep_up_flower", "lucibell"), "normal"),
        "grass" to CobDecor("green", listOf("green_mint", "revival_herb", "hearty_grains/grains_patch"), listOf("verdroot", "pep_up_flower"), "grass"),
        "bug" to CobDecor("green", listOf("hearty_grains/grains_patch", "green_mint"), listOf("bugwort", "verdroot"), "bug"),
        "fairy" to CobDecor("pink", listOf("pink_mint", "white_mint"), listOf("lucibell", "pomohana", "pauvillea"), "fairy"),
        "water" to CobDecor("blue", listOf("medicinal_leek", "blue_mint", "cyan_mint"), listOf("lobaila"), "water"),
        "fire" to CobDecor("red", listOf("red_mint"), emptyList(), "fire"),
        "ice" to CobDecor("white", listOf("white_mint"), emptyList(), "ice"),
        "electric" to CobDecor("yellow", emptyList(), listOf("voltaconia"), "electric"),
        "psychic" to CobDecor(null, listOf("pink_mint"), listOf("wistensu", "mistleaf"), "psychic"),
        "poison" to CobDecor(null, emptyList(), listOf("mistleaf"), "poison"),
        "dark" to CobDecor("black", emptyList(), emptyList(), "dark"),
        "ghost" to CobDecor(null, emptyList(), emptyList(), "ghost", chest = true),
        "rock" to CobDecor(null, emptyList(), emptyList(), "rock", listOf("tumblestone_cluster")),
        "ground" to CobDecor(null, emptyList(), emptyList(), "ground", listOf("tumblestone_cluster")),
        "steel" to CobDecor(null, emptyList(), emptyList(), "steel", listOf("sky_tumblestone_cluster")),
        "dragon" to CobDecor(null, emptyList(), emptyList(), "dragon", listOf("black_tumblestone_cluster")),
        "fighting" to CobDecor("red", listOf("galarica_nuts/galarica_bushes"), emptyList(), "fighting"),
    )

    /** A Cobblemon block by name, fully grown if it grows; null when this Cobblemon version doesn't have it. */
    private fun cobBlock(id: String): BlockState? {
        val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("cobblemon", id))
        if (block == Blocks.AIR) return null
        var state = block.defaultBlockState()
        val age = block.stateDefinition.getProperty("age") as? net.minecraft.world.level.block.state.properties.IntegerProperty
        if (age != null) state = state.setValue(age, age.possibleValues.max())
        return pointUp(state)
    }

    /** Crystal clusters and the like stand upright on the ground. */
    fun pointUp(state: BlockState): BlockState {
        val facing = state.block.stateDefinition.getProperty("facing") as? net.minecraft.world.level.block.state.properties.DirectionProperty
        return if (facing != null && Direction.UP in facing.possibleValues) state.setValue(facing, Direction.UP) else state
    }

    private fun cobFeature(id: String): ResourceKey<ConfiguredFeature<*, *>> =
        ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("cobblemon", id))

    private fun cobblemonDecor(c: Ctx, zone: String, target: Int) {
        val d = COBBLEMON_DECOR[zone] ?: return
        if (target < WATER_Y + 1 || !c.level.getBlockState(c.above).isAir) return
        val salt = 600 + Math.floorMod(zone.hashCode(), 97)
        val ground = c.level.getBlockState(c.surface)
        val soil = ground.`is`(BlockTags.DIRT)
        val solid = ground.isFaceSturdy(c.level, c.surface, Direction.UP)
        when {
            d.apricorn != null && soil && c.grid(36, salt) && c.roomy(6) -> c.feature(cobFeature("${d.apricorn}_apricorn_tree"))
            d.gem != null && solid && c.grid(25, salt + 1) && c.roomy() -> cobBlock("${d.gem}_gem_cluster")?.let { c.place(it) }
            d.rocks.isNotEmpty() && solid && c.grid(17, salt + 2) && c.roomy() -> cobBlock(d.rocks[c.random.nextInt(d.rocks.size)])?.let { c.place(it) }
            d.patches.isNotEmpty() && soil && c.grid(31, salt + 3) && c.roomy() -> c.feature(cobFeature(d.patches[c.random.nextInt(d.patches.size)]), 4, 3)
            d.flowers.isNotEmpty() && soil && c.chance(0.012) -> cobBlock(d.flowers[c.random.nextInt(d.flowers.size)])?.let {
                if (it.hasProperty(DoublePlantBlock.HALF)) c.tallPlant(it) else c.place(it)
            }
            d.chest && solid && c.grid(73, salt + 4) && c.roomy(8) -> cobBlock("gimmighoul_chest")?.let { c.place(it) }
        }
    }

    private val BERRY_ZONES = listOf("grass", "bug", "fairy", ZoneLayout.HUB, "water", "fighting")

    /** The layer that shows in tall cliffs of each rock. */
    private val STRATA: Map<Block, BlockState> = mapOf(
        Blocks.STONE to b(Blocks.ANDESITE), Blocks.SANDSTONE to b(Blocks.SMOOTH_SANDSTONE), Blocks.RED_SANDSTONE to b(Blocks.TERRACOTTA),
        Blocks.BLACKSTONE to b(Blocks.BASALT), Blocks.TUFF to b(Blocks.CALCITE), Blocks.DEEPSLATE to b(Blocks.COBBLED_DEEPSLATE),
        Blocks.PACKED_ICE to b(Blocks.BLUE_ICE), Blocks.PACKED_MUD to b(Blocks.MUD), Blocks.CALCITE to b(Blocks.SMOOTH_BASALT),
        Blocks.SOUL_SOIL to b(Blocks.SOUL_SAND), Blocks.SMOOTH_BASALT to b(Blocks.TUFF),
    )

    fun isNatural(state: BlockState): Boolean =
        state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
            state.`is`(BlockTags.TERRACOTTA) || state.block in GROUND || state.`is`(Blocks.WATER)

    private val CLUTTER: Set<Block> = setOf(
        Blocks.SNOW, Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.COCOA, Blocks.BEE_NEST, Blocks.SWEET_BERRY_BUSH, Blocks.PUMPKIN, Blocks.MELON,
        Blocks.BAMBOO, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.MOSS_CARPET, Blocks.PINK_PETALS,
        Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
        Blocks.POINTED_DRIPSTONE, Blocks.LILY_PAD, Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MANGROVE_PROPAGULE,
        Blocks.DEAD_BUSH, Blocks.HANGING_ROOTS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.SEA_PICKLE,
        Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.WARPED_ROOTS, Blocks.COBWEB,
    )

    /** Cobblemon's own plants, flowers and crystal clusters (not player blocks like PCs or healers). */
    private val COB_PLANTS = setOf(
        "pep_up_flower", "lucibell", "verdroot", "bugwort", "pomohana", "pauvillea", "lobaila", "voltaconia", "wistensu", "mistleaf",
        "revival_herb", "medicinal_leek", "hearty_grains", "galarica_nut_bush", "vivichoke", "big_root", "energy_root", "gimmighoul_chest",
    )

    fun isCobblemonDecor(st: BlockState): Boolean {
        val id = BuiltInRegistries.BLOCK.getKey(st.block)
        if (id.namespace != "cobblemon") return false
        val p = id.path
        // (apricorns are the fruit hanging off apricorn trees: they go with the tree)
        return p.endsWith("_cluster") || p.endsWith("_bud") || p.endsWith("_mint") || p.endsWith("_berry") || p.endsWith("_apricorn") ||
            p.endsWith("_apricorn_sapling") || p in COB_PLANTS
    }

    fun isClutter(st: BlockState): Boolean =
        isCobblemonDecor(st) || st.block in CLUTTER || st.block in DECOR || st.`is`(BlockTags.FLOWERS) || st.`is`(BlockTags.SAPLINGS) || st.`is`(BlockTags.CORALS) ||
            (st.fluidState.isEmpty && st.canBeReplaced() && !st.isAir)

    private fun naturalLeaf(st: BlockState) = st.`is`(BlockTags.LEAVES) && !st.getOptionalValue(LeavesBlock.PERSISTENT).orElse(true)

    // ================================================================== terrain shapes

    private fun smooth(t: Double): Double { val c = t.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
    fun smoothstep(t: Double): Double = smooth(t)
    private fun terrace(v: Double, step: Int) = floor(v / step) * step

    /** Terraces with sloped edges (not sheer walls): flat steps joined by short, steep slopes. */
    private fun softTerrace(v: Double, step: Int, steep: Double = 2.6): Double {
        val base = floor(v / step) * step
        val t = (v - base) / step
        return base + step * smooth((t - 0.5) * steep + 0.5)
    }

    /** Fractal noise in [-1, 1] at a scale in blocks. */
    private fun f(s: Sanctuary, x: Double, z: Double, scale: Double, salt: Int, oct: Int = 3) = Noise.fbm(s.seed + salt * 7919L, x / scale, z / scale, oct)
    private fun f01(s: Sanctuary, x: Double, z: Double, scale: Double, salt: Int, oct: Int = 3) = f(s, x, z, scale, salt, oct) * 0.5 + 0.5
    private fun ridge(s: Sanctuary, x: Double, z: Double, scale: Double, salt: Int, oct: Int = 3) = Noise.ridged(s.seed + salt * 7919L, x / scale, z / scale, oct)

    private fun clamp(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)
    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** Height the habitats settle to at their edges, close to the meadows' own height, so borders stay soft. */
    private const val EDGE_LEVEL = 1.0
    private val MOUNTAINS = setOf("ice", "rock", "dragon", "fire")

    /**
     * Height of a zone above the flat ground at (x, z), before blending. Each habitat is one natural landform shaped
     * around its own middle: a lake in a basin, a snowy peak, a volcano, a mountain range, dunes, canyon highlands, a
     * hill with the haunted tower, a swamp full of pools, wooded hills, a garden with ponds, open plains.
     * All of them settle down towards their edges, so neighbours flow into each other.
     */
    private fun rawOffset(s: Sanctuary, zone: String, variant: Int, x: Int, z: Int, @Suppress("UNUSED_PARAMETER") ring: List<String>): Double {
        val xd = x.toDouble()
        val zd = z.toDouble()
        val wx = x + f(s, xd, zd, 48.0, 901, 2) * 14
        val wz = z + f(s, xd, zd, 48.0, 902, 2) * 14
        if (zone == ZoneLayout.HUB) {
            // the town is nearly flat; the meadows out between the habitats roll gently
            val d = sqrt(((x - s.center.x) * (x - s.center.x) + (z - s.center.z) * (z - s.center.z)).toDouble())
            val wild = smooth((d - s.size.hubRadius - 4) / 30.0)
            return f(s, xd, zd, 60.0, 1, 2) * 1.6 + wild * (f(s, wx, wz, 70.0, 2, 3) * 3.2 + 1.2)
        }
        val l = ZoneLayout.local(s, zone, x, z) ?: return 0.0
        val q = l.q
        val rB = l.dist
        val big = l.minR
        val size = l.size
        val a = l.angle
        val e = smooth((1.12 - q) / 0.45)
        // a clearing in the middle of some habitats, for their landmark, with a natural, uneven edge
        fun clearing(radius: Double) = smooth((rB * (1 + f(s, xd, zd, 20.0, 77, 2) * 0.45) - radius) / 26.0)
        val h: Double = when (zone) {
            "water" -> if (variant == 2) {
                // a rocky coast: a wide, deep bay with beaches at its foot, cliffs rising behind them, and rock stacks
                // standing out in the water
                val qn = q + f(s, wx, wz, 40.0, 5, 2) * 0.18
                val m = smooth((0.66 - qn) / 0.12)
                val deep = clamp(big * 0.14, 9.0, 20.0) * smooth((0.55 - qn) / 0.35)
                val cliffs = smooth((qn - 0.74) / 0.06) * (4.0 + f01(s, wx, wz, 30.0, 12, 2) * 5.0)
                val land = 1.0 + cliffs + smooth((qn - 0.85) / 0.3) * 2.0 + f(s, wx, wz, 40.0, 9, 2) * 1.2
                val base = land * (1 - m) + (-2.0 - deep) * m
                val stack = smooth((f01(s, xd, zd, 9.0, 13, 2) - 0.8) / 0.04) * smooth((0.6 - qn) / 0.12) * smooth((qn - 0.2) / 0.1) * smooth((rB - 12) / 6.0)
                mix(base, 3.0 + f01(s, xd, zd, 5.0, 14, 2) * 5.0, stack)
            } else {
                // a lake in a shallow basin, with beaches, grassy banks rising behind them, and a few islands
                val qn = q + f(s, wx, wz, 36.0, 5, 2) * 0.2
                val m = smooth((0.62 - qn) / 0.16)
                // deep enough in the middle for the biggest swimmers (Wailord!), shelving gently from the beaches
                val deep = (if (variant == 1) clamp(big * 0.1, 7.0, 14.0) else clamp(big * 0.14, 9.0, 20.0)) * smooth((0.5 - qn) / 0.35)
                val land = 1.0 + smooth((qn - 0.62) / 0.4) * 2.0 + f(s, wx, wz, 54.0, 9, 2) * 1.6
                val base = land * (1 - m) + (-2.0 - deep) * m
                val island = smooth((f01(s, wx, wz, 26.0, 6, 2) - (if (variant == 1) 0.62 else 0.67)) / 0.1) *
                    smooth((0.48 - qn) / 0.15) * smooth((rB - 12) / 6.0)
                mix(base, 1.5 + f01(s, xd, zd, 8.0, 66, 2), island)
            }
            "ice" -> {
                // one big snowy mountain at the back, with spurs running down from its summit, and snowy foothills in front
                val (px, pz) = peakOf(s, zone)!!
                val rB = sqrt((xd - px) * (xd - px) + (zd - pz) * (zd - pz))
                val a = kotlin.math.atan2(zd - pz, xd - px)
                val cone = min(big, 90.0)
                val top = clamp(cone * 0.85, 22.0, 70.0) * (if (variant == 2) 0.75 else 1.0)
                val rr = rB * (1 + f(s, xd, zd, 26.0, 58, 2) * 0.12)
                val t = clamp((rr - 8) / (cone * 1.2 - 8), 0.0, 1.0)
                val p = 0.55 * Math.pow(1 - t, 2.2) + 0.45 * smooth(1 - t)
                val aw = a + f(s, xd, zd, 30.0, 57, 2) * 0.6
                val spur = Noise.fbm(s.seed + 59 * 7919L, cos(aw) * 1.8 + rr / 45, sin(aw) * 1.8, 3)
                var hh = top * p * (1 + spur * 0.5 * smooth(t / 0.25)) + f(s, wx, wz, 18.0, 60, 3) * 2.5 * smooth(t / 0.3) + 1 +
                    (f01(s, wx, wz, 50.0, 63, 3) * 9) * smooth((t - 0.6) / 0.4) // foothills
                if (variant == 2) { // frozen tarns at its foot
                    val pond = smooth((f01(s, wx, wz, 22.0, 61, 2) - 0.64) / 0.08) * smooth((t - 0.6) / 0.12)
                    hh = mix(hh, -3.0, pond)
                }
                hh
            }
            "fire" -> {
                // a volcano at the back of the habitat: concave slopes cut by gullies, up to a crater rim
                val (px, pz) = peakOf(s, zone)!!
                val rB = sqrt((xd - px) * (xd - px) + (zd - pz) * (zd - pz))
                val a = kotlin.math.atan2(zd - pz, xd - px)
                val cone = min(big, 80.0)
                val top = clamp(cone * 0.9, 24.0, 64.0)
                val rr = rB * (1 + f(s, xd, zd, 18.0, 47, 2) * 0.08)
                val crater = clamp(cone * 0.14, 5.0, 10.0)
                if (rr < crater) max(top - 9, top - 1.5 - (crater - rr) * 1.6)
                else {
                    val t = clamp((rr - crater) / (cone * 1.25 - crater), 0.0, 1.0)
                    val p = 0.6 * Math.pow(1 - t, 2.4) + 0.4 * smooth(1 - t)
                    val gully = Noise.ridged(s.seed + 48 * 7919L, cos(a) * 4.5 + rr / 70, sin(a) * 4.5, 2)
                    top * p + (gully * gully - 0.4) * 3.0 * sin(Math.PI * t) + f(s, xd, zd, 14.0, 49, 2) * smooth(t / 0.2) + 1 +
                        f01(s, wx, wz, 36.0, 46, 3) * 6 * smooth((t - 0.7) / 0.3) // rough old lava fields around it
                }
            }
            "rock" -> {
                // a range of rugged mountains around a sheltered valley (where the arch stands), stony hills elsewhere
                val top = clamp(size * 0.5, 18.0, 56.0) * (if (variant == 2) 0.7 else 1.0)
                val massif = smooth((f01(s, wx, wz, size * 0.8, 35, 2) - 0.3) / 0.36)
                val m = Noise.ridged(s.seed + 36 * 7919L, wx / (size * 0.5), wz / (size * 0.5), 3)
                val tors = smooth((f01(s, wx, wz, 14.0, 37, 2) - 0.8) / 0.08) * 3 * (1 - massif)
                // the range rises behind the valley at the front
                val back = smooth((backness(s, zone, x, z) + 0.15) / 0.55)
                val hills = 2 + f(s, wx, wz, 40.0, 34, 3) * 2 + smooth((rB - 10) / (size * 0.35)) * smooth((1.0 - q) / 0.4) * massif * back * (top * m * m + 4) + tors
                // always enough mountain over the caves, and a level apron in front of each cave mouth
                mix(max(hills, Caves.cover(s, x, z)), Caves.floorOffset(s), Caves.apron(s, x, z))
            }
            "ground" -> {
                val c = clearing(12.0)
                when (variant) {
                    1 -> { // badlands: flat-topped buttes rising from the sand
                        val n = f01(s, wx, wz, 46.0, 44, 2)
                        (smooth((n - 0.58) / 0.06) * 13 + smooth((n - 0.5) / 0.08) * 3) * c + f(s, wx, wz, 60.0, 45, 2) * 1.2 + 1
                    }
                    else -> if (s.variants["ground"] == Remix.INDEX) {
                        // the Dry savanna: gentle rolling grassland of its own (no dunes)
                        (f(s, wx, wz, 80.0, 46, 3) * 2.5 + f(s, wx, wz, 180.0, 47, 2) * 2.0) * c + 2
                    } else { // dunes: soft, winding crests with broad slopes between them
                        val field = smooth((f01(s, xd, zd, 110.0, 44, 2) - 0.3) / 0.3)
                        val wxw = wx + f(s, xd, zd, 55.0, 41, 2) * 20
                        val wzw = wz + f(s, xd, zd, 55.0, 42, 2) * 20
                        val ridge = Noise.ridged(s.seed + 48 * 7919L, wxw / 75.0, wzw / 75.0, 2)
                        val crest = smooth((ridge - 0.4) / 0.6)
                        var hh = crest * (if (variant == 0) 5.0 else 3.0) * field * c + f(s, wx, wz, 120.0, 43, 2) * 1.5 + 1
                        if (variant == 2) { // an oasis
                            val (ox, oz) = oasis(s)
                            val od = sqrt((x - ox) * (x - ox) + (z - oz) * (z - oz).toDouble())
                            hh = mix(hh, -3.0, smooth((10 - od + f(s, xd, zd, 8.0, 45, 2) * 4) / 5.0))
                        }
                        hh
                    }
                }
            }
            "dragon" -> {
                // highlands: steep-sided plateaus cut by canyons, around a hollow where the spire stands
                val top = clamp(size * 0.4, 16.0, 40.0) * (if (variant == 1) 1.25 else 1.0)
                val n = f01(s, wx, wz, size * 0.55, 52, 2)
                val plateau = smooth((n - 0.42) / 0.1)
                val canyon = smooth((Noise.ridged(s.seed + 53 * 7919L, wx / (size * 0.9), wz / (size * 0.9), 2) - 0.8) / 0.08)
                val back = 0.25 + 0.75 * smooth((backness(s, zone, x, z) + 0.15) / 0.55)
                2 + (top * plateau * back + 3 * smooth((n - 0.3) / 0.1)) * (1 - 0.9 * canyon) * clearing(10.0) * smooth((1.0 - q) / 0.35) + f(s, xd, zd, 16.0, 54, 2) * 1.2
            }
            "ghost" -> {
                // a lonely hill with the tower on top, in rolling gloomy land
                val qn = q * (1 + f(s, xd, zd, 24.0, 21, 2) * 0.15)
                val hill = smooth((0.6 - qn) / 0.45)
                11 * hill + f(s, wx, wz, 36.0, 22, 3) * 2.5 * (1 - hill * 0.6) + 1
            }
            "poison" -> {
                // low, wet ground full of pools
                val pool = smooth((f01(s, wx, wz, if (variant == 2) 22.0 else 16.0, 23, 3) - 0.58) / 0.1)
                1.0 + f(s, wx, wz, 40.0, 24, 2) - 3.6 * pool
            }
            "grass" -> when (variant) {
                0 -> f(s, wx, wz, 40.0, 10) * 6 + 4
                1 -> f(s, wx, wz, 46.0, 12) * 5 + 2.5
                else -> f(s, wx, wz, 70.0, 13) * 4 + 2
            }
            "fairy" -> {
                // soft hills with little ponds
                val pond = smooth((f01(s, wx, wz, 20.0, 17, 2) - 0.7) / 0.07) * smooth((rB - 14) / 4.0)
                f(s, wx, wz, 48.0, 16, 3) * (if (variant == 0) 5.0 else 3.0) + 2.2 - pond * 4.2
            }
            "electric" -> {
                // open plains with the odd rocky knoll (or rocky hills)
                val tors = smooth((f01(s, wx, wz, 13.0, 33, 2) - 0.8) / 0.07) * 3
                tors + when (variant) {
                    0 -> f(s, wx, wz, 60.0, 32, 2) * 2.2 + 1.2
                    1 -> f(s, wx, wz, 50.0, 26, 2) * 1.5 + 1
                    else -> Math.pow(ridge(s, wx, wz, 60.0, 31, 2), 1.6) * 10 + f(s, wx, wz, 30.0, 30, 2) * 1.5
                }
            }
            else -> if (CustomTypes.isCustom(zone)) customShape(s, CustomTypes.looksOf(zone).let { it[variant.coerceIn(0, it.size - 1)] }.terrain, xd, zd, wx, wz, q)
                else f(s, wx, wz, 34.0, 62) * 4 + 1
        }
        return h * e + EDGE_LEVEL * (1 - e)
    }

    /**
     * Where a mountain habitat's peak stands: pushed to the back of the habitat (towards the valley wall), so the
     * front facing the town stays low and easy to walk, with the high ground behind it.
     */
    fun peakOf(s: Sanctuary, zone: String): Pair<Double, Double>? {
        val hb = s.habitats[zone] ?: return null
        val shift = hb.radial * 0.5
        return hb.x + sin(hb.angle) * shift to hb.z - cos(hb.angle) * shift
    }

    /** How far towards the back of a habitat (x, z) is: -1 at its front (towards the town), 0 in the middle, 1 at the back. */
    private fun backness(s: Sanctuary, zone: String, x: Int, z: Int): Double {
        val hb = s.habitats[zone] ?: return 0.0
        return (((x - hb.x) * sin(hb.angle) - (z - hb.z) * cos(hb.angle)) / hb.radial).coerceIn(-1.5, 1.5)
    }

    /** Where the desert's oasis lies: halfway out from its middle, in a direction picked by the world. */
    private fun oasis(s: Sanctuary): Pair<Int, Int> {
        val hb = s.habitats["ground"] ?: return s.center.x to s.center.z
        val a = Noise.hash(s.seed, 3, 17) * 2 * Math.PI
        val d = min(hb.radial, hb.along) * 0.5
        return (hb.x + cos(a) * d).toInt() to (hb.z + sin(a) * d).toInt()
    }

    /** A biome's own shape, or flat for a biome that's still asleep. */
    private fun shape(s: Sanctuary, zone: String, x: Int, z: Int, ring: List<String>): Double =
        if (zone != ZoneLayout.HUB && zone !in s.unlocked) 0.0 else rawOffset(s, zone, variantOf(s, zone), x, z, ring)

    /** Width of the band where two neighbouring biomes melt into each other. */
    private const val BLEND = 24.0

    /** The mountains closing in the valley. */
    private fun rimHeight(s: Sanctuary, x: Int, z: Int, dist: Double): Double {
        val t = smooth((dist - s.size.radius - 1) / 34.0)
        val xd = x.toDouble()
        val zd = z.toDouble()
        return t * (24 + f01(s, xd, zd, 60.0, 63) * 18 + ridge(s, xd, zd, 26.0, 64) * 14)
    }

    /**
     * Target top height of a column. Near a border both biomes' shapes are mixed (50/50 right on the border),
     * so hills and lakes flow from one biome into the next instead of stopping at a flat strip.
     */
    // ------------------------------------------------------------------ height memory while building
    // Each column's height is asked for ~5 times in a pass (for itself, and by each neighbour measuring its slope).
    // While the generator runs, heights are remembered in a fixed-size table; anything that changes the shape of
    // the land (the map, the size, a habitat waking up) gives a new key and so forgets all of them.
    private const val HC_BITS = 17
    private val hcPos = LongArray(1 shl HC_BITS)
    private val hcVal = IntArray(1 shl HC_BITS)
    private val hcStamp = IntArray(1 shl HC_BITS)
    private var stamp = 1
    private var hcKey = Long.MIN_VALUE
    private var hcFor: java.util.UUID? = null
    private var hcOn = false

    /** Start remembering heights for [s] (only while the generator is working on it). */
    fun beginHeights(s: Sanctuary) {
        // (the habitats' looks shape their ground too)
        var k = s.layoutSalt * 1_000_003L + s.size.radius * 31L + s.unlocked.hashCode() * 7L + s.center.hashCode() + s.looksKey() * 13L +
            (if (SanctuaryConfig.reshapeTerrain.get()) 1 else 0) + (if (SanctuaryConfig.caves.get()) 2 else 0)
        for ((zone, hb) in s.habitats) k = k * 31 + zone.hashCode() + hb.x * 131L + hb.z * 17L + (hb.radial * 7).toLong()
        if (k != hcKey || s.id != hcFor) { hcKey = k; hcFor = s.id; stamp++ }
        hcOn = true
    }

    fun endHeights() { hcOn = false }

    fun targetHeight(s: Sanctuary, x: Int, z: Int, ring: List<String>): Int {
        if (!hcOn || s.id != hcFor) return computeHeight(s, x, z, ring)
        val pos = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        val slot = ((pos * -0x61c8864680b583ebL) ushr (64 - HC_BITS)).toInt()
        if (hcStamp[slot] == stamp && hcPos[slot] == pos) return hcVal[slot]
        val h = computeHeight(s, x, z, ring)
        hcPos[slot] = pos; hcVal[slot] = h; hcStamp[slot] = stamp
        return h
    }

    /**
     * The height, with the routes graded: a route follows a smoothed line of the ground along it (never more than a
     * block's step from one block to the next), cutting through high ground and banking up over dips, with gentle
     * shoulders either side. Not over the lake.
     */
    private fun computeHeight(s: Sanctuary, x: Int, z: Int, ring: List<String>): Int {
        val h = shapedHeight(s, x, z, ring)
        if (h < WATER_Y + 1) return h
        val r = routeAt(s, x, z) ?: return h
        if (r.dist >= ROUTE_BANK) return h
        val graded = routeProfile(s, r.zone, ring)?.at(r.along) ?: return h
        val w = smooth((ROUTE_BANK - r.dist) / (ROUTE_BANK - 1.5))
        var v = mix(h.toDouble(), graded, w).roundToInt().coerceAtLeast(WATER_Y + 1)
        // (a cutting stops well above a cave under it, never opening it to the sky; a cave mouth is left as it is)
        if (v < h) Caves.at(s, x, z)?.let { c -> v = if (c.mouth) h else max(v, min(h, c.ceil + 4)) }
        return v
    }

    /** How far either side of a route the ground is eased towards its grade. */
    private const val ROUTE_BANK = 6.0

    /** A route's graded heights, a block apart from its start. */
    private class RouteProfile(val start: Double, val h: DoubleArray) {
        fun at(along: Double): Double? {
            if (h.isEmpty()) return null
            val t = (along - start).coerceIn(0.0, (h.size - 1).toDouble())
            val i = t.toInt().coerceAtMost(h.size - 2).coerceAtLeast(0)
            return if (h.size == 1) h[0] else h[i] + (h[(i + 1).coerceAtMost(h.size - 1)] - h[i]) * (t - i)
        }
    }

    private val routeProfiles = HashMap<String, RouteProfile>()
    private var routeProfileKey = Long.MIN_VALUE

    private fun routeProfile(s: Sanctuary, zone: String, ring: List<String>): RouteProfile? {
        var k = s.id.hashCode() * 31L + s.layoutSalt * 1_000_003L + s.size.radius * 31L + s.unlocked.hashCode() * 7L + s.center.hashCode() +
            s.looksKey() * 13L + s.landmarks.hashCode() * 3L + s.customSubs.hashCode() * 5L + s.customPockets.hashCode() * 11L +
            (if (SanctuaryConfig.reshapeTerrain.get()) 1 else 0) + (if (SanctuaryConfig.caves.get()) 2 else 0)
        for ((z, hb) in s.habitats) k = k * 31 + z.hashCode() + hb.x * 131L + hb.z * 17L + (hb.radial * 7).toLong() + (hb.along * 3).toLong()
        if (k != routeProfileKey) { routeProfiles.clear(); routeProfileKey = k }
        routeProfiles[zone]?.let { return it }
        val hb = s.habitats[zone] ?: return null
        val l = routeLine(s, zone, hb)
        val n = (l.end - l.start).toInt() + 1
        if (n < 2) return null
        // the ground along the route's middle line (the lake counts as its shore's height)
        val raw = DoubleArray(n) { i ->
            val a = l.start + i
            val b = bend(s, l, a)
            val x = s.center.x + l.ux * a + l.uz * b
            val z = s.center.z + l.uz * a - l.ux * b
            max(shapedHeight(s, x.roundToInt(), z.roundToInt(), ring).toDouble(), WATER_Y + 1.0)
        }
        // smoothed over ±10 blocks, then no steeper than one block up in two
        val win = 10
        val g = DoubleArray(n) { i ->
            var sum = 0.0
            var c = 0
            for (j in (i - win).coerceAtLeast(0)..(i + win).coerceAtMost(n - 1)) { sum += raw[j]; c++ }
            sum / c
        }
        // (the start keeps the town's height and the end the landmark's: the route arrives level with them)
        g[0] = raw[0]
        g[n - 1] = raw[n - 1]
        val grade = 0.55
        repeat(2) {
            for (i in 1 until n) g[i] = g[i].coerceIn(g[i - 1] - grade, g[i - 1] + grade)
            for (i in n - 2 downTo 0) g[i] = g[i].coerceIn(g[i + 1] - grade, g[i + 1] + grade)
        }
        // over a cave the route can't cut down (it would open the cave): there it rises, gently, from both sides
        val floorAt = DoubleArray(n) { i ->
            val a = l.start + i
            val b = bend(s, l, a)
            val cave = Caves.at(s, (s.center.x + l.ux * a + l.uz * b).roundToInt(), (s.center.z + l.uz * a - l.ux * b).roundToInt())
            if (cave != null && !cave.mouth) min(raw[i], cave.ceil + 4.0) else -1.0
        }
        if (floorAt.any { it >= 0 }) {
            for (i in 0 until n) g[i] = max(g[i], floorAt[i])
            repeat(2) {
                for (i in 1 until n) g[i] = max(g[i], g[i - 1] - grade)
                for (i in n - 2 downTo 0) g[i] = max(g[i], g[i + 1] - grade)
            }
        }
        return RouteProfile(l.start, g).also { routeProfiles[zone] = it }
    }

    private fun shapedHeight(s: Sanctuary, x: Int, z: Int, ring: List<String>): Int {
        val base = baseHeight(s, x, z, ring)
        val (p, d) = ZoneLayout.pocketAt(s, x, z) ?: return base
        if (!p.live(s)) return base
        val level = p.level(s)
        // (a sub-biome blends into the habitat around it over a wider band)
        val w = smooth((p.r + 4 - d) / (if (p.sub) 12.0 else 4.0))
        val flat = mix(base.toDouble(), level.toDouble(), w)
        val h = when (p.zone) {
            // ponds and bogs: a level hollow with a pool dug in its middle, the water flush with the ground
            "water", "poison" -> if (d < p.r * 0.72) level - 1 - 2 * smooth((p.r * 0.72 - d) / 3.0) else flat
            // outcrops, crags and snowy rocks: a rough knoll
            "rock", "dragon", "ice", "fire" -> {
                val tall = when (p.zone) { "dragon" -> 7.0; "rock" -> 4.5; "fire" -> 3.0; else -> 3.5 }
                val n = 0.7 + 0.6 * Noise.value(s.seed + 5050 + p.salt, x / 4.0, z / 4.0)
                flat + tall * Math.pow(smooth((p.r - d) / p.r), 1.3) * n
            }
            // a custom type's pocket, shaped like its look in small
            // a sub-biome: its look's own ground, at the level of the habitat around it
            else -> if (p.sub) {
                val sh = customShape(s, CustomTypes.look(s, p.zone).terrain, x.toDouble(), z.toDouble(), x.toDouble(), z.toDouble(), (d / p.r).coerceIn(0.0, 1.2))
                val v = mix(base.toDouble(), level + sh - 1.5, w)
                // (on a mountain it only builds up, never digging down towards the caves under it)
                if (p.host in MOUNTAINS) max(base.toDouble(), v) else v
            } else if (CustomTypes.isCustom(p.zone)) {
                val k = d / p.r
                val rise = Math.pow(smooth((p.r - d) / p.r), 1.3)
                when (CustomTypes.look(s, p.zone).terrain) {
                    CustomTypes.Terrain.HOLLOW -> flat - 2.0 * smooth((0.8 - k) / 0.4)
                    CustomTypes.Terrain.CRATER -> flat - 2.0 * smooth((0.6 - k) / 0.3) + 2.0 * exp(-((k - 0.82) / 0.13).let { it * it })
                    CustomTypes.Terrain.SPIRES, CustomTypes.Terrain.CANYON, CustomTypes.Terrain.PLATEAUS, CustomTypes.Terrain.TERRACES ->
                        flat + 3.0 * rise * (0.7 + 0.6 * Noise.value(s.seed + 5051 + p.salt, x / 4.0, z / 4.0))
                    CustomTypes.Terrain.HILLS, CustomTypes.Terrain.DUNES -> flat + 1.6 * rise
                    else -> flat
                }
            } else mix(base.toDouble(), level.toDouble(), w * 0.6)
        }
        return h.roundToInt()
    }

    /** The water level of a pond pocket at (x, z), or null when the column isn't in one. */
    fun pondLevel(s: Sanctuary, x: Int, z: Int): Int? {
        val (p, d) = ZoneLayout.pocketAt(s, x, z) ?: return null
        if (!p.live(s) || (p.zone != "water" && p.zone != "poison") || d >= p.r * 0.72) return null
        return p.level(s)
    }

    /** The ground of a custom type's own habitat, by its look's shape ([q]: 0 in the middle, 1 at its edge). */
    private fun customShape(s: Sanctuary, t: CustomTypes.Terrain, xd: Double, zd: Double, wx: Double, wz: Double, q: Double): Double {
        val h = when (t) {
            CustomTypes.Terrain.HILLS -> f(s, wx, wz, 44.0, 63) * 5 + 2.5
            CustomTypes.Terrain.PLATEAUS -> 2 + 9 * smooth((f01(s, wx, wz, 38.0, 64, 2) - 0.52) / 0.06) + f(s, xd, zd, 20.0, 65, 2) * 0.8
            // steps with short risers
            CustomTypes.Terrain.TERRACES -> {
                val v = f01(s, wx, wz, 46.0, 67, 2) * 4.5
                floor(v) * 2.5 + smooth((v - floor(v) - 0.8) / 0.2) * 2.5 + 1
            }
            CustomTypes.Terrain.HOLLOW -> 1.0 + 7 * smooth((q - 0.6) / 0.22) + f(s, wx, wz, 30.0, 66, 2) * 1.2
            // a basin with a raised rim, the ground outside it lower again
            CustomTypes.Terrain.CRATER -> 2.0 + 8 * exp(-((q - 0.5) / 0.12).let { it * it }) - 1.2 * smooth((0.38 - q) / 0.15) + f(s, wx, wz, 26.0, 68, 2) * 0.6
            CustomTypes.Terrain.SPIRES -> f(s, wx, wz, 40.0, 69) * 2 + 1.8
            CustomTypes.Terrain.MARSH -> 0.8 + f(s, wx, wz, 24.0, 70) * 0.5
            // long rounded ridges
            CustomTypes.Terrain.DUNES -> {
                val r = 1 - abs(2 * f01(s, wx + zd * 0.6, wz * 0.45, 30.0, 71, 2) - 1)
                1.2 + 5.5 * Math.pow(r, 1.6)
            }
            // high ground split by a winding gorge
            CustomTypes.Terrain.CANYON -> {
                val g = abs(f01(s, wx, wz, 55.0, 72, 2) - 0.5)
                9.0 - 7.5 * smooth((0.075 - g) / 0.04) + f(s, xd, zd, 18.0, 73, 2) * 0.8
            }
            CustomTypes.Terrain.FLAT -> 1.4 + f(s, xd, zd, 40.0, 74, 2) * 0.4
        }
        // where a route runs through, the ground eases down into a gentle pass (no staircase up a plateau or a crater's rim)
        val rd = routeDistance(s, xd.toInt(), zd.toInt())
        val eased = if (rd < 12) 1.5 + (h - 1.5) * (1 - 0.9 * smooth((12 - rd) / 9.0)) else h
        // (no lakes in them)
        return eased.coerceAtLeast(0.6)
    }

    /** Target height without pockets (a pocket is built at the height its surroundings have here). */
    fun baseHeight(s: Sanctuary, x: Int, z: Int, ring: List<String> = ZoneLayout.HABITATS, asIfAwake: Boolean = false): Int {
        fun shape(s: Sanctuary, zone: String, x: Int, z: Int, ring: List<String>): Double =
            if (asIfAwake) rawOffset(s, zone, variantOf(s, zone), x, z, ring) else this@BiomeDresser.shape(s, zone, x, z, ring)
        val dx = (x - s.center.x).toDouble()
        val dz = (z - s.center.z).toDouble()
        val dist = sqrt(dx * dx + dz * dz)
        if (dist > s.size.radius) return G + rimHeight(s, x, z, dist).roundToInt()
        if (!SanctuaryConfig.reshapeTerrain.get()) return G
        val hit = ZoneLayout.locate(s, x, z, ring) ?: return G
        val hubOff = shape(s, ZoneLayout.HUB, x, z, ring) * smooth((dist - 12) / 24.0)
        var off: Double
        if (hit.zone == ZoneLayout.HUB) {
            off = hubOff
            val nb = hit.neighbour
            if (nb != null && hit.edgeDistance < BLEND) {
                val w = 0.5 + 0.5 * smooth(hit.edgeDistance / BLEND)
                off = off * w + shape(s, nb, x, z, ring) * smooth((dist - ZoneLayout.hubRadiusAt(s, x, z)) / 20.0) * (1 - w)
            }
        } else {
            off = shape(s, hit.zone, x, z, ring)
            val nb = hit.neighbour
            if (nb != null && hit.edgeDistance < BLEND) {
                val w = 0.5 + 0.5 * smooth(hit.edgeDistance / BLEND)
                off = off * w + shape(s, nb, x, z, ring) * (1 - w)
            }
            // into the hub
            val hubT = smooth((dist - ZoneLayout.hubRadiusAt(s, x, z)) / 20.0)
            off = off * hubT + hubOff * (1 - hubT)
        }
        // down to the wall
        off *= smooth((s.size.radius - 6 - dist) / 18.0)
        // the caves always have mountain over them (even where they reach under a neighbouring habitat)
        // (only where there is a cave: elsewhere the cover is 0, and lakes and pools must stay below it)
        // a level apron in front of the cave mouths of the other mountains (the Cave & mountain range has its own)
        if (s.caveStyle >= 2) {
            val ap = Caves.apron(s, x, z, only = null, except = Caves.ZONE)
            if (ap > 0.0) { val fo = Caves.floorOffset(s); if (off > fo) off = mix(off, fo, ap) }
        }
        val cover = Caves.cover(s, x, z)
        if (cover > 0.0 && cover > off) off = cover
        return G + off.roundToInt()
    }

    // ================================================================== main entry

    /** The look the owner chose for [zone] (0..2, or 3 for the fourth look). */
    fun lookOf(s: Sanctuary, zone: String): Int =
        s.variants.getOrPut(zone) { (Noise.hash(s.seed, zone.hashCode(), 7) * 3).toInt().coerceIn(0, 2) }

    /** The look whose land [zone] is built with (the fourth look keeps another look's land). */
    fun variantOf(s: Sanctuary, zone: String): Int =
        lookOf(s, zone).let { if (it == Remix.INDEX) Remix.ALL[zone]?.base ?: 0 else it }

    private fun key(name: String): ResourceKey<Biome> =
        ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("cobblemon_sanctuary", name))

    /** The custom biome painted under each sanctuary biome (custom types from other mods get the meadow). */
    fun biomeKey(zone: String): ResourceKey<Biome> =
        key(if (zone in ZoneLayout.BASE_RING || zone == ZoneLayout.HUB) zone else "meadow")
    val RIM_BIOME: ResourceKey<Biome> = key("rim")
    val MEADOW_BIOME: ResourceKey<Biome> = key("meadow")

    /** How exposed the column is (how deep its side shows) and how steep the ground around it is. */
    private fun around(s: Sanctuary, x: Int, z: Int, y: Int, ring: List<String>): Pair<Int, Int> {
        var low = y
        var step = 0
        for ((ox, oz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val h = targetHeight(s, x + ox, z + oz, ring)
            low = min(low, h)
            step = max(step, abs(h - y))
        }
        return (y - low + 1).coerceIn(3, 48) to step
    }

    private fun exposure(s: Sanctuary, x: Int, z: Int, y: Int, ring: List<String>): Int = around(s, x, z, y, ring).first

    /** Moves the column's top to [target]: builds it up with stone or cuts natural blocks away. */
    private fun reshape(level: ServerLevel, ground: BlockPos, target: Int, keep: Set<Long>? = null): BlockPos {
        if (target > ground.y) {
            for (yy in ground.y + 1..target) {
                val p = BlockPos(ground.x, yy, ground.z)
                if (keep == null || p.asLong() !in keep) level.setBlock(p, b(Blocks.STONE), BULK)
            }
        } else if (target < ground.y) {
            for (yy in target + 1..ground.y) {
                val p = BlockPos(ground.x, yy, ground.z)
                val st = level.getBlockState(p)
                // natural ground, and whatever was growing or hanging inside it (glow lichen from an old cave's
                // ceiling, dripstone, mushrooms…), or it would be left floating in the air
                // everything above the new ground goes (lava too: an old cave's pool would be left there and run),
                // but the mod's own blocks (beds, nests…)
                if (!st.isAir && !ValleyBlocks.isMods(st) && (keep == null || p.asLong() !in keep)) {
                    if (!ValleyBlocks.belongs(st)) { Trace.removed(st); ValleyBlocks.remove(level, p) } else level.setBlock(p, b(Blocks.AIR), BULK)
                }
            }
        }
        return BlockPos(ground.x, target, ground.z)
    }

    /**
     * Fills what's hollow under the new ground: the tunnels of a cave from an older layout (or an old pond) would
     * otherwise stay under it with a thin roof, and open into a pit once the roof goes. Today's cave is carved out
     * again right after. A column with anything built in it (a player's cellar) is left as it is.
     */
    private fun fillHollows(level: ServerLevel, x: Int, z: Int, target: Int) {
        // (deep enough for the old joining tunnels too, which dip about 20 below the valley floor)
        val bottom = max(level.minBuildHeight + 1, G - 24)
        val holes = ArrayList<BlockPos>()
        for (y in target - 1 downTo bottom) {
            val p = BlockPos(x, y, z)
            val st = level.getBlockState(p)
            if (st.isAir || !st.fluidState.isEmpty || (isClutter(st) && !st.`is`(BlockTags.FLOWERS)) || st.`is`(Blocks.OCHRE_FROGLIGHT) || st.`is`(Blocks.AMETHYST_CLUSTER)) {
                // (a lake bed's own water is above the target, so water down here is buried)
                holes += p
                continue
            }
            if (isNatural(st)) continue
            return // something built: leave the column alone
        }
        if (holes.isEmpty()) return
        Trace.note(x, z, "filled ${holes.size} hollow blocks under $target")
        for (p in holes) level.setBlock(p, b(Blocks.STONE), BULK)
    }

    private fun randomFor(s: Sanctuary, x: Int, z: Int): RandomSource =
        RandomSource.create(s.seed xor (x * 341873128712L) xor (z * 132897987541L))

    /** Dresses one column inside the sanctuary. Returns false if it can't be done right now. */
    fun dress(level: ServerLevel, s: Sanctuary, x: Int, z: Int, hit: ZoneLayout.Hit, ring: List<String>): Boolean {
        val done = dressSurface(level, s, x, z, hit, ring)
        if (done && shaping && hit.zone in s.unlocked && hit.dist < s.size.radius - 2) {
            Caves.at(s, x, z)?.let { spot ->
                val target = targetHeight(s, x, z, ring)
                // under a landmark only where the tunnel runs deep below it
                if (!Landmarks.near(s, x, z) || spot.ceil < target - 8) withPhase(Phase.ALL) { carveCave(level, s, x, z, spot, target, ring) }
            }
        }
        return done
    }

    /**
     * (Run with everything on: underground, the cave's own dripstone and crystals can go in with the land.)
     * Hollows a column out for the cave: air from its floor to its ceiling (and, at a cave mouth, all the way up),
     * with a rocky floor, glow lichen on the ceiling to light it, dripstone and the odd amethyst cluster.
     * Everything is kept clear of the middle of the passages so big Pokémon get through.
     */
    private fun carveCave(level: ServerLevel, s: Sanctuary, x: Int, z: Int, spot: Caves.Spot, target: Int, ring: List<String>) {
        val roofed = target - spot.ceil >= 4
        val top = when {
            roofed -> spot.ceil
            spot.mouth -> target + 20
            else -> max(spot.floor + 4, target - 4)
        }
        val air = b(Blocks.AIR)
        Trace.note(x, z, "carve ${spot.floor}..$top target=$target cave=${Caves.network(s)?.key}")
        for (y in spot.floor..top) {
            val p = BlockPos(x, y, z)
            if (!level.getBlockState(p).isAir) level.setBlock(p, air, if (y >= top - 1) FLAGS else BULK)
        }
        if (spot.zone != Caves.ZONE) { themedCave(level, s, x, z, spot, target, top, roofed, ring); return }
        val floorPos = BlockPos(x, spot.floor - 1, z)
        val c = Ctx(level, s, floorPos, randomFor(s, x, z), variantOf(s, Caves.ZONE), ZoneLayout.Hit(Caves.ZONE, 0.0, 99.0, null), 3, 0)
        val tuff = c.variant == 2
        val rock = if (tuff) b(Blocks.TUFF) else b(Blocks.STONE)
        // old water buried in the mountain (an older lake or pond under raised ground) mustn't show in the cave:
        // in the floor, the ceiling and the walls next to it, it's turned into rock (the volcano's lava is left be)
        fun seal(p: BlockPos) { if (level.getBlockState(p).fluidState.`is`(net.minecraft.tags.FluidTags.WATER)) level.setBlock(p, rock, BULK) }
        for (y in spot.floor - 4 until spot.floor) seal(BlockPos(x, y, z))
        if (roofed) for (y in top + 1..top + 3) seal(BlockPos(x, y, z))
        for (d in Direction.Plane.HORIZONTAL) {
            val nx = x + d.stepX
            val nz = z + d.stepZ
            val other = Caves.at(s, nx, nz)
            for (y in spot.floor - 2..top + 2) {
                if (other != null && y in other.floor..other.ceil) continue // that's cave too
                seal(BlockPos(nx, y, nz))
            }
        }
        // the floor: rock and gravel, mossy where daylight reaches in (not where the mouth opens onto lower ground:
        // a floor laid above it would stand there as a step)
        if (spot.floor - 1 <= target) c.ground(when {
            spot.mouth -> c.broad(b(Blocks.GRAVEL), b(Blocks.MOSS_BLOCK), 0.5, 6.0, 1)
            else -> c.broad(c.broad(rock, b(Blocks.GRAVEL), 0.62, 9.0, 2), if (tuff) b(Blocks.DRIPSTONE_BLOCK) else b(Blocks.ANDESITE), 0.72, 7.0, 3)
        }, rock)
        if (!roofed && spot.mouth) {
            if (c.chance(0.15)) c.place(b(Blocks.MOSS_CARPET))
            return
        }
        val ceiling = BlockPos(x, top + 1, z)
        val wall = spot.centre > 0.55 // towards the sides, out of the way
        // light: glow lichen spreading over the ceiling, a few glowing spots in the hall
        if (level.getBlockState(ceiling).isSolid) {
            if (spot.inHall && c.grid(9, 4)) level.setBlock(ceiling, b(Blocks.OCHRE_FROGLIGHT), FLAGS)
            else if (c.noise(7.0, 5) > 0.42) level.setBlock(ceiling.below(), b(Blocks.GLOW_LICHEN).setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.UP, true), FLAGS)
            else if (wall && c.grid(7, 6)) {
                level.setBlock(ceiling.below(), b(Blocks.POINTED_DRIPSTONE)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DRIPSTONE_THICKNESS, net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP), FLAGS)
            }
        }
        if (!wall) return
        when {
            c.grid(11, 7) -> c.place(b(Blocks.POINTED_DRIPSTONE)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.VERTICAL_DIRECTION, Direction.UP)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DRIPSTONE_THICKNESS, net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP))
            spot.inHall && c.grid(13, 8) -> c.place(b(Blocks.AMETHYST_CLUSTER))
            c.chance(0.04) -> c.place(c.pick(b(Blocks.BROWN_MUSHROOM), b(Blocks.RED_MUSHROOM)))
            c.chance(0.05) -> c.place(b(Blocks.GLOW_LICHEN).setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOWN, true))
        }
    }

    /**
     * The other mountains' parts of the caves, each with its own look:
     * the snowy peak's ice cavern (packed and blue ice, frozen pools, icicles, pale light),
     * the dragons' crystal grotto (deepslate and calcite, amethyst growing from the ceiling and floor, green light),
     * and the volcano's magma chamber (blackstone and basalt, glowing magma, shroomlight and real lava pools along
     * the walls: Pokémon don't mind it, but watch your step).
     */
    private fun themedCave(level: ServerLevel, s: Sanctuary, x: Int, z: Int, spot: Caves.Spot, target: Int, top: Int, roofed: Boolean, ring: List<String>) {
        val zone = spot.zone
        val floorPos = BlockPos(x, spot.floor - 1, z)
        val c = Ctx(level, s, floorPos, randomFor(s, x, z), variantOf(s, zone), ZoneLayout.Hit(zone, 0.0, 99.0, null), 3, 0)
        fun st(block: Block) = b(block)
        val wallRock = when (zone) {
            "ice" -> c.broad(st(Blocks.PACKED_ICE), st(Blocks.BLUE_ICE), 0.7, 8.0, 21)
            "dragon" -> c.broad(st(Blocks.DEEPSLATE), st(Blocks.CALCITE), 0.62, 8.0, 22)
            "fire" -> c.broad(st(Blocks.BLACKSTONE), st(Blocks.BASALT), 0.58, 7.0, 23)
            else -> st(Blocks.STONE)
        }
        val under = when (zone) { "ice" -> st(Blocks.PACKED_ICE); "dragon" -> st(Blocks.DEEPSLATE); "fire" -> st(Blocks.BLACKSTONE); else -> st(Blocks.STONE) }
        fun seal(p: BlockPos) { if (level.getBlockState(p).fluidState.`is`(net.minecraft.tags.FluidTags.WATER)) level.setBlock(p, under, BULK) }
        for (y in spot.floor - 4 until spot.floor) seal(BlockPos(x, y, z))
        if (roofed) for (y in top + 1..top + 3) seal(BlockPos(x, y, z))
        // the walls and the ceiling take the part's own rock (where it's inside the mountain, not out in the open)
        for (d in Direction.Plane.HORIZONTAL) {
            val nx = x + d.stepX
            val nz = z + d.stepZ
            val other = Caves.at(s, nx, nz)
            val nTop = targetHeight(s, nx, nz, ring) - 2
            for (y in spot.floor - 2..min(top + 2, nTop)) {
                if (other != null && y in other.floor..other.ceil) continue // that's cave too
                val p = BlockPos(nx, y, nz)
                val cur = level.getBlockState(p)
                if (cur.fluidState.`is`(net.minecraft.tags.FluidTags.WATER)) { level.setBlock(p, under, BULK); continue }
                if (!cur.isAir && cur.fluidState.isEmpty && isNatural(cur)) level.setBlock(p, if ((y + nx + nz) % 5 == 0) under else wallRock, BULK)
            }
        }
        if (roofed) for (y in top + 1..min(top + 2, target - 2)) {
            val p = BlockPos(x, y, z)
            if (isNatural(level.getBlockState(p)) && level.getBlockState(p).fluidState.isEmpty) level.setBlock(p, wallRock, BULK)
        }
        val wall = spot.centre > 0.55 // towards the sides, out of the way
        // the floor
        val floorTop = when (zone) {
            "ice" -> when {
                spot.mouth -> c.broad(st(Blocks.SNOW_BLOCK), st(Blocks.PACKED_ICE), 0.55, 6.0, 31)
                c.noise(6.0, 32) > 0.7 -> st(Blocks.ICE) // a frozen pool
                else -> c.broad(st(Blocks.PACKED_ICE), st(Blocks.SNOW_BLOCK), 0.6, 8.0, 33)
            }
            "dragon" -> when {
                spot.mouth -> c.broad(st(Blocks.GRAVEL), st(Blocks.CALCITE), 0.55, 6.0, 34)
                spot.inHall && c.noise(5.0, 35) > 0.74 -> st(Blocks.AMETHYST_BLOCK)
                else -> c.broad(c.broad(st(Blocks.DEEPSLATE), st(Blocks.SMOOTH_BASALT), 0.6, 9.0, 36), st(Blocks.CALCITE), 0.72, 7.0, 37)
            }
            "fire" -> when {
                spot.mouth -> c.broad(st(Blocks.BASALT), st(Blocks.GRAVEL), 0.55, 6.0, 38)
                c.noise(5.0, 39) > 0.68 -> st(Blocks.MAGMA_BLOCK)
                else -> c.broad(st(Blocks.BLACKSTONE), st(Blocks.BASALT), 0.55, 8.0, 40)
            }
            else -> st(Blocks.STONE)
        }
        if (spot.floor - 1 <= target) c.ground(floorTop, under)
        if (!roofed && spot.mouth) {
            if (zone == "ice" && c.chance(0.4)) c.place(st(Blocks.SNOW))
            return
        }
        val ceiling = BlockPos(x, top + 1, z)
        val hanging = ceiling.below()
        val solidCeiling = level.getBlockState(ceiling).isSolid
        fun dripDown() = st(Blocks.POINTED_DRIPSTONE)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DRIPSTONE_THICKNESS, net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP)
        when (zone) {
            "ice" -> {
                if (solidCeiling) when {
                    c.grid(if (spot.inHall) 8 else 11, 41) -> level.setBlock(ceiling, st(Blocks.PEARLESCENT_FROGLIGHT), FLAGS)
                    c.noise(6.0, 42) > 0.55 -> level.setBlock(ceiling, st(Blocks.BLUE_ICE), FLAGS)
                    wall && c.grid(5, 43) -> level.setBlock(hanging, dripDown(), FLAGS) // icicles
                }
                if (wall) when {
                    spot.centre > 0.78 && c.grid(9, 44) -> c.spike(st(Blocks.PACKED_ICE), 0, 2 + c.random.nextInt(3))
                    c.chance(0.25) -> c.place(st(Blocks.SNOW))
                } else if (floorTop.`is`(Blocks.SNOW_BLOCK) && c.chance(0.3)) c.place(st(Blocks.SNOW))
            }
            "dragon" -> {
                if (solidCeiling) when {
                    c.grid(if (spot.inHall) 8 else 11, 45) -> level.setBlock(ceiling, st(Blocks.VERDANT_FROGLIGHT), FLAGS)
                    c.noise(6.0, 46) > 0.5 -> {
                        level.setBlock(ceiling, st(Blocks.AMETHYST_BLOCK), FLAGS)
                        if (c.chance(0.35)) level.setBlock(hanging, st(Blocks.AMETHYST_CLUSTER).setValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING, Direction.DOWN), FLAGS)
                    }
                    c.noise(7.0, 5) > 0.5 -> level.setBlock(hanging, st(Blocks.GLOW_LICHEN).setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.UP, true), FLAGS)
                }
                if (wall) when {
                    c.grid(7, 47) -> c.place(st(if (c.chance(0.5)) Blocks.AMETHYST_CLUSTER else Blocks.LARGE_AMETHYST_BUD))
                    spot.centre > 0.78 && c.grid(13, 48) -> c.crystalCluster(st(Blocks.AMETHYST_BLOCK), st(Blocks.AMETHYST_CLUSTER), 2 + c.random.nextInt(2))
                    c.chance(0.04) -> level.setBlock(floorPos, st(Blocks.CRYING_OBSIDIAN), FLAGS)
                }
            }
            "fire" -> {
                if (solidCeiling) when {
                    c.grid(if (spot.inHall) 7 else 10, 49) -> level.setBlock(ceiling, st(Blocks.SHROOMLIGHT), FLAGS)
                    c.noise(6.0, 50) > 0.5 -> level.setBlock(ceiling, st(Blocks.MAGMA_BLOCK), FLAGS)
                }
                // real lava, in pools sunk into the floor along the walls: walled in on every side, so it never spreads
                if (wall && !spot.link && !spot.mouth && c.noise(5.0, 51) > 0.56 && lavaSafe(level, s, x, z, spot, ring)) {
                    level.setBlock(floorPos, st(Blocks.LAVA), FLAGS)
                    return
                }
                if (spot.centre > 0.78 && c.grid(9, 52)) c.crystalCluster(st(Blocks.BASALT), null, 2 + c.random.nextInt(3))
            }
        }
    }

    /** Can a lava pool go in the floor of this cave column without flowing anywhere? */
    private fun lavaSafe(level: ServerLevel, s: Sanctuary, x: Int, z: Int, spot: Caves.Spot, ring: List<String>): Boolean {
        val y = spot.floor - 1
        for (d in Direction.Plane.HORIZONTAL) {
            val nx = x + d.stepX
            val nz = z + d.stepZ
            val other = Caves.at(s, nx, nz)
            if (other != null) {
                // a lower floor beside it, or the open sky: the lava would run there
                if (other.floor < spot.floor || other.mouth) return false
            } else if (targetHeight(s, nx, nz, ring) < spot.floor + 1) return false
        }
        // and solid under it
        return !level.getBlockState(BlockPos(x, y - 1, z)).isAir
    }

    private fun dressSurface(level: ServerLevel, s: Sanctuary, x: Int, z: Int, hit: ZoneLayout.Hit, ring: List<String>): Boolean {
        val wallZone = SanctuaryConfig.buildWall.get() && hit.dist > s.size.radius - 1.6
        if (shaping) {
            if (s.oldWallRadius in 1 until s.size.radius && abs(hit.dist - (s.oldWallRadius - 0.8)) < 1.6) clearWall(level, x, z)
            if (wallZone) return wall(level, s, x, z)
        } else if (wallZone) return true
        if (hit.zone !in s.unlocked) return true
        if (Landmarks.near(s, x, z)) return true
        // a den and the way into it are left as they are
        if (Dens.covers(s, x, z)) return true

        val zone = surfaceZone(s, hit, x, z, ring)
        val target = targetHeight(s, x, z, ring)
        val surface: BlockPos
        if (shaping) {
            val found = findGround(level, s, x, z) ?: return false
            if (!isNatural(level.getBlockState(found))) { Trace.note(x, z, "skip: ${level.getBlockState(found).block} at ${found.y}"); return true }
            Trace.note(x, z, "land ${found.y}->$target ${hit.zone}")
            surface = reshape(level, found, target, placed[s.id])
            fillHollows(level, x, z, target)
        } else {
            // the land wave already shaped this column: work on top of it (unless something was built there since)
            surface = BlockPos(x, target, z)
            if (!isNatural(level.getBlockState(surface))) return true
        }
        // a cave mouth's way in stays clear of the habitat's own decorations
        if (!shaping && Caves.at(s, x, z)?.mouth == true) return true
        val nature = phase == Phase.ALL || phase == Phase.NATURE
        val extras = phase == Phase.ALL || phase == Phase.EXTRAS
        val (exp, slope) = around(s, x, z, target, ring)
        val ctx = Ctx(level, s, surface, randomFor(s, x, z), variantOf(s, zone), hit, exp, slope).also { it.remix = Remix.of(s, zone) }
        // a winding route from the town out to the habitat's landmark
        if (target >= WATER_Y + 1 && hit.dist > s.size.hubRadius - 4 && onRoute(s, hit, x, z, ring)) {
            val (top, fill) = routeBlocks(zone, variantOf(s, zone))
            ctx.ground(top, fill)
            if (extras && Math.floorMod(x * 7 + z * 13, 37) == 0 && hit.dist > s.size.hubRadius + 10) {
                // a little lamp along the way, just off the path
                val side = BlockPos(x + 2, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 2, z), z)
                // only on ground that's already at its final height (or it would be left floating when that column is lowered)
                if (side.y - 1 == targetHeight(s, x + 2, z, ring) && level.getBlockState(side).isAir && isNatural(level.getBlockState(side.below()))) {
                    level.setBlock(side, b(Blocks.SPRUCE_FENCE), FLAGS)
                    protect(s, side); protect(s, side.above())
                    level.setBlock(side.above(), b(if (hit.zone == "ghost" || hit.zone == "dark") Blocks.SOUL_LANTERN else Blocks.LANTERN), FLAGS)
                }
            }
            return true
        }
        // a pocket of another habitat: a pond, an outcrop, a patch of flowers…
        val pocket = ZoneLayout.pocketAt(s, x, z)?.takeIf { it.first.live(s) && it.second <= it.first.r + 1.5 }
        if (pocket != null) {
            val (p, d) = pocket
            val pc = Ctx(level, s, surface, ctx.random, variantOf(s, p.zone), hit, exp, slope).also { it.remix = Remix.of(s, p.zone) }
            if (phase != Phase.EXTRAS) pocket(pc, p, d)
            if (nature) cobblemonDecor(Ctx(level, s, surface, randomFor(s, x + 7919, z), variantOf(s, p.zone), hit, exp, slope), p.zone, target)
            if (extras) withPhase(Phase.ALL) { Props.place(pc, p.zone, ring) { px, pz -> pathAt(s, px, pz, ring) } }
            return true
        }
        // berry trees in the lusher biomes, where residents meet for meals
        if (extras && zone in BERRY_ZONES && ctx.grid(27, 400 + BERRY_ZONES.indexOf(zone)) && ctx.roomy(6) && target >= WATER_Y + 1) ctx.berryTree()
        // a lake that reaches over the border keeps its water, whichever biome it spills into
        if (zone != "water" && target < WATER_Y && (hit.neighbour == "water" || hit.zone == "water") && "water" in s.unlocked) {
            if (phase != Phase.EXTRAS) water(Ctx(level, s, surface, ctx.random, variantOf(s, "water").let { if (it == 2) 0 else it }, hit, exp, slope))
            return true
        }
        // (the land and nature waves run the same dresser: the first only shapes the ground, the second only places things)
        if (phase != Phase.EXTRAS) when (zone) {
            ZoneLayout.HUB -> hub(ctx)
            "water" -> water(ctx)
            "ground" -> ground(ctx)
            "rock" -> rock(ctx)
            "fighting" -> fighting(ctx)
            "fire" -> fire(ctx)
            "dragon" -> dragon(ctx)
            "dark" -> dark(ctx)
            "ghost" -> ghost(ctx)
            "poison" -> poison(ctx)
            "bug" -> bug(ctx)
            "grass" -> grass(ctx)
            "fairy" -> fairy(ctx)
            "psychic" -> psychic(ctx)
            "steel" -> steel(ctx)
            "electric" -> electric(ctx)
            "ice" -> ice(ctx)
            else -> custom(ctx, zone)
        }
        // the fourth look's own trees, here and there
        ctx.remix?.let { r -> r.extra?.let { e -> if (phase != Phase.EXTRAS && ctx.roomy() && !ctx.cliff && ctx.grid(r.extraEvery, 4242)) ctx.feature(e) } }
        // Cobblemon's plants and crystals (with their own randomness, so the dresser's choices are the same in every wave)
        if (nature) cobblemonDecor(Ctx(level, s, surface, randomFor(s, x + 7919, z), variantOf(s, zone), hit, exp, slope), zone, target)
        // a spot residents like to visit: a log, a rock, a pool, a campfire…
        if (extras) withPhase(Phase.ALL) { Props.place(ctx, zone, ring) { px, pz -> pathAt(s, px, pz, ring) } }
        return true
    }

    /** A route, or one of the hub's paths, runs through (x, z). */
    fun pathAt(s: Sanctuary, x: Int, z: Int, ring: List<String>): Boolean {
        val hit = ZoneLayout.locate(s, x, z, ring) ?: return true
        if (hit.zone == ZoneLayout.HUB) {
            val dx = x - s.center.x
            val dz = z - s.center.z
            val hubR = s.size.hubRadius
            if (hit.dist < hubR) return hit.dist in (hubR - 9.0)..(hubR - 2.0) || ((abs(dx) <= 2 || abs(dz) <= 2) && hit.dist > 5)
        }
        return routeDistance(s, x, z) < 3.0
    }

    /**
     * Which biome's ground and plants a column gets. Near a border the two biomes interlock in organic patches
     * (bigger the closer to the border), and the hub's meadow reaches into the biomes the same way.
     */
    private fun surfaceZone(s: Sanctuary, hit: ZoneLayout.Hit, x: Int, z: Int, ring: List<String>): String {
        val xd = x.toDouble()
        val zd = z.toDouble()
        val nb = hit.neighbour ?: return hit.zone
        if (nb !in s.unlocked || hit.edgeDistance >= 14) return hit.zone
        // a field shared by both sides of the border, so the patches line up
        val first = if (hit.zone < nb) hit.zone else nb
        val signed = if (hit.zone == first) hit.edgeDistance else -hit.edgeDistance
        val n = f01(s, xd, zd, 9.0, 71, 3)
        return if (n + signed / 28.0 > 0.5) first else if (first == hit.zone) nb else hit.zone
    }

    /** Is (x, z) on a route? Routes wind from the town out through the meadows to every habitat's landmark. */
    private fun onRoute(s: Sanctuary, hit: ZoneLayout.Hit, x: Int, z: Int, ring: List<String>): Boolean =
        routeDistance(s, x, z) < 1.3

    /** How far (x, z) is from the nearest route (a large number when there's none nearby). */
    fun routeDistance(s: Sanctuary, x: Int, z: Int): Double = routeAt(s, x, z)?.dist ?: 99.0

    /** The nearest route to (x, z): which habitat it leads to, how far along it from the middle, and how far off it. */
    class RouteHit(val zone: String, val along: Double, val dist: Double)

    /** Where a route runs: its line from the middle of the valley out to its habitat's landmark, bent by noise. */
    private class RouteLine(val ux: Double, val uz: Double, val start: Double, val end: Double, val i: Int, val anchorDist: Double)

    private fun routeLine(s: Sanctuary, zone: String, hb: ZoneLayout.Habitat): RouteLine {
        // the route ends where the habitat's landmark stands: its anchor, fixed by the layout (so the route's shape
        // doesn't change when a landmark is built or moved, leaving ground built along the old one at the wrong height)
        val (ax, az) = if (zone == "fire") hb.x to hb.z else ZoneLayout.anchor(s, zone)
        val adx = (ax - s.center.x).toDouble()
        val adz = (az - s.center.z).toDouble()
        val anchorDist = sqrt(adx * adx + adz * adz)
        val len = anchorDist.coerceAtLeast(1.0)
        val i = ZoneLayout.HABITATS.indexOf(zone).let { if (it >= 0) it else 40 + (zone.hashCode() and 63) }
        return RouteLine(adx / len, adz / len, s.size.hubRadius - 5.0, anchorDist - 3, i, anchorDist)
    }

    /** How far the route bends off its straight line [along] it. */
    private fun bend(s: Sanctuary, l: RouteLine, along: Double): Double =
        (Noise.value(s.seed + 909 + l.i, along / 26.0, 0.5) - 0.5) * 22 * smooth((along - s.size.hubRadius) / 20.0) * smooth((l.anchorDist - along) / 14.0)

    fun routeAt(s: Sanctuary, x: Int, z: Int): RouteHit? {
        var best: RouteHit? = null
        for ((zone, hb) in s.habitats) {
            if (zone !in s.unlocked) continue
            val l = routeLine(s, zone, hb)
            val px = (x - s.center.x).toDouble()
            val pz = (z - s.center.z).toDouble()
            val along = px * l.ux + pz * l.uz
            if (along < l.start || along > l.end) continue
            val side = px * l.uz - pz * l.ux
            val d = abs(side - bend(s, l, along))
            if (best == null || d < best.dist) best = RouteHit(zone, along, d)
        }
        return best
    }

    /** A point along a route: where it is, which way the route runs there, and how far out from the middle. */
    class RoutePoint(val x: Double, val z: Double, val dx: Double, val dz: Double, val along: Double)

    /** Points every [step] blocks along the route to [zone] (empty when it has none). */
    fun routePoints(s: Sanctuary, zone: String, step: Double): List<RoutePoint> {
        val hb = s.habitats[zone] ?: return emptyList()
        if (zone !in s.unlocked) return emptyList()
        val l = routeLine(s, zone, hb)
        val out = ArrayList<RoutePoint>()
        var a = l.start
        while (a <= l.end) {
            val b0 = bend(s, l, a)
            val db = (bend(s, l, a + 1) - bend(s, l, a - 1)) / 2
            var tx = l.ux + l.uz * db
            var tz = l.uz - l.ux * db
            val len = sqrt(tx * tx + tz * tz).coerceAtLeast(1e-6)
            tx /= len; tz /= len
            out += RoutePoint(s.center.x + l.ux * a + l.uz * b0, s.center.z + l.uz * a - l.ux * b0, tx, tz, a)
            a += step
        }
        return out
    }

    /** Routes are trodden earth: a dirt path, or whatever the ground there wears down to. */
    private fun routeBlocks(zone: String, variant: Int): Pair<BlockState, BlockState> = when (zone) {
        "water" -> b(Blocks.SAND) to b(Blocks.SANDSTONE)
        "ground" -> if (variant == 1) b(Blocks.RED_SAND) to b(Blocks.TERRACOTTA) else b(Blocks.SMOOTH_SANDSTONE) to b(Blocks.SANDSTONE)
        "rock", "dragon", "steel" -> b(Blocks.GRAVEL) to b(Blocks.COARSE_DIRT)
        "fire" -> b(Blocks.GRAVEL) to b(Blocks.TUFF)
        "ice" -> b(Blocks.GRAVEL) to b(Blocks.COARSE_DIRT)
        "ghost", "dark" -> b(Blocks.COARSE_DIRT) to b(Blocks.PODZOL)
        "poison" -> b(Blocks.PACKED_MUD) to b(Blocks.MUD)
        else -> b(Blocks.DIRT_PATH) to b(Blocks.COARSE_DIRT)
    }

    /**
     * A biome that hasn't woken up yet (no resident of its type) is a plain meadow at ground level.
     * Flattening it matters after the valley grows: otherwise the old mountains would stay inside it.
     */
    fun dressMeadow(level: ServerLevel, s: Sanctuary, x: Int, z: Int, hit: ZoneLayout.Hit, ring: List<String>): Boolean {
        if (s.oldWallRadius in 1 until s.size.radius && abs(hit.dist - (s.oldWallRadius - 0.8)) < 1.6) clearWall(level, x, z)
        if (Landmarks.near(s, x, z)) return true
        val found = findGround(level, s, x, z) ?: return false
        if (!isNatural(level.getBlockState(found))) return true
        val surface = reshape(level, found, G, placed[s.id])
        val c = Ctx(level, s, surface, randomFor(s, x, z), 0, hit, exposure(s, x, z, G, ring))
        c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        // a tunnel joining two mountains' caves can pass deep under a meadow
        Caves.at(s, x, z)?.takeIf { it.link && it.ceil < G - 5 && hit.dist < s.size.radius - 2 }?.let { withPhase(Phase.ALL) { carveCave(level, s, x, z, it, G, ring) } }
        when {
            c.chance(0.2) -> c.place(b(Blocks.SHORT_GRASS))
            c.chance(0.01) -> c.place(c.pick(b(Blocks.DANDELION), b(Blocks.POPPY), b(Blocks.OXEYE_DAISY)))
        }
        return true
    }

    /** The mountains around the valley. */
    fun dressRim(level: ServerLevel, s: Sanctuary, x: Int, z: Int, dist: Double, ring: List<String>): Boolean {
        if (s.oldWallRadius > 0 && abs(dist - (s.oldWallRadius - 0.8)) < 1.6) clearWall(level, x, z)
        val found = findGround(level, s, x, z) ?: return false
        if (!isNatural(level.getBlockState(found))) return true
        val target = targetHeight(s, x, z, ring)
        val surface = reshape(level, found, target, placed[s.id])
        val c = Ctx(level, s, surface, randomFor(s, x, z), 0, ZoneLayout.Hit("rim", dist, 99.0, null), exposure(s, x, z, target, ring))
        val rise = surface.y - G
        val snowLine = 30 + (c.noise(20.0, 42) * 6).toInt()
        when {
            rise >= snowLine -> { c.ground(b(Blocks.SNOW_BLOCK), b(Blocks.STONE)); if (c.chance(0.3)) c.place(b(Blocks.SNOW)) }
            rise >= 17 || c.exposure > 5 -> {
                c.ground(c.patch(9.0, 43, b(Blocks.STONE), b(Blocks.ANDESITE), b(Blocks.STONE), b(Blocks.GRAVEL)), b(Blocks.STONE))
                if (c.exposure <= 4 && c.chance(0.015)) c.feature(TreeFeatures.SPRUCE)
            }
            else -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.chance(0.03) -> c.feature(if (rise > 8) TreeFeatures.SPRUCE else if (c.random.nextBoolean()) TreeFeatures.OAK else TreeFeatures.BIRCH)
                    c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
                    c.chance(0.01) -> c.place(c.pick(b(Blocks.DANDELION), b(Blocks.CORNFLOWER)))
                }
            }
        }
        return true
    }

    // ================================================================== the wall

    private val WALL_BLOCKS: Set<Block> = setOf(
        Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
        Blocks.LANTERN, Blocks.SPRUCE_FENCE_GATE, Blocks.STONE_BRICK_SLAB, Blocks.DIRT_PATH,
    )

    private fun clearWall(level: ServerLevel, x: Int, z: Int) {
        for (yy in G + 1..G + 8) {
            val p = BlockPos(x, yy, z)
            if (level.getBlockState(p).block in WALL_BLOCKS) level.setBlock(p, b(Blocks.AIR), FLAGS)
        }
    }

    private fun wall(level: ServerLevel, s: Sanctuary, x: Int, z: Int): Boolean {
        findGround(level, s, x, z) // clears trees and plants in the way
        for (yy in G + 1..G + 10) {
            val p = BlockPos(x, yy, z)
            val st = level.getBlockState(p)
            if (st.block in WALL_BLOCKS || isNatural(st) || isClutter(st) || naturalLeaf(st)) level.setBlock(p, b(Blocks.AIR), FLAGS)
        }
        val ground = BlockPos(x, G, z)
        level.setBlock(ground, b(Blocks.DIRT_PATH), FLAGS)
        level.setBlock(ground.below(), b(Blocks.DIRT), FLAGS)
        val dx = x - s.center.x
        val dz = z - s.center.z
        // four gates: north, east, south and west
        val northSouth = abs(dx) <= 1 && abs(dz) > s.size.radius / 2
        val eastWest = abs(dz) <= 1 && abs(dx) > s.size.radius / 2
        if (northSouth || eastWest) {
            val facing = if (northSouth) Direction.NORTH else Direction.EAST
            level.setBlock(ground.above(), b(Blocks.SPRUCE_FENCE_GATE).setValue(FenceGateBlock.FACING, facing), FLAGS)
            level.setBlock(ground.above(4), b(Blocks.CHISELED_STONE_BRICKS), FLAGS)
            if ((northSouth && dx == 0) || (eastWest && dz == 0)) level.setBlock(ground.above(5), b(Blocks.LANTERN), FLAGS)
            return true
        }
        var angle = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dz.toDouble()))
        if (angle < 0) angle += 360.0
        val step = (Math.toRadians(angle) * s.size.radius).toInt()
        val pillar = Math.floorMod(step, 16) < 2
        val height = if (pillar) 5 else 4
        for (i in 1..height) {
            val noise = Noise.value(s.seed + 77, (x + i * 31) / 4.0, z / 4.0)
            val st = when {
                pillar -> if (i == height) b(Blocks.CHISELED_STONE_BRICKS) else b(Blocks.STONE_BRICKS)
                noise > 0.68 -> b(Blocks.MOSSY_STONE_BRICKS)
                noise < 0.18 -> b(Blocks.CRACKED_STONE_BRICKS)
                else -> b(Blocks.STONE_BRICKS)
            }
            level.setBlock(ground.above(i), st, FLAGS)
        }
        if (pillar && Math.floorMod(step, 16) == 0) level.setBlock(ground.above(height + 1), b(Blocks.LANTERN), FLAGS)
        else if (!pillar && Math.floorMod(step, 4) < 2) level.setBlock(ground.above(height + 1), b(Blocks.STONE_BRICK_SLAB), FLAGS)
        return true
    }

    // ================================================================== ground finding + clearing

    /** The ground of a column, below trees and plants (which get cleared). Null = can't be done yet. */
    private fun findGround(level: ServerLevel, s: Sanctuary, x: Int, z: Int): BlockPos? {
        val clear = SanctuaryConfig.clearTrees.get()
        val air = b(Blocks.AIR)
        // from the very top: plants and crystals without collision would otherwise be left floating when the ground is lowered
        var p = BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z)
        val mine = placed[s.id]
        var guard = 0
        while (guard++ < 120 && p.y > level.minBuildHeight) {
            val st = level.getBlockState(p)
            when {
                st.isAir -> p = p.below()
                mine != null && p.asLong() in mine -> p = p.below() // placed by a neighbour in this pass: keep it, look under it
                // coral (alive, or dead once its water's gone) sits on the lake bed: it isn't the ground
                isCoral(st) -> { level.setBlock(p, air, FLAGS); p = p.below() }
                st.`is`(BlockTags.LEAVES) -> {
                    // placed leaves with no wood anywhere near (the canopy of a landmark that was taken down)
                    if (!naturalLeaf(st) && (mine == null || p.asLong() !in mine) && !leavesReachWood(level, p)) { level.setBlock(p, air, FLAGS); p = p.below(); continue }
                    if (!clear || !naturalLeaf(st)) return p
                    // take the whole tree while its leaves still show it's a natural one
                    val log = nearbyLog(level, p)
                    when {
                        log == null -> level.setBlock(p, air, FLAGS) // a stray leaf
                        removeTree(level, s, log) -> continue
                        // else: part of a tree the sanctuary planted next door, keep it and look underneath
                    }
                    p = p.below()
                }
                st.`is`(BlockTags.LOGS) -> {
                    if (!clear) return p
                    if (removeTree(level, s, p)) continue
                    // wood that isn't a tree (a lantern post, a fallen log, a bare dead tree): it goes with the old
                    // ground, or it's left floating where that ground was (unless it's part of something built)
                    if (!treeLeavesNear(level, p) && !nextToBuild(level, p)) { level.setBlock(p, air, FLAGS); p = p.below(); continue }
                    p = p.below()
                }
                isClutter(st) || st.fluidState.`is`(net.minecraft.tags.FluidTags.LAVA) -> { level.setBlock(p, air, FLAGS); p = p.below() }
                // the sanctuary's own decorations (not a player's build): taken away too, or the old ground under
                // them would be left standing
                st.block in MOD_SOLID && !nextToBuild(level, p) -> { level.setBlock(p, air, FLAGS); p = p.below() }
                // anything a player built (players can't build in the sanctuary): it goes too
                !ValleyBlocks.belongs(st) -> { Trace.removed(st); ValleyBlocks.remove(level, p); p = p.below() }
                // the natural ground (or a lake's water, as always): found
                isNatural(st) && (st.fluidState.isEmpty || st.`is`(Blocks.WATER)) -> return p
                // one of the mod's own blocks a player placed (a bed, a nest…): the column is left as it is
                ValleyBlocks.isMods(st) -> return p
                // anything else in the way (a lamp, a dead coral, an old decoration) goes, so no column is ever left
                // unbuilt (players can't build here, so there's nothing of theirs to keep)
                else -> { Trace.note(x, z, "cleared ${st.block} at ${p.y} to reach the ground"); level.setBlock(p, air, FLAGS); p = p.below() }
            }
        }
        return null
    }

    /**
     * Takes down every tree with a log inside the cylinder (radius [r] around [center], from [y0] to [y1]) as a
     * whole: its logs and the leaves around them. Trees standing outside only reaching in with their leaves stay
     * as they are, so nothing is left sliced in half when an area is cleared.
     */
    fun removeTreesIn(level: ServerLevel, center: BlockPos, r: Int, y0: Int, y1: Int) {
        val logs = LinkedHashSet<BlockPos>()
        val kept = HashSet<BlockPos>()
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            for (y in y0..y1) {
                val p = BlockPos(center.x + dx, y, center.z + dz)
                if (p in logs || p in kept || !level.getBlockState(p).`is`(BlockTags.LOGS)) continue
                // the whole tree joined to this log
                val tree = LinkedHashSet<BlockPos>()
                val queue = ArrayDeque<BlockPos>()
                queue += p; tree += p
                var n = 0
                var leafy = false
                var built = false
                while (queue.isNotEmpty() && n++ < 1500) {
                    val q = queue.removeFirst()
                    for (ox in -1..1) for (oy in -1..1) for (oz in -1..1) {
                        val m = q.offset(ox, oy, oz)
                        val st = level.getBlockState(m)
                        when {
                            st.`is`(BlockTags.LOGS) -> if (m !in tree) { tree += m; queue += m }
                            st.`is`(BlockTags.LEAVES) -> leafy = true
                            // touching planks, glass, a door… that's somebody's build, not a tree
                            !st.isAir && !isNatural(st) && !isClutter(st) && !isCobblemonDecor(st) && st.block !in MOD_SOLID -> built = true
                        }
                    }
                    if (level.getBlockEntity(q) != null) built = true
                }
                // only real trees (with leaves) that aren't part of a build come down
                if (leafy && !built) logs += tree else kept += tree
            }
        }
        if (logs.isEmpty()) return
        val air = b(Blocks.AIR)
        val leaves = HashSet<BlockPos>()
        for (l in logs) for (ox in -4..4) for (oy in -3..4) for (oz in -4..4) {
            val m = l.offset(ox, oy, oz)
            if (m !in leaves && level.getBlockState(m).`is`(BlockTags.LEAVES)) leaves += m
        }
        for (p in (logs + leaves).sortedByDescending { it.y }) level.setBlock(p, air, FLAGS)
    }

    private fun nearbyLog(level: ServerLevel, from: BlockPos): BlockPos? {
        for (r in 1..5) for (dx in -r..r) for (dy in -r..1) for (dz in -r..r) {
            if (max(abs(dx), max(abs(dy), abs(dz))) != r) continue
            val p = from.offset(dx, dy, dz)
            if (level.getBlockState(p).`is`(BlockTags.LOGS)) return p
        }
        return null
    }

    /**
     * Leaves in the cylinder (placed ones too) that no longer touch any wood, with what hangs from them: what's left
     * of a canopy after its tree or landmark was taken down.
     */
    fun removeLooseLeaves(level: ServerLevel, center: BlockPos, r: Int, y0: Int, y1: Int) {
        val air = b(Blocks.AIR)
        val done = HashSet<Long>()
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            for (y in y0..y1) {
                val start = BlockPos(center.x + dx, y, center.z + dz)
                if (start.asLong() in done || !level.getBlockState(start).`is`(BlockTags.LEAVES)) continue
                val part = ArrayList<BlockPos>()
                val queue = ArrayDeque<BlockPos>()
                queue += start; done += start.asLong()
                var wood = false
                while (queue.isNotEmpty() && part.size < 6000) {
                    val q = queue.removeFirst()
                    part += q
                    for (d in Direction.entries) {
                        val n = q.relative(d)
                        if (!level.hasChunk(n.x shr 4, n.z shr 4)) { wood = true; continue }
                        val st = level.getBlockState(n)
                        if (st.`is`(BlockTags.LOGS)) wood = true
                        else if (st.`is`(BlockTags.LEAVES) && done.add(n.asLong())) queue += n
                    }
                }
                if (wood || part.size >= 6000) continue
                for (q in part.sortedByDescending { it.y }) {
                    level.setBlock(q, air, FLAGS)
                    val under = q.below()
                    if (isClutter(level.getBlockState(under)) && !level.getBlockState(under).`is`(BlockTags.FLOWERS)) level.setBlock(under, air, FLAGS)
                }
            }
        }
    }

    /** Is [p] part of a living tree (leaves on or right next to it)? */
    private fun treeLeavesNear(level: ServerLevel, p: BlockPos): Boolean {
        for (dx in -2..2) for (dy in -1..3) for (dz in -2..2) if (level.getBlockState(p.offset(dx, dy, dz)).`is`(BlockTags.LEAVES)) return true
        return false
    }

    /**
     * Something of the valley's own (a post, a lantern, a fence, a lone log, a decoration block) that isn't part of
     * anything built, for the cleanup: plants that grow under water, and anything holding items, aren't.
     */
    fun isLooseDecor(level: ServerLevel, p: BlockPos, st: BlockState): Boolean {
        if (st.isAir || isNatural(st) || !st.fluidState.isEmpty && !st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) return false
        if (st.`is`(Blocks.KELP) || st.`is`(Blocks.KELP_PLANT) || st.`is`(Blocks.SEAGRASS) || st.`is`(Blocks.TALL_SEAGRASS) || st.`is`(Blocks.SEA_PICKLE) ||
            st.`is`(Blocks.LILY_PAD) || st.`is`(BlockTags.CORALS) || st.`is`(BlockTags.CORAL_BLOCKS) || st.`is`(BlockTags.WALL_CORALS) ||
            // the swamp's own: roots in the water, vines hanging into it, dripleaves
            st.`is`(Blocks.VINE) || st.`is`(Blocks.MANGROVE_ROOTS) || st.`is`(Blocks.MUDDY_MANGROVE_ROOTS) || st.`is`(Blocks.MANGROVE_PROPAGULE) ||
            st.`is`(Blocks.HANGING_ROOTS) || st.`is`(Blocks.BIG_DRIPLEAF) || st.`is`(Blocks.BIG_DRIPLEAF_STEM) || st.`is`(Blocks.SMALL_DRIPLEAF)) return false
        if (level.getBlockEntity(p) != null) return false
        val ours = isClutter(st) || st.block in MOD_SOLID || isCobblemonDecor(st) || (st.`is`(BlockTags.LOGS) && !treeLeavesNear(level, p))
        return ours && !nextToBuild(level, p)
    }

    /** Do the leaves joined to [start] touch any wood (followed through up to a few thousand leaves)? */
    private fun leavesReachWood(level: ServerLevel, start: BlockPos): Boolean {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        queue += start; seen += start.asLong()
        while (queue.isNotEmpty()) {
            if (seen.size > 3000) return true
            val q = queue.removeFirst()
            for (d in Direction.entries) {
                val n = q.relative(d)
                if (!seen.add(n.asLong())) continue
                if (!level.hasChunk(n.x shr 4, n.z shr 4)) return true
                val st = level.getBlockState(n)
                if (st.`is`(BlockTags.LOGS)) return true
                if (st.`is`(BlockTags.LEAVES)) queue += n
            }
        }
        return false
    }

    /** Removes a whole tree: logs first, then its leaves and anything hanging from them. */
    private fun removeTree(level: ServerLevel, s: Sanctuary, start: BlockPos): Boolean {
        val r = (s.size.radius + Sanctuaries.RIM).toDouble()
        fun inside(p: BlockPos): Boolean {
            val dx = (p.x - s.center.x).toDouble()
            val dz = (p.z - s.center.z).toDouble()
            return dx * dx + dz * dz <= r * r
        }
        val logs = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        logs += start
        queue += start
        while (queue.isNotEmpty() && logs.size < 2000) {
            val p = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val n = p.offset(dx, dy, dz)
                if (n !in logs && level.getBlockState(n).`is`(BlockTags.LOGS) && inside(n)) { logs += n; queue += n }
            }
        }
        // a tree the sanctuary planted in an already finished column stays
        val lowest = logs.minOf { it.y }
        val limit = ColumnOrder.countWithin(s.size.radius + Sanctuaries.RIM)
        for (base in logs.filter { it.y == lowest }) {
            val idx = ColumnOrder.indexOf(base.x - s.center.x, base.z - s.center.z)
            if (idx in 0 until limit && s.dressed.get(idx)) return false
        }
        val air = b(Blocks.AIR)
        for (p in logs) level.setBlock(p, air, FLAGS)
        val seen = HashSet<BlockPos>(logs)
        var frontier: List<BlockPos> = logs.toList()
        repeat(8) {
            val next = ArrayList<BlockPos>()
            for (p in frontier) for (d in Direction.entries) {
                val n = p.relative(d)
                if (!seen.add(n) || !inside(n)) continue
                val st = level.getBlockState(n)
                val hanging = st.`is`(Blocks.VINE) || st.`is`(Blocks.COCOA) || st.`is`(Blocks.BEE_NEST) || st.`is`(Blocks.MOSS_CARPET) ||
                    st.`is`(Blocks.MANGROVE_PROPAGULE) || st.`is`(Blocks.MANGROVE_ROOTS) || st.`is`(Blocks.MUDDY_MANGROVE_ROOTS)
                if (st.`is`(BlockTags.LEAVES) || hanging) { level.setBlock(n, air, FLAGS); next += n }
            }
            frontier = next
        }
        return true
    }

    // ================================================================== the biomes

    /** Normal hub: a town square with paths, lamp posts, flower beds and big shade trees. */
    private fun hub(c: Ctx) {
        val s = c.s
        val dx = c.x - s.center.x
        val dz = c.z - s.center.z
        val hubR = s.size.hubRadius
        val d = c.hit.dist
        val ringPath = d in (hubR - 7.0)..(hubR - 4.0)
        val spoke = (abs(dx) <= 1 || abs(dz) <= 1) && d > 6 && d < hubR - 3
        when (c.variant) {
            1 -> { kantoHub(c, dx, dz, d, hubR, ringPath, spoke); return }
            2 -> { parkHub(c, dx, dz, d, hubR, ringPath, spoke); return }
        }
        when {
            d <= 6.5 -> { // plaza
                c.ground(
                    when {
                        d < 2.5 -> b(Blocks.CHISELED_STONE_BRICKS)
                        Math.floorMod(dx + dz, 2) == 0 -> b(Blocks.STONE_BRICKS)
                        else -> b(Blocks.POLISHED_ANDESITE)
                    },
                    b(Blocks.STONE),
                )
                if (dx == 0 && dz == 0) c.level.setBlock(c.above, ModContent.SANCTUARY_CORE.get().defaultBlockState(), 3)
                else if (d in 5.5..6.5 && Math.floorMod(dx * 3 + dz * 5, 7) == 0) c.lampPost(b(Blocks.OAK_FENCE), b(Blocks.LANTERN), 3)
            }
            d > hubR + 1 -> meadow(c)
            ringPath || spoke -> {
                val rim = (spoke && (abs(dx) == 1 || abs(dz) == 1)) || (ringPath && (d < hubR - 6.5 || d > hubR - 4.5))
                c.ground(if (rim && c.noise(3.0, 1) > 0.55) b(Blocks.COARSE_DIRT) else b(Blocks.DIRT_PATH), b(Blocks.DIRT))
            }
            else -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                val nearPath = d in (hubR - 8.5)..(hubR - 7.5) || d in (hubR - 3.5)..(hubR - 2.5)
                when {
                    nearPath && Math.floorMod(dx * 7 + dz * 13, 31) == 0 -> c.lampPost(b(Blocks.OAK_FENCE), b(Blocks.LANTERN), 2)
                    !nearPath && d > 11 && d < hubR - 10 && c.grid(15, 3) -> c.feature(TreeFeatures.FANCY_OAK)
                    c.noise(7.0, 2) > 0.72 && c.chance(0.6) -> c.place(c.patch(3.0, 4, b(Blocks.POPPY), b(Blocks.DANDELION), b(Blocks.CORNFLOWER), b(Blocks.ALLIUM), b(Blocks.OXEYE_DAISY)))
                    c.grid(19, 5) && d > 9 -> c.place(b(Blocks.HAY_BLOCK))
                    c.chance(0.25) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
        }
    }

    /** The Core in the middle of the plaza (every town look has it). */
    private fun core(c: Ctx, dx: Int, dz: Int) {
        if (dx == 0 && dz == 0) c.level.setBlock(c.above, ModContent.SANCTUARY_CORE.get().defaultBlockState(), 3)
    }

    /** A stone lantern: a wall post, the lantern, and a stone cap over it. */
    private fun stoneLantern(c: Ctx) {
        c.place(b(Blocks.STONE_BRICK_WALL), 1)
        c.place(b(Blocks.LANTERN), 2)
        c.place(b(Blocks.STONE_BRICK_SLAB), 3)
    }

    /**
     * Old Kanto village: gravel paths with stepping stones, stone lanterns, koi ponds with lily pads, bamboo groves
     * and cherry trees, and a red wooden gate on each path into the plaza.
     */
    private fun kantoHub(c: Ctx, dx: Int, dz: Int, d: Double, hubR: Int, ringPath: Boolean, spoke: Boolean) {
        val gateRing = (abs(dz) == 9 && abs(dx) <= 3) || (abs(dx) == 9 && abs(dz) <= 3)
        when {
            d <= 6.5 -> { // a raked gravel court with a stone centre
                c.ground(if (d < 2.5) b(Blocks.CHISELED_STONE_BRICKS) else if (d > 5.5) b(Blocks.MOSSY_STONE_BRICKS) else b(Blocks.GRAVEL), b(Blocks.STONE))
                core(c, dx, dz)
                if (d in 5.5..6.5 && Math.floorMod(dx * 3 + dz * 5, 7) == 0) stoneLantern(c)
            }
            d > hubR + 1 -> meadow(c)
            ringPath || spoke -> {
                // gravel with stepping stones down the middle
                val middle = if (spoke) (abs(dx) == 0 || abs(dz) == 0) else d in (hubR - 6.0)..(hubR - 5.0)
                c.ground(if (middle && Math.floorMod(dx + dz, 3) == 0) b(Blocks.STONE_BRICKS) else b(Blocks.GRAVEL), b(Blocks.STONE))
                if (gateRing) toriiPart(c, dx, dz)
            }
            else -> {
                // koi ponds
                val pond = c.noise(16.0, 43) > 0.74 && d > 10 && d < hubR - 9
                if (pond) {
                    c.pool(b(Blocks.CLAY))
                    if (c.level.getBlockState(c.surface).`is`(Blocks.WATER) && c.chance(0.12)) c.place(b(Blocks.LILY_PAD), 1)
                    return
                }
                c.ground(if (c.noise(9.0, 44) > 0.7) b(Blocks.MOSS_BLOCK) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                if (gateRing) { toriiPart(c, dx, dz); return }
                val nearPath = d in (hubR - 8.5)..(hubR - 7.5) || d in (hubR - 3.5)..(hubR - 2.5)
                when {
                    nearPath && Math.floorMod(dx * 7 + dz * 13, 23) == 0 -> stoneLantern(c)
                    !nearPath && d > 11 && d < hubR - 10 && c.grid(17, 45) && c.roomy() -> c.feature(TreeFeatures.CHERRY)
                    c.noise(11.0, 46) > 0.72 && !nearPath -> c.column(b(Blocks.BAMBOO), 3 + c.random.nextInt(5))
                    c.grid(13, 47) && c.roomy() -> c.boulder(b(Blocks.MOSSY_COBBLESTONE), b(Blocks.STONE), 1)
                    c.noise(7.0, 48) > 0.74 && c.chance(0.5) -> c.place(c.patch(3.0, 49, b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.PINK_TULIP), b(Blocks.AZURE_BLUET)))
                    c.chance(0.2) -> c.place(if (c.chance(0.3)) b(Blocks.FERN) else b(Blocks.SHORT_GRASS))
                }
            }
        }
    }

    /** One column of a red wooden gate standing across a path into the plaza (two posts, two beams). */
    private fun toriiPart(c: Ctx, dx: Int, dz: Int) {
        val across = if (abs(dz) == 9) abs(dx) else abs(dz)
        val post = b(Blocks.STRIPPED_MANGROVE_LOG)
        val beam = b(Blocks.MANGROVE_PLANKS)
        val top = b(Blocks.DARK_OAK_PLANKS)
        when (across) {
            2 -> { c.column(post, 4); c.place(beam, 5); c.place(top, 6) }
            0, 1 -> { c.place(beam, 4); c.place(top, 6) }
            3 -> c.place(top, 6)
        }
    }

    /**
     * Park & gardens: pale stone walkways with benches along them, low hedges of azalea, borders of tulips,
     * fountains, rows of birches and dark lamp posts.
     */
    private fun parkHub(c: Ctx, dx: Int, dz: Int, d: Double, hubR: Int, ringPath: Boolean, spoke: Boolean) {
        when {
            d <= 6.5 -> { // a paved square with a ring of water round the Core's dais
                when {
                    d < 2.5 -> c.ground(b(Blocks.CHISELED_STONE_BRICKS), b(Blocks.STONE))
                    d < 3.6 -> { c.ground(b(Blocks.STONE_BRICKS), b(Blocks.STONE)); c.pool(b(Blocks.STONE_BRICKS)) }
                    else -> c.ground(if (Math.floorMod(dx + dz, 2) == 0) b(Blocks.POLISHED_DIORITE) else b(Blocks.SMOOTH_STONE), b(Blocks.STONE))
                }
                core(c, dx, dz)
                if (d in 5.5..6.5 && Math.floorMod(dx * 3 + dz * 5, 7) == 0) c.lampPost(b(Blocks.POLISHED_BLACKSTONE_WALL), b(Blocks.LANTERN), 3)
            }
            d > hubR + 1 -> meadow(c)
            ringPath || spoke -> {
                val edge = if (spoke) (abs(dx) == 1 || abs(dz) == 1) else (d < hubR - 6.5 || d > hubR - 4.5)
                c.ground(if (edge) b(Blocks.SMOOTH_STONE) else b(Blocks.POLISHED_DIORITE), b(Blocks.STONE))
            }
            else -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                val hedge = d in (hubR - 8.5)..(hubR - 7.5) || d in (hubR - 3.5)..(hubR - 2.5)
                val border = d in (hubR - 9.5)..(hubR - 8.5) || d in (hubR - 2.5)..(hubR - 1.5)
                // fountains: round basins with a lantern on a pillar in the middle
                val fx = Math.floorMod(dx, 24) - 12
                val fz = Math.floorMod(dz, 24) - 12
                val fd = sqrt((fx * fx + fz * fz).toDouble())
                val fountainHere = d > 12 && d < hubR - 11 && Noise.hash(c.s.seed + 51, Math.floorDiv(dx, 24), Math.floorDiv(dz, 24)) > 0.45
                when {
                    fountainHere && fd < 0.8 -> { c.column(b(Blocks.STONE_BRICKS), 2); c.place(b(Blocks.LANTERN), 3) }
                    fountainHere && fd < 2.6 -> { c.pool(b(Blocks.STONE_BRICKS)) }
                    fountainHere && fd < 3.4 -> c.place(b(Blocks.STONE_BRICK_SLAB))
                    // low hedges along the walkways, with a lamp every so often and gaps (some with a bench)
                    hedge -> when {
                        Math.floorMod(dx * 3 - dz * 7, 17) == 0 -> c.lampPost(b(Blocks.POLISHED_BLACKSTONE_WALL), b(Blocks.LANTERN), 2)
                        Math.floorMod(dx * 5 + dz * 3, 11) == 0 ->
                            if (Math.floorMod(dx + dz, 2) == 0) c.place(b(Blocks.OAK_STAIRS).setValue(net.minecraft.world.level.block.StairBlock.FACING, outward(dx, dz, d < hubR - 5)))
                        else -> c.place(if (c.noise(5.0, 52) > 0.6) b(Blocks.FLOWERING_AZALEA) else b(Blocks.AZALEA))
                    }
                    // lamps down the walkways into the plaza, evenly spaced
                    (abs(dx) == 2 || abs(dz) == 2) && d > 8 && d < hubR - 9 && Math.floorMod(max(abs(dx), abs(dz)), 9) == 0 ->
                        c.lampPost(b(Blocks.POLISHED_BLACKSTONE_WALL), b(Blocks.LANTERN), 2)
                    border -> c.place(c.patch(4.0, 53, b(Blocks.RED_TULIP), b(Blocks.ORANGE_TULIP), b(Blocks.WHITE_TULIP), b(Blocks.PINK_TULIP), b(Blocks.ALLIUM)))
                    // the lawns: groves of oaks and birches, round flower beds edged with azalea, the odd lone tree
                    else -> {
                        val grove = c.noise(22.0, 55)
                        val bed = c.noise(8.0, 57)
                        when {
                            d > 11 && d < hubR - 10 && grove > 0.6 && c.grid(6, 56) && c.roomy() ->
                                c.feature(if (c.noise(30.0, 58) > 0.5) TreeFeatures.FANCY_OAK else TreeFeatures.BIRCH)
                            d > 11 && d < hubR - 10 && grove < 0.5 && bed > 0.8 ->
                                c.place(c.patch(3.0, 59, b(Blocks.RED_TULIP), b(Blocks.PINK_TULIP), b(Blocks.WHITE_TULIP), b(Blocks.CORNFLOWER), b(Blocks.ALLIUM)))
                            d > 11 && d < hubR - 10 && grove < 0.5 && bed > 0.76 -> c.place(b(Blocks.AZALEA))
                            d > 11 && d < hubR - 10 && c.grid(23, 54) && c.roomy() -> c.feature(TreeFeatures.BIRCH)
                            c.chance(0.1) -> c.place(b(Blocks.SHORT_GRASS))
                        }
                    }
                }
            }
        }
    }

    /** Which way a bench at (dx, dz) faces: towards the walkway beside it. */
    private fun outward(dx: Int, dz: Int, inward: Boolean): Direction {
        val d = if (abs(dx) > abs(dz)) (if (dx > 0) Direction.EAST else Direction.WEST) else (if (dz > 0) Direction.SOUTH else Direction.NORTH)
        // a stair's back is on its facing side: inside the ring path the back is towards the town's middle (the seat
        // looks out at the walkway), outside it the other way
        return if (inward) d.opposite else d
    }

    /**
     * The meadows between the habitats: open grassland with tall grass along the routes (where wild Pokémon would
     * hide in the games), clumps of trees, flowers and the odd boulder.
     */
    private fun meadow(c: Ctx) {
        val route = routeDistance(c.s, c.x, c.z)
        c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        val clump = c.noise(26.0, 31)
        when {
            route < 5.5 && c.noise(4.0, 32) > 0.38 -> if (c.chance(0.55)) c.tallPlant(b(Blocks.TALL_GRASS)) else c.place(b(Blocks.SHORT_GRASS))
            route > 7 && clump > 0.66 && c.grid(9, 33) && c.roomy() -> c.feature(if (c.noise(40.0, 34) > 0.5) TreeFeatures.OAK else TreeFeatures.BIRCH)
            route > 7 && c.grid(41, 35) && c.roomy(6) -> c.feature(TreeFeatures.FANCY_OAK)
            route > 4 && c.grid(37, 36) && c.roomy() -> c.boulder(b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COBBLESTONE), 1)
            c.noise(9.0, 37) > 0.74 && c.chance(0.5) -> c.place(c.patch(4.0, 38, b(Blocks.POPPY), b(Blocks.DANDELION), b(Blocks.CORNFLOWER), b(Blocks.OXEYE_DAISY), b(Blocks.AZURE_BLUET)))
            c.noise(14.0, 39) > 0.55 && c.chance(0.35) -> if (c.chance(0.3)) c.tallPlant(b(Blocks.TALL_GRASS)) else c.place(b(Blocks.SHORT_GRASS))
            c.chance(0.12) -> c.place(b(Blocks.SHORT_GRASS))
        }
    }

    /** Lake & coast: a clear lake with beaches and islands, a turquoise lagoon, or a mangrove delta. */
    private fun water(c: Ctx) {
        val depth = WATER_Y - c.y
        if (c.variant == 2) { // rocky coast
            if (depth >= 1) {
                c.flood()
                c.ground(c.broad(c.broad(b(Blocks.GRAVEL), b(Blocks.SAND), 0.55, 18.0, 15), b(Blocks.STONE), 0.7, 12.0, 16), b(Blocks.STONE))
                when {
                    depth >= 3 && c.noise(12.0, 8) > 0.45 && c.chance(0.35) -> if (c.placing) { val h = 1 + c.random.nextInt(depth - 1); for (i in 1 until h) c.level.setBlock(c.surface.above(i), b(Blocks.KELP_PLANT), FLAGS); c.level.setBlock(c.surface.above(h), b(Blocks.KELP), FLAGS) }
                    depth >= 2 && c.chance(0.18) -> c.underwater(b(Blocks.SEAGRASS))
                }
                return
            }
            // beaches of sand and shingle at the water's edge
            if (c.y <= WATER_Y + 1 && !c.cliff) {
                c.ground(c.broad(b(Blocks.SAND), b(Blocks.GRAVEL), 0.55, 14.0, 17), b(Blocks.SANDSTONE))
                if (c.grid(17, 18) && c.roomy()) c.boulder(b(Blocks.STONE), b(Blocks.MOSSY_COBBLESTONE), 1)
                return
            }
            // the cliffs and the stacks show their rock; on top, grass with wind-bent spruces
            c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.ANDESITE))
            if (c.cliff) return
            when {
                c.tree(12, 26, 19) && c.roomy() && c.y > WATER_Y + 3 -> c.feature(TreeFeatures.SPRUCE)
                c.chance(0.22) -> c.place(b(Blocks.SHORT_GRASS))
                c.chance(0.05) -> c.place(b(Blocks.FERN))
                c.noise(11.0, 20) > 0.72 && c.chance(0.15) -> c.place(c.pick(b(Blocks.BLUE_ORCHID), b(Blocks.CORNFLOWER)))
            }
            return
        }
        val lagoon = c.variant == 1
        if (depth >= 1) {
            c.flood()
            val bed = when {
                lagoon || depth <= 2 -> b(Blocks.SAND)
                else -> c.broad(c.broad(b(Blocks.SAND), b(Blocks.GRAVEL), 0.6, 20.0, 4), b(Blocks.CLAY), 0.72, 16.0, 3)
            }
            c.ground(bed, b(Blocks.SAND))
            when {
                lagoon && depth >= 2 && c.chance(0.12) -> c.underwater(c.pick(b(Blocks.BRAIN_CORAL_BLOCK), b(Blocks.TUBE_CORAL_BLOCK), b(Blocks.HORN_CORAL_BLOCK)))
                lagoon && depth >= 2 && c.chance(0.12) -> c.underwater(c.pick(b(Blocks.BRAIN_CORAL), b(Blocks.TUBE_CORAL), b(Blocks.HORN_CORAL), b(Blocks.FIRE_CORAL_FAN)))
                lagoon && c.chance(0.05) -> c.underwater(b(Blocks.SEA_PICKLE))
                depth >= 3 && c.noise(12.0, 8) > 0.55 && c.chance(0.25) -> if (c.placing) { val h = 1 + c.random.nextInt(depth - 1); for (i in 1 until h) c.level.setBlock(c.surface.above(i), b(Blocks.KELP_PLANT), FLAGS); c.level.setBlock(c.surface.above(h), b(Blocks.KELP), FLAGS) }
                depth >= 2 && c.chance(0.16) -> c.underwater(b(Blocks.SEAGRASS))
                depth == 1 && c.noise(10.0, 9) > 0.6 && c.chance(0.2) -> c.waterTop(b(Blocks.LILY_PAD))
            }
            return
        }
        // a beach all along the water, a little wider here and there
        val beach = c.y <= WATER_Y + 1 || (c.y == WATER_Y + 2 && c.noise(14.0, 5) > 0.5 && c.slope <= 1)
        if (beach) {
            c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE))
            when {
                c.chance(0.04) && c.y <= WATER_Y + 1 -> c.column(b(Blocks.SUGAR_CANE), 2 + c.random.nextInt(2))
                lagoon && c.grid(11, 6) && c.roomy() -> c.feature(TreeFeatures.JUNGLE_BUSH)
            }
            return
        }
        c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.GRAVEL))
        if (c.cliff) return
        when {
            c.tree(8, 21, 7) && c.roomy() -> c.feature(if (lagoon) TreeFeatures.JUNGLE_BUSH else if (c.noise(40.0, 10) > 0.5) TreeFeatures.BIRCH else TreeFeatures.OAK)
            c.chance(0.25) -> c.place(b(Blocks.SHORT_GRASS))
            c.noise(11.0, 11) > 0.7 && c.chance(0.2) -> c.place(c.pick(b(Blocks.BLUE_ORCHID), b(Blocks.AZURE_BLUET), b(Blocks.OXEYE_DAISY)))
        }
    }

    /** Desert: golden dunes, red badlands with buttes, or dunes around an oasis. */
    private fun ground(c: Ctx) {
        if (c.variant == 2 && c.y < WATER_Y) { // the oasis
            c.flood()
            c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE))
            if (c.chance(0.15)) c.underwater(b(Blocks.SEAGRASS)) else if (WATER_Y - c.y == 1 && c.chance(0.08)) c.waterTop(b(Blocks.LILY_PAD))
            return
        }
        if (c.variant == 1) {
            val bands = listOf(b(Blocks.TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.TERRACOTTA), b(Blocks.BROWN_TERRACOTTA), b(Blocks.RED_TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.WHITE_TERRACOTTA))
            if (c.slope >= 2) c.bands(bands)
            else { c.top(if (c.rise > 8 && c.noise(18.0, 3) > 0.55) b(Blocks.COARSE_DIRT) else b(Blocks.RED_SAND)); c.filler(b(Blocks.RED_SANDSTONE)) }
            when {
                c.slope <= 1 && c.chance(0.006) -> c.place(b(Blocks.DEAD_BUSH))
                c.slope <= 1 && c.rise <= 3 && c.chance(0.003) -> c.column(b(Blocks.CACTUS), 1 + c.random.nextInt(3))
            }
            return
        }
        // sand, with sandstone only on real cliffs (not on every little step, which drew stripes)
        if (c.cliff) c.ground(b(Blocks.SANDSTONE), b(Blocks.SANDSTONE)) else c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE))
        val (ox, oz) = oasis(c.s)
        val nearOasis = c.variant == 2 && ZoneLayout.distance(c.x, c.z, ox, oz) < 17
        when {
            nearOasis && c.y <= WATER_Y + 2 && c.noise(7.0, 4) > 0.35 -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.grid(6, 5) && c.roomy(3) -> c.feature(TreeFeatures.JUNGLE_BUSH)
                    c.chance(0.4) -> c.place(b(Blocks.SHORT_GRASS))
                    c.chance(0.05) -> c.column(b(Blocks.SUGAR_CANE), 2)
                }
            }
            c.cliff -> {}
            c.grid(37, 1) && c.roomy(8) -> { // fossil ribs half buried in the sand
                for (i in 0..4) {
                    c.at(c.above.east(i), b(Blocks.BONE_BLOCK).setValue(RotatedPillarBlock.AXIS, Direction.Axis.X))
                    if (i % 2 == 0) { c.at(c.above.east(i).north(), b(Blocks.BONE_BLOCK)); c.at(c.above.east(i).north().above(), b(Blocks.BONE_BLOCK)) }
                }
            }
            c.chance(0.004) -> c.column(b(Blocks.CACTUS), 1 + c.random.nextInt(3))
            c.chance(0.006) -> c.place(b(Blocks.DEAD_BUSH))
        }
    }

    /** Cave & mountain: rugged peaks over grassy valleys, mossy (or tuff and dripstone) heights, snow on the tops. */
    private fun rock(c: Ctx) {
        if (Caves.at(c.s, c.x, c.z)?.mouth == true) { c.ground(b(Blocks.GRAVEL), b(Blocks.STONE)); return }
        val tuff = c.variant == 2
        val rock = if (tuff) b(Blocks.TUFF) else b(Blocks.STONE)
        val alt = if (tuff) b(Blocks.DRIPSTONE_BLOCK) else b(Blocks.ANDESITE)
        val snowLine = 30 + (c.noise(20.0, 1) * 6).toInt()
        when {
            c.rise >= snowLine && !c.cliff -> { c.ground(b(Blocks.SNOW_BLOCK), rock); if (c.chance(0.5)) c.place(b(Blocks.SNOW)); return }
            c.rise >= 14 -> {
                // high up: bare rock, with tough grass on the gentler ledges
                if (!c.cliff && c.slope <= 1 && c.noise(16.0, 2) > 0.45) c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                else c.ground(if (c.noise(13.0, 3) > 0.66) alt else c.broad(rock, b(Blocks.GRAVEL), 0.8, 10.0, 4), rock)
            }
            else -> {
                c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), rock, alt)
                // a few broad stony patches in the valleys
                if (!c.cliff && c.noise(26.0, 5) > 0.74) c.ground(c.broad(b(Blocks.GRAVEL), if (c.variant == 1) b(Blocks.MOSS_BLOCK) else rock, 0.5, 8.0, 6), rock)
            }
        }
        if (c.cliff) return
        val onGrass = c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK)
        when {
            // (a vanilla mossy rock hangs over the low side on a slope: this one sits into it)
            c.grid(17, 7) && c.roomy() -> c.boulder(b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COBBLESTONE), 1 + c.random.nextInt(2))
            tuff && c.grid(11, 8) && c.roomy() -> c.crystalCluster(b(Blocks.DRIPSTONE_BLOCK), b(Blocks.POINTED_DRIPSTONE), 2 + c.random.nextInt(3))
            c.variant == 1 && c.grid(10, 9) && c.roomy() -> c.boulder(b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COBBLESTONE), 1)
            onGrass && c.rise < 24 && c.tree(9, 23, 10) && c.roomy(6) -> c.feature(TreeFeatures.SPRUCE)
            onGrass && c.chance(0.22) -> c.place(b(Blocks.SHORT_GRASS))
            onGrass && c.chance(0.012) -> c.place(c.pick(b(Blocks.DANDELION), b(Blocks.AZURE_BLUET)))
        }
    }

    /** Fighting: dojo courtyards with lantern posts, a training ground, or terraced plateaus with acacias. */
    private fun fighting(c: Ctx) {
        when (c.variant) {
            0 -> {
                val cell = 15
                val lx = Math.floorMod(c.x, cell)
                val lz = Math.floorMod(c.z, cell)
                val court = lx in 2..12 && lz in 2..12 && c.roomy(4) && c.exposure <= 3
                val border = lx == 2 || lx == 12 || lz == 2 || lz == 12
                if (court) {
                    c.ground(if (border) b(Blocks.CHISELED_STONE_BRICKS) else if ((lx + lz) % 2 == 0) b(Blocks.STONE_BRICKS) else b(Blocks.POLISHED_ANDESITE), b(Blocks.STONE))
                    if ((lx == 2 || lx == 12) && (lz == 2 || lz == 12)) { c.column(b(Blocks.STRIPPED_SPRUCE_LOG), 3); c.place(b(Blocks.LANTERN), 4) }
                } else {
                    c.ground(c.patch(8.0, 1, b(Blocks.GRASS_BLOCK), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                    when {
                        c.grid(13, 2) && c.roomy() -> c.feature(TreeFeatures.ACACIA)
                        c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
                    }
                }
            }
            1 -> {
                c.ground(c.patch(9.0, 3, b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK), b(Blocks.DIRT_PATH), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                when {
                    c.grid(9, 4) && c.roomy() -> { c.place(b(Blocks.SPRUCE_FENCE)); c.place(b(Blocks.HAY_BLOCK), 2); c.place(b(Blocks.CARVED_PUMPKIN), 3) }
                    c.grid(11, 5) && c.roomy() -> { c.place(b(Blocks.SPRUCE_FENCE)); c.place(b(Blocks.TARGET), 2) }
                    c.grid(16, 6) && c.roomy() -> c.feature(TreeFeatures.ACACIA)
                    c.chance(0.12) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            else -> {
                val cliff = c.exposure > 3
                c.ground(if (cliff) c.patch(4.0, 7, b(Blocks.STONE), b(Blocks.GRANITE)) else c.patch(10.0, 8, b(Blocks.GRASS_BLOCK), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.STONE))
                when {
                    cliff -> {}
                    c.grid(14, 9) && c.roomy() -> c.feature(TreeFeatures.ACACIA)
                    c.grid(22, 10) && c.roomy(8) -> c.ruinWall(b(Blocks.STONE_BRICKS), 4 + c.random.nextInt(4), 3)
                    c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
        }
    }

    /** Volcano: a black (or red, or old grey) cone with lava running down its gullies and a glowing crater. */
    private fun fire(c: Ctx) {
        val hb = c.s.habitats["fire"]
        val big = min(if (hb != null) min(hb.radial, hb.along) else 30.0, 80.0)
        val peak = peakOf(c.s, "fire")
        val dx = c.x - (peak?.first ?: c.x.toDouble())
        val dz = c.z - (peak?.second ?: c.z.toDouble())
        val d = sqrt(dx * dx + dz * dz)
        val crater = (big * 0.14).coerceIn(5.0, 10.0)
        val t = ((d - crater) / (big * 1.25 - crater)).coerceIn(0.0, 1.0)
        val dormant = c.variant == 2
        if (d < crater + 0.5) { // the crater
            when {
                dormant -> { if (c.noise(5.0, 1) > 0.55) c.pool(b(Blocks.SMOOTH_BASALT)) else c.ground(c.broad(b(Blocks.SMOOTH_BASALT), b(Blocks.CALCITE), 0.6, 6.0, 2), b(Blocks.STONE)) }
                d < crater * 0.55 -> c.ground(b(Blocks.MAGMA_BLOCK), b(Blocks.MAGMA_BLOCK))
                else -> c.ground(if (c.noise(4.0, 3) > 0.6) b(Blocks.MAGMA_BLOCK) else b(Blocks.OBSIDIAN), b(Blocks.BLACKSTONE))
            }
            return
        }
        // lava runs down a few of the gullies (cooled to magma)
        val ang = kotlin.math.atan2(dz, dx)
        val flow = abs(Noise.gradient(c.s.seed + 4411, cos(ang) * 6 + d / 30, sin(ang) * 6)) < 0.035 && t < 0.85 && !dormant
        if (flow) { c.ground(b(Blocks.MAGMA_BLOCK), b(Blocks.BLACKSTONE)); return }
        val rock = when (c.variant) { 1 -> b(Blocks.BASALT); 2 -> b(Blocks.TUFF); else -> b(Blocks.BLACKSTONE) }
        val alt = when (c.variant) { 1 -> b(Blocks.BLACKSTONE); 2 -> b(Blocks.SMOOTH_BASALT); else -> b(Blocks.BASALT) }
        when {
            // the foot of the volcano: ash, gravel and dry, dying grass
            t > 0.78 -> c.natural(c.broad(c.broad(b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK), 0.58, 20.0, 4), if (c.variant == 1) b(Blocks.RED_SAND) else b(Blocks.GRAVEL), 0.7, 14.0, 5), b(Blocks.DIRT), rock, alt)
            c.variant == 1 && t > 0.5 -> c.ground(if (c.slope >= 2) b(Blocks.RED_TERRACOTTA) else c.broad(b(Blocks.RED_SAND), b(Blocks.TERRACOTTA), 0.6, 16.0, 6), b(Blocks.RED_SANDSTONE))
            else -> c.ground(c.broad(rock, alt, 0.62, 14.0, 7), rock)
        }
        when {
            c.cliff -> {}
            t > 0.3 && c.grid(15, 8) && c.roomy() -> c.crystalCluster(alt, null, 1 + c.random.nextInt(3))
            t > 0.78 && c.chance(0.02) -> c.place(b(Blocks.DEAD_BUSH))
            t > 0.78 && c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK) && c.chance(0.1) -> c.place(b(Blocks.SHORT_GRASS))
        }
    }

    /** Dragon's den: banded canyon highlands, grey crags with snowy tops, or green plateaus with old ruins. */
    private fun dragon(c: Ctx) {
        when (c.variant) {
            0 -> {
                val bands = listOf(b(Blocks.ORANGE_TERRACOTTA), b(Blocks.TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA), b(Blocks.TERRACOTTA), b(Blocks.LIGHT_GRAY_TERRACOTTA), b(Blocks.RED_TERRACOTTA))
                if (c.slope >= 2) c.bands(bands)
                else { c.top(c.broad(c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.COARSE_DIRT), 0.55, 22.0, 1), b(Blocks.RED_SAND), 0.75, 18.0, 2)); c.filler(b(Blocks.DIRT)) }
            }
            1 -> {
                val snow = c.rise > 30 + c.noise(12.0, 3) * 6
                when {
                    snow && !c.cliff -> { c.ground(b(Blocks.SNOW_BLOCK), b(Blocks.STONE)); if (c.chance(0.4)) c.place(b(Blocks.SNOW)) }
                    else -> c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.CALCITE))
                }
            }
            else -> c.natural(c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.COARSE_DIRT), 0.68, 20.0, 4), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.MOSSY_COBBLESTONE))
        }
        if (c.cliff) return
        val onGrass = c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK)
        when {
            c.variant == 0 && c.grid(31, 5) && c.roomy(10) -> { // a bone arch, the remains of something huge
                val h = 4 + c.random.nextInt(3)
                c.column(b(Blocks.BONE_BLOCK), h)
                for (i in 1..4) c.at(c.surface.above(h).east(i), b(Blocks.BONE_BLOCK).setValue(RotatedPillarBlock.AXIS, Direction.Axis.X))
                for (i in 1..h) c.at(c.surface.above(i).east(5), b(Blocks.BONE_BLOCK))
            }
            c.variant == 2 && c.grid(22, 6) && c.roomy(8) -> c.ruinWall(b(Blocks.MOSSY_STONE_BRICKS), 4 + c.random.nextInt(5), 4)
            onGrass && c.variant == 1 && c.rise < 22 && c.tree(10, 26, 7) && c.roomy() -> c.feature(TreeFeatures.SPRUCE)
            onGrass && c.variant != 1 && c.grid(29, 8) && c.roomy() -> c.feature(TreeFeatures.ACACIA)
            onGrass && c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
            c.chance(0.005) -> c.place(b(Blocks.DEAD_BUSH))
        }
    }

    /** Dark: a deep night forest, a shadowy ravine, or a ruined town in the woods. */
    private fun dark(c: Ctx) {
        if (c.variant == 1) {
            c.ground(c.patch(8.0, 1, b(Blocks.DEEPSLATE), b(Blocks.TUFF), b(Blocks.COBBLED_DEEPSLATE), b(Blocks.BLACKSTONE)), b(Blocks.DEEPSLATE))
            when {
                c.grid(9, 2) && c.roomy() -> c.crystalCluster(b(Blocks.BLACKSTONE), null, 3 + c.random.nextInt(4))
                c.grid(13, 3) && c.roomy() -> c.lampPost(b(Blocks.COBBLED_DEEPSLATE_WALL), b(Blocks.SOUL_LANTERN))
                c.exposure <= 3 && c.chance(0.04) -> c.place(b(Blocks.DEAD_BUSH))
            }
            return
        }
        c.ground(c.patch(9.0, 4, b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.PODZOL), b(Blocks.ROOTED_DIRT)), b(Blocks.DIRT))
        when {
            c.grid(if (c.variant == 0) 6 else 10, 5) && c.roomy() -> c.feature(TreeFeatures.DARK_OAK)
            c.variant == 2 && c.grid(19, 6) && c.roomy(8) -> c.ruinWall(b(Blocks.COBBLESTONE), 4 + c.random.nextInt(4), 4)
            c.variant == 2 && c.grid(15, 7) && c.roomy() -> c.lampPost(b(Blocks.SPRUCE_FENCE), b(Blocks.SOUL_LANTERN))
            c.chance(0.02) -> c.place(c.pick(b(Blocks.BROWN_MUSHROOM), b(Blocks.RED_MUSHROOM)))
            c.chance(0.08) -> c.place(b(Blocks.FERN))
        }
    }

    /** Haunted tower: a gloomy hill with the tower, gnarled woods and an old graveyard. */
    private fun ghost(c: Ctx) {
        // pale grass with broad patches of podzol and bare earth; soul soil only in a few damp hollows
        val soil = c.broad(c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.PODZOL), 0.68, 18.0, 1), b(Blocks.COARSE_DIRT), 0.8, 14.0, 2)
        val hollow = c.slope <= 1 && c.rise <= 2 && c.noise(18.0, 3) > 0.8
        c.natural(if (hollow) b(Blocks.SOUL_SOIL) else soil, b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.COBBLED_DEEPSLATE))
        if (c.cliff) return
        val graveyard = c.noise(34.0, 4) > 0.66 && c.slope <= 1
        when {
            graveyard && Math.floorMod(c.x, 4) == 0 && Math.floorMod(c.z, 5) == 0 && c.roomy() -> c.gravestone()
            !graveyard && c.tree(9, 19, 5) && c.roomy() -> when {
                // dead trees only well inside the haunted habitat (not in the patches mixed into its neighbours)
                c.hit.zone == "ghost" && c.hit.edgeDistance > 18 &&
                    ((c.variant == 1 && c.random.nextInt(2) == 0) || c.random.nextInt(4) == 0) -> c.deadTree(b(Blocks.DARK_OAK_LOG), b(Blocks.DARK_OAK_FENCE))
                else -> c.feature(TreeFeatures.DARK_OAK)
            }
            c.variant == 1 && c.grid(23, 7) && c.roomy() -> c.place(b(Blocks.CARVED_PUMPKIN))
            c.chance(0.004) -> c.place(b(Blocks.COBWEB))
            c.chance(0.02) -> c.place(c.pick(b(Blocks.BROWN_MUSHROOM), b(Blocks.DEAD_BUSH)))
            c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK) && c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
            c.variant == 2 && c.chance(0.006) -> c.place(b(Blocks.WITHER_ROSE))
        }
    }

    /** Swamp: pools of murky water among mud and grass, with swamp oaks (or a toxic bog, or giant mushrooms). */
    private fun poison(c: Ctx) {
        if (c.y < WATER_Y) {
            c.flood()
            c.ground(if (c.variant == 1 && c.noise(10.0, 1) > 0.6) b(Blocks.PURPLE_TERRACOTTA) else b(Blocks.MUD), b(Blocks.MUD))
            when {
                c.chance(0.12) -> c.waterTop(b(Blocks.LILY_PAD))
                c.chance(0.1) -> c.underwater(b(Blocks.SEAGRASS))
            }
            return
        }
        val wet = c.y <= WATER_Y + 1
        val soil = when {
            wet && c.noise(8.0, 2) > 0.35 -> b(Blocks.MUD)
            c.variant == 2 -> c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.MYCELIUM), 0.5, 20.0, 3)
            else -> c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.MUD), 0.74, 14.0, 4)
        }
        c.natural(soil, b(Blocks.MUD), b(Blocks.PACKED_MUD), b(Blocks.STONE))
        if (c.cliff) return
        val onGrass = c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK)
        when {
            c.variant == 2 && c.grid(16, 5) && c.roomy(6) -> c.feature(if (c.random.nextBoolean()) TreeFeatures.HUGE_RED_MUSHROOM else TreeFeatures.HUGE_BROWN_MUSHROOM, 3, 15)
            c.variant != 2 && onGrass && c.tree(8, 18, 6) && c.roomy() -> c.feature(TreeFeatures.SWAMP_OAK)
            wet && c.chance(0.05) -> c.column(b(Blocks.SUGAR_CANE), 2 + c.random.nextInt(2))
            onGrass && c.chance(0.02) -> c.place(b(Blocks.BLUE_ORCHID))
            c.chance(0.02) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
            onGrass && c.chance(0.2) -> c.place(b(Blocks.SHORT_GRASS))
        }
    }

    /** Bug: a buzzing azalea thicket, a field of giant flowers, or a birch wood full of fallen logs. */
    private fun bug(c: Ctx) {
        c.ground(c.patch(10.0, 1, b(Blocks.GRASS_BLOCK), b(Blocks.MOSS_BLOCK), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
        when (c.variant) {
            0 -> when {
                c.grid(7, 2) && c.roomy() -> c.feature(TreeFeatures.AZALEA_TREE)
                c.grid(13, 3) && c.roomy() -> c.fallenLog(b(Blocks.OAK_LOG))
                c.grid(33, 4) && c.roomy() -> { c.column(b(Blocks.OAK_LOG), 2); c.place(b(Blocks.BEE_NEST), 3) }
                c.chance(0.3) -> c.tallPlant(c.pick(b(Blocks.TALL_GRASS), b(Blocks.LARGE_FERN)))
                c.chance(0.3) -> c.place(b(Blocks.SHORT_GRASS))
            }
            1 -> when {
                c.grid(31, 7) && c.roomy() -> { c.column(b(Blocks.OAK_LOG), 2); c.place(b(Blocks.BEE_NEST), 3) }
                c.chance(0.06) -> c.tallPlant(c.patch(12.0, 5, b(Blocks.SUNFLOWER), b(Blocks.LILAC), b(Blocks.ROSE_BUSH), b(Blocks.PEONY)))
                c.chance(0.3) -> c.place(c.patch(6.0, 6, b(Blocks.POPPY), b(Blocks.ALLIUM), b(Blocks.CORNFLOWER), b(Blocks.OXEYE_DAISY), b(Blocks.ORANGE_TULIP), b(Blocks.PINK_TULIP)))
            }
            else -> when {
                c.grid(7, 8) && c.roomy() -> c.feature(TreeFeatures.BIRCH)
                c.grid(11, 9) && c.roomy() -> c.fallenLog(b(Blocks.BIRCH_LOG))
                c.chance(0.25) -> c.place(c.pick(b(Blocks.SHORT_GRASS), b(Blocks.FERN)))
            }
        }
    }

    /** Forest: a jungle on the hills, a mixed oak and birch wood, or an open woodland of big oaks. */
    private fun grass(c: Ctx) {
        // a forest floor: grass, with podzol and moss in the deep woods
        val deep = c.noise(34.0, 510) > 0.45
        val soil = if (deep && c.variant != 2) c.broad(b(Blocks.GRASS_BLOCK), if (c.variant == 0) b(Blocks.MOSS_BLOCK) else b(Blocks.PODZOL), 0.72, 12.0, 1) else b(Blocks.GRASS_BLOCK)
        c.natural(soil, b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.MOSSY_COBBLESTONE))
        if (c.cliff) return
        when (c.variant) {
            0 -> when {
                c.tree(11, 30, 1) && c.roomy(7) -> c.feature(TreeFeatures.MEGA_JUNGLE_TREE)
                c.tree(5, 13, 2) && c.roomy() -> c.feature(TreeFeatures.JUNGLE_TREE_NO_VINE)
                c.tree(5, 9, 3) && c.roomy(3) -> c.feature(TreeFeatures.JUNGLE_BUSH)
                c.noise(9.0, 4) > 0.78 && c.chance(0.5) -> c.column(b(Blocks.BAMBOO), 4 + c.random.nextInt(6))
                c.chance(0.006) -> c.place(b(Blocks.MELON))
                c.chance(0.35) -> c.place(c.pick(b(Blocks.FERN), b(Blocks.SHORT_GRASS)))
            }
            1 -> when {
                c.tree(5, 14, 5) && c.roomy() -> c.feature(
                    when { c.noise(40.0, 6) > 0.62 -> TreeFeatures.BIRCH; c.random.nextInt(4) == 0 -> TreeFeatures.FANCY_OAK; else -> TreeFeatures.OAK },
                )
                deep && c.grid(13, 7) && c.roomy() -> c.fallenLog(b(Blocks.OAK_LOG))
                c.chance(0.25) -> c.place(if (deep) c.pick(b(Blocks.FERN), b(Blocks.SHORT_GRASS)) else b(Blocks.SHORT_GRASS))
                !deep && c.chance(0.03) -> c.place(c.pick(b(Blocks.POPPY), b(Blocks.DANDELION), b(Blocks.LILY_OF_THE_VALLEY)))
            }
            else -> when {
                c.tree(12, 26, 8) && c.roomy() -> c.feature(TreeFeatures.FANCY_OAK)
                c.chance(0.25) -> c.tallPlant(b(Blocks.TALL_GRASS))
                c.noise(12.0, 9) > 0.66 && c.chance(0.3) -> c.place(c.patch(8.0, 10, b(Blocks.CORNFLOWER), b(Blocks.OXEYE_DAISY), b(Blocks.AZURE_BLUET)))
                c.chance(0.3) -> c.place(b(Blocks.SHORT_GRASS))
            }
        }
    }

    /** Flower garden: cherry groves, mossy glades with glowing toadstools, or meadows of tall flowers, with ponds. */
    private fun fairy(c: Ctx) {
        if (c.y < WATER_Y) { // a pond
            c.flood()
            c.ground(b(Blocks.CLAY), b(Blocks.DIRT))
            if (c.chance(0.12)) c.waterTop(b(Blocks.LILY_PAD)) else if (c.chance(0.1)) c.underwater(b(Blocks.SEAGRASS))
            return
        }
        val soil = if (c.variant == 1) c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.MOSS_BLOCK), 0.5, 18.0, 1) else b(Blocks.GRASS_BLOCK)
        c.natural(soil, b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.CALCITE))
        if (c.cliff) return
        val bed = c.noise(10.0, 2) > 0.5 // flowers grow in drifts
        when (c.variant) {
            0 -> when {
                c.tree(8, 19, 3) && c.roomy(6) -> c.feature(TreeFeatures.CHERRY)
                c.noise(7.0, 4) > 0.45 && c.chance(0.6) -> c.place(b(Blocks.PINK_PETALS))
                bed && c.chance(0.25) -> c.place(c.patch(6.0, 5, b(Blocks.PINK_TULIP), b(Blocks.ALLIUM), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.WHITE_TULIP)))
                c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
            }
            1 -> when {
                c.grid(11, 6) && c.roomy() -> { // a glowing toadstool
                    val h = 3 + c.random.nextInt(3)
                    c.column(b(Blocks.MUSHROOM_STEM), h)
                    c.place(b(Blocks.SHROOMLIGHT), h + 1)
                    for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) c.at(c.surface.offset(dx, h + 1, dz), b(Blocks.RED_MUSHROOM_BLOCK))
                    c.place(b(Blocks.RED_MUSHROOM_BLOCK), h + 2)
                }
                c.tree(9, 20, 7) && c.roomy() -> c.feature(TreeFeatures.AZALEA_TREE)
                bed && c.chance(0.2) -> c.place(c.patch(6.0, 8, b(Blocks.ALLIUM), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.AZURE_BLUET)))
                c.chance(0.04) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                c.chance(0.1) -> c.place(b(Blocks.MOSS_CARPET))
            }
            else -> when {
                c.grid(17, 9) && c.roomy() -> c.feature(TreeFeatures.CHERRY)
                bed && c.chance(0.12) -> c.tallPlant(c.patch(9.0, 10, b(Blocks.LILAC), b(Blocks.PEONY), b(Blocks.ROSE_BUSH)))
                bed && c.chance(0.4) -> c.place(c.patch(6.0, 11, b(Blocks.ALLIUM), b(Blocks.PINK_TULIP), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.AZURE_BLUET), b(Blocks.WHITE_TULIP)))
                c.chance(0.2) -> c.place(b(Blocks.SHORT_GRASS))
            }
        }
    }

    /** Psychic: a crystal garden, floating islands, or a temple of the mind. */
    private fun psychic(c: Ctx) {
        when (c.variant) {
            0 -> {
                val crystal = c.noise(8.0, 1) > 0.68
                c.ground(if (crystal) b(Blocks.AMETHYST_BLOCK) else c.patch(12.0, 2, b(Blocks.GRASS_BLOCK), b(Blocks.CALCITE), b(Blocks.GRASS_BLOCK)), b(Blocks.CALCITE))
                when {
                    crystal && c.chance(0.12) -> c.place(c.pick(b(Blocks.AMETHYST_CLUSTER), b(Blocks.LARGE_AMETHYST_BUD)))
                    c.grid(9, 3) && c.roomy() -> c.crystalCluster(b(Blocks.AMETHYST_BLOCK), b(Blocks.AMETHYST_CLUSTER), 2 + c.random.nextInt(4))
                    c.chance(0.06) -> c.place(c.pick(b(Blocks.ALLIUM), b(Blocks.SHORT_GRASS)))
                }
            }
            1 -> {
                c.ground(c.patch(10.0, 4, b(Blocks.GRASS_BLOCK), b(Blocks.WARPED_NYLIUM), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                when {
                    c.grid(17, 5) && c.roomy(8) -> c.floatingIsland(c.pick(b(Blocks.CALCITE), b(Blocks.PURPUR_BLOCK)), b(Blocks.GRASS_BLOCK), 2 + c.random.nextInt(2), 9 + c.random.nextInt(8))
                    c.grid(8, 6) && c.roomy() -> c.place(b(Blocks.END_ROD))
                    c.chance(0.05) -> c.place(c.pick(b(Blocks.WARPED_ROOTS), b(Blocks.SHORT_GRASS)))
                }
            }
            else -> {
                val cell = 13
                val lx = Math.floorMod(c.x, cell)
                val lz = Math.floorMod(c.z, cell)
                val floor = lx in 2..10 && lz in 2..10 && c.roomy(4) && c.exposure <= 3
                c.ground(if (floor) (if ((lx + lz) % 2 == 0) b(Blocks.QUARTZ_BLOCK) else b(Blocks.PURPUR_BLOCK)) else c.patch(10.0, 7, b(Blocks.GRASS_BLOCK), b(Blocks.CALCITE)), b(Blocks.CALCITE))
                if (floor && (lx == 2 || lx == 10) && (lz == 2 || lz == 10)) { c.column(b(Blocks.PURPUR_PILLAR), 4); c.place(b(Blocks.END_ROD), 5) }
                else if (!floor && c.chance(0.06)) c.place(b(Blocks.SHORT_GRASS))
            }
        }
    }

    /** Steel: a scrapyard, an old factory yard with chimneys, or iron-grey ridges. */
    private fun steel(c: Ctx) {
        val steep = c.exposure > 4
        when (c.variant) {
            0 -> {
                c.ground(if (c.noise(5.0, 11) > 0.86) b(Blocks.WEATHERED_COPPER) else c.patch(14.0, 1, b(Blocks.GRAVEL), b(Blocks.POLISHED_ANDESITE), b(Blocks.STONE)), b(Blocks.STONE))
                when {
                    c.grid(8, 2) && c.roomy() -> c.boulder(c.pick(b(Blocks.POLISHED_ANDESITE), b(Blocks.SMOOTH_STONE)), b(Blocks.IRON_BARS))
                    c.grid(11, 3) && c.roomy() -> c.place(c.pick(b(Blocks.CHIPPED_ANVIL), b(Blocks.DAMAGED_ANVIL)))
                    c.chance(0.01) -> c.place(b(Blocks.IRON_BARS))
                }
            }
            1 -> {
                c.ground(if (Math.floorMod(c.x, 8) == 0 || Math.floorMod(c.z, 8) == 0) b(Blocks.POLISHED_ANDESITE) else b(Blocks.SMOOTH_STONE), b(Blocks.STONE))
                when {
                    c.grid(20, 4) && c.roomy(8) -> { for (dx in 0..1) for (dz in 0..1) for (i in 1..9) c.at(c.surface.offset(dx, i, dz), b(Blocks.BRICKS)) }
                    c.grid(9, 5) && c.roomy() -> c.column(b(Blocks.IRON_BARS), 3)
                    c.grid(13, 6) && c.roomy() -> c.lampPost(b(Blocks.IRON_BARS), b(Blocks.LANTERN), 3)
                }
            }
            else -> {
                c.ground(when {
                    steep -> c.patch(16.0, 6, b(Blocks.STONE), b(Blocks.ANDESITE))
                    c.noise(5.0, 12) > 0.87 -> b(Blocks.OXIDIZED_COPPER)
                    else -> c.patch(16.0, 7, b(Blocks.STONE), b(Blocks.GRAVEL), b(Blocks.POLISHED_ANDESITE))
                }, b(Blocks.STONE))
                if (c.grid(11, 8) && c.roomy()) c.crystalCluster(b(Blocks.POLISHED_ANDESITE), b(Blocks.LIGHTNING_ROD), 2 + c.random.nextInt(3))
            }
        }
    }

    /** Power plant: open grassy plains with lightning rods, a paved works yard, or rocky hills with copper veins. */
    private fun electric(c: Ctx) {
        when (c.variant) {
            1 -> {
                // the works: grass with a few broad paved yards and gravel lots
                val yard = c.noise(30.0, 1) > 0.66 && c.slope <= 1
                if (yard) c.ground(c.broad(b(Blocks.SMOOTH_STONE), b(Blocks.GRAVEL), 0.55, 9.0, 2), b(Blocks.STONE))
                else c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.ANDESITE))
                if (c.cliff) return
                when {
                    yard && c.grid(11, 3) && c.roomy() -> { c.column(b(Blocks.IRON_BARS), 4); c.place(b(Blocks.END_ROD), 5) }
                    yard && c.grid(9, 4) && c.roomy() -> c.place(b(Blocks.REDSTONE_LAMP))
                    !yard && c.chance(0.22) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            2 -> {
                val vein = abs(c.noise(22.0, 5) - 0.5) < 0.025
                if (vein && c.rise > 3) c.ground(b(Blocks.WEATHERED_COPPER), b(Blocks.STONE))
                else c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.TUFF))
                if (c.cliff) return
                when {
                    c.grid(12, 6) && c.roomy() -> c.crystalCluster(b(Blocks.EXPOSED_COPPER), b(Blocks.LIGHTNING_ROD), 2 + c.random.nextInt(3))
                    c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            else -> {
                c.natural(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT), b(Blocks.STONE), b(Blocks.ANDESITE))
                if (c.cliff) return
                when {
                    c.grid(17, 7) && c.roomy() -> { c.column(b(Blocks.POLISHED_ANDESITE), 3); c.place(b(Blocks.LIGHTNING_ROD), 4) }
                    c.grid(23, 8) && c.roomy() -> c.boulder(b(Blocks.STONE), b(Blocks.WEATHERED_COPPER), 1)
                    c.chance(0.3) -> c.place(b(Blocks.SHORT_GRASS))
                    c.noise(12.0, 9) > 0.72 && c.chance(0.3) -> c.place(b(Blocks.DANDELION))
                }
            }
        }
    }

    /** Snowy peak: a mountain under snow, spruce forest on its lower slopes, ice spikes or frozen tarns. */
    private fun ice(c: Ctx) {
        if (c.y < WATER_Y) { // a frozen tarn: ice on top, water under it
            c.ground(b(Blocks.GRAVEL), b(Blocks.STONE))
            for (yy in c.y + 1 until WATER_Y) c.level.setBlock(BlockPos(c.x, yy, c.z), b(Blocks.WATER), FLAGS)
            c.level.setBlock(BlockPos(c.x, WATER_Y, c.z), b(Blocks.ICE), FLAGS)
            return
        }
        if (c.cliff) {
            // cliffs show their rock, with ice in the cracks
            c.ground(if (c.noise(9.0, 1) > 0.7) b(Blocks.PACKED_ICE) else b(Blocks.STONE), b(Blocks.STONE))
            return
        }
        c.ground(if (c.slope == 2 && c.noise(9.0, 2) > 0.62) b(Blocks.STONE) else b(Blocks.SNOW_BLOCK), if (c.rise > 6) b(Blocks.STONE) else b(Blocks.DIRT))
        val forest = c.rise < (if (c.variant == 1) 22 else 14) + c.noise(16.0, 3) * 5
        when {
            c.variant == 0 && c.rise > 10 && c.grid(19, 4) && c.roomy(8) && settled(c.level, c.s, c.x, c.z, 4) ->
                c.spike(b(Blocks.PACKED_ICE), 1 + c.random.nextInt(3), 7 + c.random.nextInt(14))
            forest && c.tree(if (c.variant == 1) 6 else 9, 22, 5) && c.roomy() -> c.feature(TreeFeatures.SPRUCE)
            c.grid(21, 6) && c.roomy() -> c.boulder(b(Blocks.PACKED_ICE), b(Blocks.STONE), 1)
            c.chance(0.55) -> c.place(b(Blocks.SNOW))
        }
    }

    /**
     * A pocket of another habitat: a pond, a rocky outcrop, a snowy crag, a patch of flowers, a little grove, a bog,
     * a dry sandy patch, a hot vent, a few old graves, a lightning rod or two. [d] is how far in the column is.
     */
    private fun pocket(c: Ctx, p: ZoneLayout.Pocket, d: Double) {
        val inner = d < p.r * 0.72
        val core = d < p.r * 0.4
        val host = p.host
        fun hostSoil() = when (host) {
            "ground" -> b(Blocks.SAND)
            "ice" -> b(Blocks.SNOW_BLOCK)
            "fire" -> b(Blocks.COARSE_DIRT)
            "rock", "dragon" -> if (c.rise > 14) b(Blocks.STONE) else b(Blocks.GRASS_BLOCK)
            else -> b(Blocks.GRASS_BLOCK)
        }
        when (p.zone) {
            "water", "poison" -> {
                val bog = p.zone == "poison"
                val level = p.level(c.s)
                // only where the ground (or more of the pond) holds the water in on every side
                val held = inner && c.y < level && Direction.Plane.HORIZONTAL.all { d ->
                    val nx = c.x + d.stepX
                    val nz = c.z + d.stepZ
                    pondLevel(c.s, nx, nz) == level || targetHeight(c.s, nx, nz, ZoneLayout.HABITATS) >= level
                }
                if (held) {
                    for (yy in c.y + 1..level) c.level.setBlock(BlockPos(c.x, yy, c.z), b(Blocks.WATER), FLAGS)
                    c.ground(if (bog) b(Blocks.MUD) else if (host == "ground") b(Blocks.SAND) else b(Blocks.CLAY), b(Blocks.DIRT))
                    val top = BlockPos(c.x, level + 1, c.z)
                    when {
                        c.chance(0.1) -> if (c.placing && c.level.getBlockState(top).isAir) c.level.setBlock(top, b(Blocks.LILY_PAD), FLAGS)
                        c.chance(0.15) -> c.underwater(b(Blocks.SEAGRASS))
                    }
                    return
                }
                // the banks
                val bank = when {
                    host == "ground" -> b(Blocks.GRASS_BLOCK) // an oasis
                    bog -> if (c.noise(6.0, 1) > 0.45) b(Blocks.MUD) else b(Blocks.GRASS_BLOCK)
                    host == "ice" -> b(Blocks.SNOW_BLOCK)
                    else -> if (d < p.r * 0.9 && c.noise(5.0, 2) > 0.6) b(Blocks.SAND) else hostSoil()
                }
                c.ground(bank, b(Blocks.DIRT))
                val onGrass = bank.`is`(Blocks.GRASS_BLOCK)
                when {
                    d < p.r * 0.95 && c.chance(0.07) && onGrass -> c.column(b(Blocks.SUGAR_CANE), 1 + c.random.nextInt(2))
                    bog && onGrass && c.grid(9, 3) && c.roomy() -> c.feature(TreeFeatures.SWAMP_OAK)
                    host == "ground" && c.grid(7, 4) && c.roomy(3) -> c.feature(TreeFeatures.JUNGLE_BUSH)
                    onGrass && c.chance(0.3) -> c.place(if (bog) b(Blocks.BLUE_ORCHID).takeIf { c.chance(0.15) } ?: b(Blocks.SHORT_GRASS) else b(Blocks.SHORT_GRASS))
                }
            }
            "rock", "dragon" -> {
                val rock = if (p.zone == "dragon") b(Blocks.TERRACOTTA) else b(Blocks.STONE)
                val alt = if (p.zone == "dragon") b(Blocks.STONE) else b(Blocks.ANDESITE)
                if (inner || c.slope >= 2) c.ground(c.broad(rock, if (host == "grass" || host == "ghost") b(Blocks.MOSSY_COBBLESTONE) else alt, 0.6, 5.0, 5), rock)
                else c.ground(c.broad(hostSoil(), b(Blocks.GRAVEL), 0.65, 6.0, 6), b(Blocks.DIRT))
                when {
                    core && c.grid(5, 7) -> c.crystalCluster(rock, null, if (p.zone == "dragon") 3 + c.random.nextInt(4) else 1 + c.random.nextInt(2))
                    !inner && c.chance(0.15) && c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            "ice" -> {
                c.ground(if (inner && c.slope >= 2) b(Blocks.PACKED_ICE) else b(Blocks.SNOW_BLOCK), b(Blocks.STONE))
                when {
                    core && c.grid(6, 8) -> c.crystalCluster(b(Blocks.PACKED_ICE), null, 2 + c.random.nextInt(3))
                    c.grid(7, 9) && c.roomy() && !core -> c.feature(TreeFeatures.SPRUCE)
                    c.chance(0.6) -> c.place(b(Blocks.SNOW))
                }
            }
            "fire" -> {
                c.ground(when {
                    core && c.noise(3.0, 10) > 0.55 -> b(Blocks.MAGMA_BLOCK)
                    inner -> c.broad(b(Blocks.BLACKSTONE), b(Blocks.BASALT), 0.5, 4.0, 11)
                    else -> c.broad(b(Blocks.COARSE_DIRT), b(Blocks.GRAVEL), 0.5, 5.0, 12)
                }, b(Blocks.BLACKSTONE))
                if (inner && !core && c.grid(5, 13)) c.crystalCluster(b(Blocks.BASALT), null, 1 + c.random.nextInt(2))
            }
            "ground" -> {
                c.ground(if (inner) c.broad(b(Blocks.SAND), b(Blocks.COARSE_DIRT), 0.62, 6.0, 14) else c.broad(hostSoil(), b(Blocks.COARSE_DIRT), 0.5, 5.0, 15), b(Blocks.SANDSTONE))
                when {
                    inner && c.chance(0.02) -> c.place(b(Blocks.DEAD_BUSH))
                    inner && c.chance(0.006) -> c.column(b(Blocks.CACTUS), 1 + c.random.nextInt(2))
                }
            }
            "grass" -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.grid(5, 17) && c.roomy(3) -> c.feature(if (c.noise(20.0, 18) > 0.5) TreeFeatures.OAK else TreeFeatures.BIRCH)
                    c.chance(0.35) -> c.place(c.pick(b(Blocks.FERN), b(Blocks.SHORT_GRASS)))
                }
            }
            "fairy" -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    core && c.grid(9, 19) && c.roomy(4) -> c.feature(TreeFeatures.CHERRY)
                    inner && c.chance(0.5) -> c.place(c.patch(4.0, 20, b(Blocks.ALLIUM), b(Blocks.PINK_TULIP), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.AZURE_BLUET), b(Blocks.PEONY).let { b(Blocks.WHITE_TULIP) }))
                    c.chance(0.25) -> c.place(b(Blocks.PINK_PETALS))
                }
            }
            "ghost" -> {
                c.ground(c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.PODZOL), 0.62, 5.0, 21), b(Blocks.DIRT))
                when {
                    inner && Math.floorMod(c.x, 3) == 0 && Math.floorMod(c.z, 4) == 0 -> c.gravestone()
                    core && c.grid(8, 22) && c.roomy() -> c.feature(TreeFeatures.DARK_OAK)
                    c.chance(0.03) -> c.place(b(Blocks.BROWN_MUSHROOM))
                }
            }
            "electric" -> {
                c.ground(if (inner) c.broad(b(Blocks.GRASS_BLOCK), b(Blocks.GRAVEL), 0.55, 4.0, 23) else hostSoil(), b(Blocks.DIRT))
                when {
                    core && c.grid(6, 24) -> { c.column(b(Blocks.POLISHED_ANDESITE), 2); c.place(b(Blocks.LIGHTNING_ROD), 3) }
                    inner && c.grid(5, 25) -> c.boulder(b(Blocks.WEATHERED_COPPER), b(Blocks.STONE), 1)
                    c.chance(0.2) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            else -> if (CustomTypes.isCustom(p.zone)) custom(c, p.zone, (d / p.r).coerceIn(0.0, 1.0), big = p.sub) else c.ground(hostSoil(), b(Blocks.DIRT))
        }
    }

    /**
     * A custom type's own habitat (Gravelmon's, Lost Lore's…), built from a palette that suits it: Shadow from
     * blackstone and soul soil with dark oaks and crying obsidian; Crystal from calcite with amethyst; Cosmic from end
     * stone with chorus and end rods; Light from bright grass and quartz with white flowers; and so on. Types it
     * doesn't know get the palette closest to their colour.
     */
    private fun custom(c: Ctx, zone: String, inPocket: Double? = null, big: Boolean = false) {
        val p = CustomTypes.palette(zone)
        val look = CustomTypes.look(c.s, zone)
        // a pocket is dressed in small; a sub-biome like a habitat
        val small = inPocket != null && !big
        // how far out it is: 0 in the middle, 1 at the edge (of the habitat, or of the pocket)
        val q = inPocket ?: ZoneLayout.local(c.s, zone, c.x, c.z)?.q ?: 0.5
        val top = b(look.top ?: p.top)
        val patch = b(look.patch ?: p.patch)
        val onGridX = Math.floorMod(c.x, 8) == 0
        val onGridZ = Math.floorMod(c.z, 8) == 0
        var soil = c.broad(top, patch, 0.6, if (small) 5.0 else 10.0, 61)
        if (look.grid) soil = when {
            onGridX && onGridZ && p.glow == Blocks.SEA_LANTERN -> b(Blocks.SEA_LANTERN)
            onGridX || onGridZ -> patch
            else -> top
        }
        // pools of slime or amethyst in the low middle
        val poolReach = if (look.terrain == CustomTypes.Terrain.MARSH) 0.6 else 0.32
        if (look.pools != null && q < poolReach && c.slope < 2 && c.noise(6.0, 67) > 0.42) soil = b(look.pools)
        c.natural(soil, b(p.filler), b(p.rock), patch)
        if (c.cliff) return
        fun room(margin: Int) = if (small) q < 0.65 else if (inPocket != null) q < 0.85 && c.roomy(margin) else c.roomy(margin)
        val tip = p.glow?.let { b(it) }?.takeIf { it.`is`(Blocks.AMETHYST_CLUSTER) || it.`is`(Blocks.END_ROD) }
        val rim = look.terrain == CustomTypes.Terrain.HOLLOW || look.terrain == CustomTypes.Terrain.CRATER
        val spires = look.spires?.let { b(it) }
        val towers = look.grid && look.terrain == CustomTypes.Terrain.SPIRES
        val treeKind = look.tree ?: p.tree
        when {
            // data towers: tall columns where the grid lines cross, lit on top
            towers && spires != null -> if (Math.floorMod(c.x, 16) == 0 && Math.floorMod(c.z, 16) == 0 && room(6))
                c.spike(spires, 0, if (small) 3 else 5 + c.random.nextInt(6), b(Blocks.SEA_LANTERN))
            // spires: all over a spire plain, or around a hollow's rim
            spires != null && (look.terrain == CustomTypes.Terrain.SPIRES || (rim && q in 0.45..0.72)) && c.grid(if (small) 6 else 8, 62) && room(8) ->
                c.spike(spires, 1, if (small) 3 + c.random.nextInt(3) else 5 + c.random.nextInt(5))
            look.boulders != null && c.grid(if (small) 6 else 13, 69) && room(6) -> {
                // (toy blocks come in all colours)
                val main = if (look.boulders == Blocks.RED_CONCRETE) b(listOf(Blocks.RED_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.LIME_CONCRETE)[c.random.nextInt(4)])
                    else b(look.boulders)
                c.boulder(main, if (look.boulders == Blocks.RED_CONCRETE) main else b(p.rock), 1)
            }
            // outcrops stay low (a block or two, with a tip on top for amethyst and end rods)
            look.outcrops && c.grid(if (small) 7 else if (rim) 8 else 12, 63) && room(8) -> c.crystalCluster(b(p.crystal), tip, 1 + c.random.nextInt(2))
            look.trees > 0 && treeKind != null && (if (look.trees == 2) c.tree(5, 9, 64) else c.tree(9, 18, 64)) && room(5) -> c.feature(when (treeKind) {
                "dark_oak" -> TreeFeatures.DARK_OAK
                "swamp_oak" -> TreeFeatures.SWAMP_OAK
                "azalea" -> TreeFeatures.AZALEA_TREE
                else -> TreeFeatures.BIRCH
            })
            p.glow != null && look.glow > 0 && !look.grid && c.grid((15 / look.glow).toInt().coerceAtLeast(5), 65) -> c.place(b(p.glow))
            p.plants.isNotEmpty() && c.noise(9.0, 66) > 0.55 - look.plants * 0.3 && c.chance(look.plants) -> c.place(b(p.plants[c.random.nextInt(p.plants.size)]))
            c.level.getBlockState(c.surface).`is`(Blocks.GRASS_BLOCK) && c.chance(0.1 + look.plants * 0.3) ->
                c.place(b(if (look.plants > 0.5 && c.chance(0.4)) Blocks.FERN else Blocks.SHORT_GRASS))
            c.level.getBlockState(c.surface).`is`(Blocks.MOSS_BLOCK) && look.tree == "azalea" && c.chance(0.04) -> c.place(b(Blocks.BIG_DRIPLEAF))
        }
    }
}

/** Small deterministic value noise in [0, 1). */
object Noise {
    fun hash(seed: Long, x: Int, z: Int): Double {
        var h = seed xor (x.toLong() * 0x27d4eb2dL) xor (z.toLong() * 0x165667b1L)
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    /** Smooth value noise in [0, 1) (used for the biome layout). */
    fun value(seed: Long, x: Double, z: Double): Double {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = smooth(x - x0)
        val fz = smooth(z - z0)
        val a = hash(seed, x0, z0)
        val b = hash(seed, x0 + 1, z0)
        val c = hash(seed, x0, z0 + 1)
        val d = hash(seed, x0 + 1, z0 + 1)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fz
    }

    private val GX = DoubleArray(16) { kotlin.math.cos(it * Math.PI / 8) }
    private val GZ = DoubleArray(16) { kotlin.math.sin(it * Math.PI / 8) }

    /** Gradient (Perlin-style) noise in about [-1, 1]: rounder, more natural shapes than value noise. */
    fun gradient(seed: Long, x: Double, z: Double): Double {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = x - x0
        val fz = z - z0
        fun g(ix: Int, iz: Int, dx: Double, dz: Double): Double {
            val i = (hash(seed, ix, iz) * 16).toInt() and 15
            return GX[i] * dx + GZ[i] * dz
        }
        val u = fade(fx)
        val v = fade(fz)
        val a = g(x0, z0, fx, fz)
        val b = g(x0 + 1, z0, fx - 1, fz)
        val c = g(x0, z0 + 1, fx, fz - 1)
        val d = g(x0 + 1, z0 + 1, fx - 1, fz - 1)
        val top = a + (b - a) * u
        val bottom = c + (d - c) * u
        return ((top + (bottom - top) * v) * 1.41).coerceIn(-1.0, 1.0)
    }

    /** Fractal noise: several layers of detail, in about [-1, 1]. */
    fun fbm(seed: Long, x: Double, z: Double, octaves: Int = 4): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fz = z
        for (o in 0 until octaves) {
            sum += gradient(seed + o * 131L, fx, fz) * amp
            norm += amp
            amp *= 0.5
            fx *= 2.03
            fz *= 2.03
        }
        return sum / norm
    }

    /** Ridged fractal noise in [0, 1]: sharp crests, for mountain ridges and canyons. */
    fun ridged(seed: Long, x: Double, z: Double, octaves: Int = 4): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fz = z
        for (o in 0 until octaves) {
            val r = 1 - kotlin.math.abs(gradient(seed + o * 173L, fx, fz))
            sum += r * r * amp
            norm += amp
            amp *= 0.5
            fx *= 2.07
            fz *= 2.07
        }
        return sum / norm
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)
    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
}
