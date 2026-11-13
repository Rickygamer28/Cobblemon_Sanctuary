package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.features.MiscOverworldFeatures
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.data.worldgen.features.VegetationFeatures
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import kotlin.math.abs
import kotlin.math.floor

/**
 * Turns one terrain column into the look of its biome. Natural ground, plants and trees are reshaped;
 * anything a player built is left alone.
 */
object BiomeDresser {
    private const val FLAGS = Block.UPDATE_CLIENTS

    private fun b(block: Block): BlockState = block.defaultBlockState()

    // ------------------------------------------------------------------ context + helpers

    class Ctx(
        val level: ServerLevel,
        val s: Sanctuary,
        val surface: BlockPos,
        val random: RandomSource,
        val variant: Int,
        val hit: ZoneLayout.Hit,
    ) {
        val above: BlockPos get() = surface.above()
        val x get() = surface.x
        val z get() = surface.z
        fun chance(p: Double) = random.nextDouble() < p
        fun noise(scale: Double, salt: Int = 0) = Noise.value(s.seed + salt * 7919L, x / scale, z / scale)

        fun top(state: BlockState) { level.setBlock(surface, state, FLAGS) }
        fun filler(state: BlockState, depth: Int = 3) {
            for (i in 1..depth) {
                val p = surface.below(i)
                if (isNatural(level.getBlockState(p))) level.setBlock(p, state, FLAGS)
            }
        }
        fun ground(top: BlockState, filler: BlockState = top) { top(top); filler(filler) }
        fun at(pos: BlockPos, state: BlockState) {
            val cur = level.getBlockState(pos)
            if (cur.isAir || (cur.canBeReplaced() && cur.fluidState.isEmpty)) level.setBlock(pos, state, FLAGS)
        }
        fun place(state: BlockState, offset: Int = 1) = at(surface.above(offset), state)
        fun column(state: BlockState, height: Int) { for (i in 1..height) place(state, i) }
        fun pick(vararg states: BlockState): BlockState = states[random.nextInt(states.size)]
        fun feature(key: ResourceKey<ConfiguredFeature<*, *>>) {
            runCatching {
                level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent {
                    it.value().place(level, level.chunkSource.generator, random, above)
                }
            }
        }
        /** Keeps big decorations away from the sanctuary's edge and from biome borders. */
        fun roomy(margin: Int = 4) = hit.dist < s.size.radius - margin && (hit.zone == ZoneLayout.HUB || hit.edgeDistance > 2.5)

        // ---- little structures
        fun boulder(main: BlockState, accent: BlockState, size: Int = 1) {
            for (dx in -size..size) for (dz in -size..size) for (dy in 0..size) {
                if (dx * dx + dz * dz + dy * dy > size * size + 1) continue
                at(above.offset(dx, dy, dz), if (random.nextInt(4) == 0) accent else main)
            }
        }
        fun spike(state: BlockState, height: Int) {
            column(state, height)
            if (height >= 3) for (d in Direction.Plane.HORIZONTAL) if (random.nextBoolean()) at(above.relative(d), state)
        }
        fun lampPost(post: BlockState, lantern: BlockState) {
            column(post, 2)
            place(lantern.trySetValue(LanternBlock.HANGING, false), 3)
        }
        fun gravestone() {
            place(pick(b(Blocks.COBBLESTONE_WALL), b(Blocks.MOSSY_COBBLESTONE_WALL), b(Blocks.STONE_BRICK_WALL), b(Blocks.MOSSY_STONE_BRICK_WALL)))
            if (chance(0.4)) place(b(Blocks.STONE_BUTTON), 2)
        }
        fun hollowLog(log: BlockState) {
            val axis = if (random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z
            val len = 3 + random.nextInt(3)
            val state = log.trySetValue(RotatedPillarBlock.AXIS, axis)
            for (i in 0 until len) {
                val p = if (axis == Direction.Axis.X) above.east(i) else above.south(i)
                at(p, state)
                if (random.nextInt(3) == 0) at(p.above(), b(Blocks.MOSS_CARPET))
            }
        }
        fun ruinWall(state: BlockState, length: Int, height: Int) {
            val dir = if (random.nextBoolean()) Direction.EAST else Direction.SOUTH
            for (i in 0 until length) {
                val h = (height - random.nextInt(height)).coerceAtLeast(1)
                for (y in 1..h) at(surface.relative(dir, i).above(y), if (random.nextInt(4) == 0) b(Blocks.CRACKED_STONE_BRICKS) else state)
            }
        }
        fun slab(state: BlockState, offset: Int = 1) = place(state.trySetValue(SlabBlock.TYPE, SlabType.BOTTOM), offset)
        fun tallPlant(state: BlockState) {
            val lower = above
            val upper = lower.above()
            if (level.getBlockState(lower).isAir && level.getBlockState(upper).isAir) {
                level.setBlock(lower, state.trySetValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), FLAGS)
                level.setBlock(upper, state.trySetValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), FLAGS)
            }
        }
    }

    // ------------------------------------------------------------------ what counts as ground / clutter

    private val GROUND: Set<Block> = setOf(
        Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
        Blocks.PACKED_ICE, Blocks.ICE, Blocks.BLUE_ICE, Blocks.CALCITE, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.POLISHED_BASALT,
        Blocks.SOUL_SOIL, Blocks.SOUL_SAND, Blocks.AMETHYST_BLOCK, Blocks.PACKED_MUD, Blocks.MUD, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.DIRT_PATH, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
        Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES,
        Blocks.POLISHED_DEEPSLATE, Blocks.MOSS_BLOCK, Blocks.MUD_BRICKS, Blocks.PURPUR_BLOCK, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ,
        Blocks.BONE_BLOCK, Blocks.MAGMA_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.ROOTED_DIRT, Blocks.COARSE_DIRT,
        Blocks.WARPED_NYLIUM, Blocks.WARPED_WART_BLOCK, Blocks.TUFF, Blocks.POLISHED_TUFF, Blocks.TUFF_BRICKS,
        Blocks.PRISMARINE, Blocks.BRAIN_CORAL_BLOCK, Blocks.TUBE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK,
        Blocks.BUBBLE_CORAL_BLOCK, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER, Blocks.DRIPSTONE_BLOCK,
    )

    fun isNatural(state: BlockState): Boolean =
        state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
            state.`is`(BlockTags.TERRACOTTA) || state.block in GROUND || state.`is`(BlockTags.CONCRETE_POWDER)

    private val CLUTTER: Set<Block> = setOf(
        Blocks.SNOW, Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.COCOA, Blocks.BEE_NEST, Blocks.SWEET_BERRY_BUSH, Blocks.PUMPKIN, Blocks.MELON,
        Blocks.BAMBOO, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF, Blocks.AZALEA,
        Blocks.FLOWERING_AZALEA, Blocks.MOSS_CARPET, Blocks.PINK_PETALS, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK,
        Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.POINTED_DRIPSTONE, Blocks.LILY_PAD, Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS,
        Blocks.MANGROVE_PROPAGULE, Blocks.DEAD_BUSH, Blocks.HANGING_ROOTS, Blocks.SPORE_BLOSSOM, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
        Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.SEA_PICKLE,
    )

    private fun isClutter(st: BlockState): Boolean =
        st.block in CLUTTER || st.`is`(BlockTags.FLOWERS) || st.`is`(BlockTags.SAPLINGS) || st.`is`(BlockTags.CORALS) ||
            (st.fluidState.isEmpty && st.canBeReplaced() && !st.isAir)

    private fun naturalLeaf(st: BlockState) = st.`is`(BlockTags.LEAVES) && !st.getOptionalValue(LeavesBlock.PERSISTENT).orElse(true)

    // ------------------------------------------------------------------ main entry

    /** Dresses one column. Returns false if the column can't be dressed right now. */
    fun dress(level: ServerLevel, s: Sanctuary, x: Int, z: Int, hit: ZoneLayout.Hit, @Suppress("UNUSED_PARAMETER") ring: List<String>): Boolean {
        val surface = findGround(level, s, x, z) ?: return true
        var state = level.getBlockState(surface)
        val random = RandomSource.create(s.seed xor (x * 341873128712L) xor (z * 132897987541L))

        // blend borders: near an edge, sometimes use the neighbour's look
        var zone = hit.zone
        val nb = hit.neighbour
        if (nb != null && nb in s.unlocked && hit.edgeDistance < 4.0 && random.nextDouble() < 0.5 - hit.edgeDistance / 8.0) zone = nb

        if (state.fluidState.`is`(FluidTags.WATER) && zone != "water") {
            if (!SanctuaryConfig.fillRivers.get() || !fillShallowWater(level, surface)) return true
            state = level.getBlockState(surface)
        }
        if (!isNatural(state) && !state.fluidState.`is`(FluidTags.WATER)) return true

        val ctx = Ctx(level, s, surface, random, variantOf(s, zone), hit)
        when (zone) {
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
        return true
    }

    fun variantOf(s: Sanctuary, zone: String): Int =
        s.variants.getOrPut(zone) { (Noise.hash(s.seed, zone.hashCode(), 7) * 3).toInt().coerceIn(0, 2) }

    /** The Minecraft biome painted under each sanctuary biome (grass/leaf/water colours, snow and rain). */
    fun biomeKey(zone: String, variant: Int): ResourceKey<Biome>? = when (zone) {
        ZoneLayout.HUB -> Biomes.PLAINS
        "water" -> listOf(Biomes.LUKEWARM_OCEAN, Biomes.WARM_OCEAN, Biomes.MANGROVE_SWAMP)[variant]
        "ground" -> listOf(Biomes.DESERT, Biomes.BADLANDS, Biomes.SAVANNA)[variant]
        "rock" -> listOf(Biomes.STONY_PEAKS, Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.STONY_SHORE)[variant]
        "fighting" -> listOf(Biomes.SAVANNA, Biomes.WINDSWEPT_SAVANNA, Biomes.SAVANNA_PLATEAU)[variant]
        "fire" -> listOf(Biomes.ERODED_BADLANDS, Biomes.BADLANDS, Biomes.SAVANNA_PLATEAU)[variant]
        "dragon" -> listOf(Biomes.WOODED_BADLANDS, Biomes.JAGGED_PEAKS, Biomes.WINDSWEPT_HILLS)[variant]
        "dark" -> Biomes.DARK_FOREST
        "ghost" -> Biomes.DEEP_DARK
        "poison" -> listOf(Biomes.SWAMP, Biomes.SWAMP, Biomes.MUSHROOM_FIELDS)[variant]
        "bug" -> listOf(Biomes.FLOWER_FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST)[variant]
        "grass" -> listOf(Biomes.JUNGLE, Biomes.FOREST, Biomes.MEADOW)[variant]
        "fairy" -> listOf(Biomes.CHERRY_GROVE, Biomes.LUSH_CAVES, Biomes.CHERRY_GROVE)[variant]
        "psychic" -> listOf(Biomes.WARPED_FOREST, Biomes.WARPED_FOREST, Biomes.MEADOW)[variant]
        "steel" -> listOf(Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.STONY_SHORE, Biomes.WINDSWEPT_GRAVELLY_HILLS)[variant]
        "electric" -> listOf(Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA, Biomes.SAVANNA)[variant]
        "ice" -> listOf(Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.FROZEN_RIVER)[variant]
        else -> null
    }

    // ------------------------------------------------------------------ ground finding + clearing

    /** The ground of a column, below trees and plants (which get cleared). Null = something built is in the way. */
    private fun findGround(level: ServerLevel, s: Sanctuary, x: Int, z: Int): BlockPos? {
        val clear = SanctuaryConfig.clearTrees.get()
        val air = Blocks.AIR.defaultBlockState()
        var p = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z)
        var guard = 0
        while (guard++ < 64 && p.y > level.minBuildHeight) {
            val st = level.getBlockState(p)
            when {
                st.isAir -> p = p.below()
                st.`is`(BlockTags.LEAVES) -> {
                    if (!clear || !naturalLeaf(st)) return null
                    level.setBlock(p, air, FLAGS)
                    p = p.below()
                }
                st.`is`(BlockTags.LOGS) -> if (!clear || !removeTree(level, s, p)) return null
                isClutter(st) -> {
                    val wet = !st.fluidState.isEmpty
                    level.setBlock(p, if (wet) Blocks.WATER.defaultBlockState() else air, FLAGS)
                    if (wet) return p
                    p = p.below()
                }
                else -> return p
            }
        }
        return null
    }

    /**
     * Removes a natural tree: its connected logs, then the natural leaves around them.
     * Trees the sanctuary planted itself, trees rooted outside it, and builds made of logs are kept.
     */
    private fun removeTree(level: ServerLevel, s: Sanctuary, start: BlockPos): Boolean {
        val r2 = s.size.radius.toDouble() * s.size.radius
        fun inside(p: BlockPos): Boolean {
            val dx = (p.x - s.center.x).toDouble()
            val dz = (p.z - s.center.z).toDouble()
            return dx * dx + dz * dz <= r2
        }

        val logs = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        logs += start
        queue += start
        var naturalLeaves = false
        while (queue.isNotEmpty() && logs.size < 2000) {
            val p = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val n = p.offset(dx, dy, dz)
                val st = level.getBlockState(n)
                if (!naturalLeaves && naturalLeaf(st)) naturalLeaves = true
                if (n !in logs && st.`is`(BlockTags.LOGS) && inside(n)) { logs += n; queue += n }
            }
        }
        if (!naturalLeaves) return false
        val lowest = logs.minOf { it.y }
        val limit = ColumnOrder.countWithin(s.size.radius)
        for (base in logs.filter { it.y == lowest }) {
            val idx = ColumnOrder.indexOf(base.x - s.center.x, base.z - s.center.z)
            if (idx < 0 || idx >= limit || s.dressed.get(idx)) return false
        }
        val air = Blocks.AIR.defaultBlockState()
        for (p in logs) level.setBlock(p, air, FLAGS)

        // leaves (and things hanging on them) within reach of the removed logs
        val seen = HashSet<BlockPos>(logs)
        var frontier: List<BlockPos> = logs.toList()
        repeat(7) {
            val next = ArrayList<BlockPos>()
            for (p in frontier) for (d in Direction.entries) {
                val n = p.relative(d)
                if (!seen.add(n) || !inside(n)) continue
                val st = level.getBlockState(n)
                val hanging = st.`is`(Blocks.VINE) || st.`is`(Blocks.COCOA) || st.`is`(Blocks.BEE_NEST) || st.`is`(Blocks.MOSS_CARPET)
                if (naturalLeaf(st) || hanging) {
                    level.setBlock(n, air, FLAGS)
                    next += n
                }
            }
            frontier = next
        }
        return true
    }

    /** Fills a shallow water column with dirt. Returns false if the water is too deep (oceans). */
    private fun fillShallowWater(level: ServerLevel, surface: BlockPos): Boolean {
        val column = ArrayList<BlockPos>()
        var p = surface
        while (level.getBlockState(p).fluidState.`is`(FluidTags.WATER)) {
            column += p
            if (column.size > SanctuaryConfig.fillWaterDepth.get()) return false
            p = p.below()
        }
        if (column.isEmpty() || level.getBlockState(p).isAir) return false
        for (pos in column) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), FLAGS)
        return true
    }

    private fun isFlatEnough(c: Ctx): Boolean {
        val y = c.surface.y
        for ((ox, oz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val h = c.level.getHeight(Heightmap.Types.OCEAN_FLOOR, c.x + ox, c.z + oz) - 1
            if (h < y) return false
        }
        return true
    }

    /** Turns the column into a pool of water [depth] deep with [bed] at the bottom (only where it can't spill). */
    private fun pool(c: Ctx, depth: Int, bed: BlockState): Boolean {
        if (!isFlatEnough(c)) { c.ground(bed); return false }
        for (i in 0 until depth) c.level.setBlock(c.surface.below(i), b(Blocks.WATER), FLAGS)
        c.level.setBlock(c.surface.below(depth), bed, FLAGS)
        return true
    }

    // ------------------------------------------------------------------ biomes

    private fun hub(c: Ctx) {
        val s = c.s
        val dx = c.x - s.center.x
        val dz = c.z - s.center.z
        val hubR = s.size.hubRadius
        val ringPath = c.hit.dist in (hubR - 5.0)..(hubR - 2.5)
        val spoke = (abs(dx) <= 1 || abs(dz) <= 1) && c.hit.dist > 4
        val plaza = c.hit.dist <= 4.5
        when {
            plaza -> c.ground(if ((dx + dz) and 1 == 0) b(Blocks.STONE_BRICKS) else b(Blocks.SMOOTH_STONE), b(Blocks.STONE))
            ringPath || spoke -> {
                c.ground(if (c.chance(0.15)) b(Blocks.COARSE_DIRT) else b(Blocks.DIRT_PATH), b(Blocks.DIRT))
                if (ringPath && c.hit.dist >= hubR - 2.9 && Math.floorMod(dx * 7 + dz * 13, 23) == 0) c.lampPost(b(Blocks.OAK_FENCE), b(Blocks.LANTERN))
            }
            else -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.chance(0.003) -> c.place(b(Blocks.HAY_BLOCK))
                    c.chance(0.025) -> c.place(c.pick(b(Blocks.DANDELION), b(Blocks.POPPY), b(Blocks.OXEYE_DAISY), b(Blocks.CORNFLOWER), b(Blocks.AZURE_BLUET)))
                    c.chance(0.12) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
        }
    }

    private fun water(c: Ctx) {
        val lake = c.noise(20.0) * 0.7 + c.noise(7.0, 11) * 0.3
        when (c.variant) {
            0 -> when { // clear lake with sandy beaches
                lake > 0.58 -> { if (pool(c, 3, c.pick(b(Blocks.SAND), b(Blocks.GRAVEL), b(Blocks.CLAY))) && c.chance(0.06)) c.level.setBlock(c.surface.below(2), b(Blocks.SEAGRASS), FLAGS) }
                lake > 0.48 -> { if (pool(c, 1, b(Blocks.SAND)) && c.chance(0.06)) c.place(b(Blocks.LILY_PAD)) }
                lake > 0.40 -> { c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE)); if (c.chance(0.05)) c.column(b(Blocks.SUGAR_CANE), 2 + c.random.nextInt(2)) }
                else -> { c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT)); if (c.chance(0.004) && c.roomy()) c.feature(TreeFeatures.OAK) else if (c.chance(0.08)) c.place(b(Blocks.SHORT_GRASS)) }
            }
            1 -> when { // coral lagoon
                lake > 0.5 -> {
                    if (pool(c, 3, b(Blocks.SAND))) {
                        if (c.chance(0.2)) c.level.setBlock(c.surface.below(2), c.pick(b(Blocks.BRAIN_CORAL_BLOCK), b(Blocks.TUBE_CORAL_BLOCK), b(Blocks.FIRE_CORAL_BLOCK), b(Blocks.HORN_CORAL_BLOCK), b(Blocks.BUBBLE_CORAL_BLOCK)), FLAGS)
                        else if (c.chance(0.08)) c.level.setBlock(c.surface.below(2), b(Blocks.SEA_PICKLE), FLAGS)
                    }
                }
                else -> { c.ground(c.pick(b(Blocks.SAND), b(Blocks.SAND), b(Blocks.SMOOTH_SANDSTONE)), b(Blocks.SANDSTONE)); if (c.chance(0.006) && c.roomy()) c.feature(TreeFeatures.JUNGLE_BUSH) }
            }
            else -> { // river delta with mangroves
                val channel = abs(kotlin.math.sin(c.x / 9.0 + c.noise(30.0) * 6) - kotlin.math.cos(c.z / 11.0)) < 0.35
                if (channel) { if (pool(c, 2, b(Blocks.MUD)) && c.chance(0.05)) c.place(b(Blocks.LILY_PAD)) }
                else {
                    c.ground(if (c.noise(6.0) > 0.5) b(Blocks.MUD) else b(Blocks.GRASS_BLOCK), b(Blocks.MUD))
                    if (c.chance(0.008) && c.roomy(6)) c.feature(TreeFeatures.MANGROVE) else if (c.chance(0.04)) c.column(b(Blocks.SUGAR_CANE), 2)
                }
            }
        }
    }

    private fun ground(c: Ctx) {
        when (c.variant) {
            0 -> { // desert with fossils and sandstone spires
                c.ground(if (c.noise(9.0) > 0.62) b(Blocks.SMOOTH_SANDSTONE) else b(Blocks.SAND), b(Blocks.SANDSTONE))
                when {
                    c.chance(0.004) -> c.feature(VegetationFeatures.PATCH_CACTUS)
                    c.chance(0.006) -> c.place(b(Blocks.DEAD_BUSH))
                    c.chance(0.0015) && c.roomy() -> c.spike(b(Blocks.CUT_SANDSTONE), 2 + c.random.nextInt(3))
                    c.chance(0.0008) && c.roomy() -> c.column(b(Blocks.BONE_BLOCK), 2)
                }
            }
            1 -> { // badlands
                val band = floor(c.surface.y / 3.0).toInt() and 3
                c.ground(if (c.noise(10.0) > 0.55) b(Blocks.RED_SAND) else listOf(b(Blocks.TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA))[band], b(Blocks.TERRACOTTA))
                when {
                    c.chance(0.006) -> c.place(b(Blocks.DEAD_BUSH))
                    c.chance(0.002) && c.roomy() -> c.spike(c.pick(b(Blocks.ORANGE_TERRACOTTA), b(Blocks.RED_TERRACOTTA)), 3 + c.random.nextInt(4))
                    c.chance(0.003) -> c.feature(VegetationFeatures.PATCH_CACTUS)
                }
            }
            else -> { // cracked mud flats
                c.ground(when { c.noise(5.0) > 0.6 -> b(Blocks.MUD_BRICKS); c.noise(7.0, 2) > 0.5 -> b(Blocks.PACKED_MUD); else -> b(Blocks.COARSE_DIRT) }, b(Blocks.MUD))
                if (c.chance(0.004)) c.place(b(Blocks.DEAD_BUSH)) else if (c.chance(0.0015) && c.roomy()) c.boulder(b(Blocks.PACKED_MUD), b(Blocks.MUD_BRICKS))
            }
        }
    }

    private fun rock(c: Ctx) {
        when (c.variant) {
            0 -> { // cliffs with spires
                c.ground(c.pick(b(Blocks.STONE), b(Blocks.STONE), b(Blocks.ANDESITE), b(Blocks.COBBLESTONE)), b(Blocks.STONE))
                when {
                    c.chance(0.003) && c.roomy() -> c.spike(c.pick(b(Blocks.STONE), b(Blocks.ANDESITE)), 3 + c.random.nextInt(5))
                    c.chance(0.003) -> c.feature(MiscOverworldFeatures.FOREST_ROCK)
                }
            }
            1 -> { // boulder field
                c.ground(c.pick(b(Blocks.GRAVEL), b(Blocks.STONE), b(Blocks.ANDESITE), b(Blocks.TUFF)), b(Blocks.STONE))
                if (c.chance(0.005) && c.roomy()) c.boulder(c.pick(b(Blocks.STONE), b(Blocks.ANDESITE), b(Blocks.COBBLESTONE)), b(Blocks.MOSSY_COBBLESTONE), 1 + c.random.nextInt(2))
            }
            else -> { // cave mouths: tuff, deepslate and dripstone
                c.ground(c.pick(b(Blocks.TUFF), b(Blocks.DEEPSLATE), b(Blocks.STONE), b(Blocks.DRIPSTONE_BLOCK)), b(Blocks.TUFF))
                if (c.chance(0.004) && c.roomy()) c.spike(b(Blocks.COBBLED_DEEPSLATE), 2 + c.random.nextInt(3))
                else if (c.chance(0.006)) c.place(b(Blocks.POINTED_DRIPSTONE))
            }
        }
    }

    private fun fighting(c: Ctx) {
        when (c.variant) {
            0 -> { // dojo ruins: tiled courtyards and pillars with lanterns
                val tiled = Math.floorMod(c.x, 9) < 3 || Math.floorMod(c.z, 9) < 3
                c.ground(if (tiled) c.pick(b(Blocks.STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS), b(Blocks.MOSSY_STONE_BRICKS)) else b(Blocks.COARSE_DIRT), b(Blocks.DIRT))
                if (Math.floorMod(c.x, 9) == 1 && Math.floorMod(c.z, 9) == 1 && c.roomy() && c.chance(0.6)) {
                    c.column(b(Blocks.CHISELED_STONE_BRICKS), 3); c.place(b(Blocks.LANTERN), 4)
                } else if (c.chance(0.003)) c.place(b(Blocks.HAY_BLOCK))
            }
            1 -> { // training grounds: targets and hay
                c.ground(c.pick(b(Blocks.COARSE_DIRT), b(Blocks.DIRT_PATH), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                when {
                    c.chance(0.004) && c.roomy() -> { c.place(b(Blocks.OAK_FENCE)); c.place(b(Blocks.TARGET), 2) }
                    c.chance(0.004) -> c.column(b(Blocks.HAY_BLOCK), 1 + c.random.nextInt(2))
                    c.chance(0.05) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            else -> { // rocky plateau with ruined walls
                c.ground(c.pick(b(Blocks.STONE), b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT)), b(Blocks.STONE))
                if (c.chance(0.002) && c.roomy(8)) c.ruinWall(b(Blocks.STONE_BRICKS), 4 + c.random.nextInt(4), 3)
            }
        }
    }

    private fun fire(c: Ctx) {
        when (c.variant) {
            0 -> { // volcano: dark rock with glowing magma cracks and basalt columns
                val crack = c.noise(5.0, 6) > 0.78
                c.ground(if (crack) b(Blocks.MAGMA_BLOCK) else c.pick(b(Blocks.BASALT), b(Blocks.BLACKSTONE), b(Blocks.SMOOTH_BASALT)), b(Blocks.BLACKSTONE))
                if (!crack && c.chance(0.006) && c.roomy()) c.spike(b(Blocks.BASALT), 2 + c.random.nextInt(5))
            }
            1 -> { // lava canyon colours
                val band = floor(c.surface.y / 2.0).toInt() and 3
                c.ground(listOf(b(Blocks.RED_TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.RED_SAND), b(Blocks.TERRACOTTA))[band], b(Blocks.RED_SANDSTONE))
                if (c.chance(0.004) && c.roomy()) c.spike(c.pick(b(Blocks.RED_SANDSTONE), b(Blocks.ORANGE_TERRACOTTA)), 3 + c.random.nextInt(4))
                else if (c.chance(0.006)) c.place(b(Blocks.DEAD_BUSH))
            }
            else -> { // hot springs over glowing magma
                if (c.noise(9.0, 5) > 0.64) pool(c, 2, b(Blocks.SMOOTH_BASALT))
                else {
                    c.ground(c.pick(b(Blocks.SMOOTH_BASALT), b(Blocks.TUFF), b(Blocks.COARSE_DIRT)), b(Blocks.STONE))
                    if (c.chance(0.004) && c.roomy()) c.boulder(b(Blocks.SMOOTH_BASALT), b(Blocks.BLACKSTONE))
                }
            }
        }
    }

    private fun dragon(c: Ctx) {
        when (c.variant) {
            0 -> { // canyon with giant bones
                val band = floor(c.surface.y / 2.0).toInt() and 3
                c.ground(listOf(b(Blocks.ORANGE_TERRACOTTA), b(Blocks.TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA))[band], b(Blocks.TERRACOTTA))
                if (c.chance(0.0025) && c.roomy(8)) { // rib arch
                    val h = 3 + c.random.nextInt(3)
                    c.column(b(Blocks.BONE_BLOCK), h)
                    for (i in 1..3) c.at(c.surface.above(h).east(i), b(Blocks.BONE_BLOCK).trySetValue(RotatedPillarBlock.AXIS, Direction.Axis.X))
                } else if (c.chance(0.003)) c.place(b(Blocks.DEAD_BUSH))
            }
            1 -> { // windswept peaks
                c.ground(if (c.noise(12.0) > 0.55) b(Blocks.SNOW_BLOCK) else c.pick(b(Blocks.STONE), b(Blocks.CALCITE), b(Blocks.DIORITE)), b(Blocks.STONE))
                if (c.chance(0.004) && c.roomy()) c.spike(c.pick(b(Blocks.STONE), b(Blocks.CALCITE)), 3 + c.random.nextInt(6))
            }
            else -> { // ancient ruins
                c.ground(c.pick(b(Blocks.MOSSY_STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.STONE))
                when {
                    c.chance(0.002) && c.roomy(8) -> c.ruinWall(b(Blocks.MOSSY_STONE_BRICKS), 5 + c.random.nextInt(5), 4)
                    c.chance(0.002) && c.roomy() -> c.column(b(Blocks.BONE_BLOCK), 2 + c.random.nextInt(2))
                }
            }
        }
    }

    private fun dark(c: Ctx) {
        when (c.variant) {
            0 -> { // night forest
                c.ground(c.pick(b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK), b(Blocks.ROOTED_DIRT)), b(Blocks.DIRT))
                when {
                    c.chance(0.012) && c.roomy(6) -> c.feature(TreeFeatures.DARK_OAK)
                    c.chance(0.012) -> c.place(c.pick(b(Blocks.BROWN_MUSHROOM), b(Blocks.RED_MUSHROOM)))
                    c.chance(0.04) -> c.place(b(Blocks.FERN))
                }
            }
            1 -> { // shadow ravine
                c.ground(c.pick(b(Blocks.DEEPSLATE), b(Blocks.TUFF), b(Blocks.COBBLED_DEEPSLATE), b(Blocks.BLACKSTONE)), b(Blocks.DEEPSLATE))
                if (c.chance(0.004) && c.roomy()) c.spike(c.pick(b(Blocks.BLACKSTONE), b(Blocks.COBBLED_DEEPSLATE)), 2 + c.random.nextInt(4))
                else if (c.chance(0.003)) { c.place(b(Blocks.COBBLED_DEEPSLATE_WALL)); c.place(b(Blocks.SOUL_LANTERN), 2) }
            }
            else -> { // ruined town in the dark woods
                c.ground(c.pick(b(Blocks.COBBLESTONE), b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COARSE_DIRT), b(Blocks.PODZOL)), b(Blocks.DIRT))
                when {
                    c.chance(0.0025) && c.roomy(8) -> c.ruinWall(b(Blocks.COBBLESTONE), 4 + c.random.nextInt(4), 3)
                    c.chance(0.006) && c.roomy(6) -> c.feature(TreeFeatures.DARK_OAK)
                }
            }
        }
    }

    private fun ghost(c: Ctx) {
        when (c.variant) {
            0 -> { // graveyard
                c.ground(c.pick(b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.SOUL_SOIL), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                when {
                    Math.floorMod(c.x, 4) == 0 && Math.floorMod(c.z, 6) == 0 && c.roomy() && c.chance(0.55) -> c.gravestone()
                    c.chance(0.003) -> c.place(b(Blocks.COBWEB))
                    c.chance(0.003) -> c.lampPost(b(Blocks.SPRUCE_FENCE), b(Blocks.SOUL_LANTERN))
                    c.chance(0.002) && c.roomy() -> c.column(b(Blocks.STRIPPED_DARK_OAK_LOG), 3 + c.random.nextInt(3)) // dead tree
                    c.chance(0.02) -> c.place(b(Blocks.DEAD_BUSH))
                }
            }
            1 -> { // haunted forest of dead trees
                c.ground(c.pick(b(Blocks.PODZOL), b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.SOUL_SOIL)), b(Blocks.DIRT))
                when {
                    c.chance(0.006) && c.roomy() -> {
                        val h = 4 + c.random.nextInt(4)
                        c.column(b(Blocks.STRIPPED_SPRUCE_LOG), h)
                        c.at(c.surface.above(h - 1).east(), b(Blocks.SPRUCE_FENCE))
                        c.at(c.surface.above(h - 2).west(), b(Blocks.SPRUCE_FENCE))
                    }
                    c.chance(0.004) -> c.place(b(Blocks.COBWEB))
                    c.chance(0.002) -> c.place(b(Blocks.JACK_O_LANTERN))
                }
            }
            else -> { // soul ruins
                c.ground(c.pick(b(Blocks.SOUL_SOIL), b(Blocks.SOUL_SAND), b(Blocks.COBBLED_DEEPSLATE)), b(Blocks.SOUL_SOIL))
                when {
                    c.chance(0.002) && c.roomy(8) -> c.ruinWall(b(Blocks.DEEPSLATE_TILES), 4 + c.random.nextInt(4), 3)
                    c.chance(0.004) -> { c.place(b(Blocks.COBBLED_DEEPSLATE_WALL)); c.place(b(Blocks.SOUL_LANTERN), 2) }
                }
            }
        }
    }

    private fun poison(c: Ctx) {
        val puddle = c.noise(8.0, 3) > 0.66
        when (c.variant) {
            0 -> { // murky swamp
                if (puddle) { if (pool(c, 1, b(Blocks.MUD)) && c.chance(0.12)) c.place(b(Blocks.LILY_PAD)); return }
                c.ground(c.pick(b(Blocks.MUD), b(Blocks.GRASS_BLOCK), b(Blocks.GRASS_BLOCK)), b(Blocks.MUD))
                when {
                    c.chance(0.006) && c.roomy(6) -> c.feature(TreeFeatures.SWAMP_OAK)
                    c.chance(0.012) -> c.place(b(Blocks.BLUE_ORCHID))
                    c.chance(0.01) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                }
            }
            1 -> { // toxic bog: purple sludge
                if (puddle) { pool(c, 1, b(Blocks.MUD)); return }
                c.ground(when { c.noise(6.0) > 0.6 -> b(Blocks.PURPLE_TERRACOTTA); c.noise(9.0, 4) > 0.5 -> b(Blocks.MYCELIUM); else -> b(Blocks.MUD) }, b(Blocks.MUD))
                if (c.chance(0.01)) c.place(b(Blocks.RED_MUSHROOM)) else if (c.chance(0.003) && c.roomy()) c.boulder(b(Blocks.PURPLE_TERRACOTTA), b(Blocks.MAGENTA_TERRACOTTA))
            }
            else -> { // giant mushroom marsh
                c.ground(b(Blocks.MYCELIUM), b(Blocks.DIRT))
                when {
                    c.chance(0.004) && c.roomy(6) -> c.feature(if (c.random.nextBoolean()) TreeFeatures.HUGE_RED_MUSHROOM else TreeFeatures.HUGE_BROWN_MUSHROOM)
                    c.chance(0.025) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                }
            }
        }
    }

    private fun bug(c: Ctx) {
        c.ground(if (c.noise(9.0) > 0.58) b(Blocks.MOSS_BLOCK) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        when (c.variant) {
            0 -> when { // thicket
                c.chance(0.008) && c.roomy() -> c.feature(TreeFeatures.AZALEA_TREE)
                c.chance(0.003) && c.roomy() -> c.hollowLog(b(Blocks.OAK_LOG))
                c.chance(0.003) -> { c.place(b(Blocks.OAK_FENCE)); c.place(b(Blocks.BEE_NEST), 2) }
                c.chance(0.2) -> c.place(c.pick(b(Blocks.SHORT_GRASS), b(Blocks.FERN), b(Blocks.SHORT_GRASS)))
            }
            1 -> when { // giant flowers
                c.chance(0.015) -> c.feature(VegetationFeatures.FLOWER_FLOWER_FOREST)
                c.chance(0.02) -> c.tallPlant(c.pick(b(Blocks.SUNFLOWER), b(Blocks.LILAC), b(Blocks.ROSE_BUSH), b(Blocks.PEONY)))
                c.chance(0.06) -> c.place(b(Blocks.SHORT_GRASS))
            }
            else -> when { // fallen logs in a birch wood
                c.chance(0.006) && c.roomy() -> c.feature(TreeFeatures.BIRCH)
                c.chance(0.004) && c.roomy() -> c.hollowLog(b(Blocks.BIRCH_LOG))
                c.chance(0.12) -> c.place(b(Blocks.SHORT_GRASS))
            }
        }
    }

    private fun grass(c: Ctx) {
        c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        when (c.variant) {
            0 -> when { // jungle
                c.chance(0.012) && c.roomy(6) -> c.feature(TreeFeatures.JUNGLE_TREE_NO_VINE)
                c.chance(0.01) -> c.feature(TreeFeatures.JUNGLE_BUSH)
                c.chance(0.004) -> c.place(b(Blocks.MELON))
                c.chance(0.008) -> c.column(b(Blocks.BAMBOO), 3 + c.random.nextInt(5))
                c.chance(0.1) -> c.place(c.pick(b(Blocks.FERN), b(Blocks.SHORT_GRASS)))
            }
            1 -> when { // leafy forest
                c.chance(0.014) && c.roomy(6) -> c.feature(if (c.random.nextInt(3) == 0) TreeFeatures.FANCY_OAK else TreeFeatures.OAK)
                c.chance(0.06) -> c.place(b(Blocks.SHORT_GRASS))
                c.chance(0.006) -> c.place(c.pick(b(Blocks.POPPY), b(Blocks.DANDELION)))
            }
            else -> when { // meadow of tall grass
                c.chance(0.12) -> c.tallPlant(b(Blocks.TALL_GRASS))
                c.chance(0.04) -> c.place(c.pick(b(Blocks.CORNFLOWER), b(Blocks.ALLIUM), b(Blocks.OXEYE_DAISY), b(Blocks.AZURE_BLUET)))
                c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
            }
        }
    }

    private fun fairy(c: Ctx) {
        when (c.variant) {
            0 -> { // cherry blossoms
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.chance(0.01) && c.roomy(6) -> c.feature(TreeFeatures.CHERRY)
                    c.chance(0.15) -> c.place(b(Blocks.PINK_PETALS))
                    c.chance(0.02) -> c.place(c.pick(b(Blocks.PINK_TULIP), b(Blocks.ALLIUM), b(Blocks.LILY_OF_THE_VALLEY)))
                }
            }
            1 -> { // glowing mushroom glade
                c.ground(if (c.noise(8.0) > 0.45) b(Blocks.MOSS_BLOCK) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.chance(0.004) && c.roomy() -> { val h = 2 + c.random.nextInt(2); c.column(b(Blocks.MUSHROOM_STEM), h); c.place(b(Blocks.SHROOMLIGHT), h + 1) }
                    c.chance(0.02) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                    c.chance(0.05) -> c.place(b(Blocks.MOSS_CARPET))
                    c.chance(0.008) && c.roomy() -> c.feature(TreeFeatures.AZALEA_TREE)
                }
            }
            else -> { // flower ponds
                if (c.noise(10.0, 9) > 0.66) { if (pool(c, 1, b(Blocks.CLAY)) && c.chance(0.12)) c.place(b(Blocks.LILY_PAD)); return }
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                if (c.chance(0.12)) c.place(c.pick(b(Blocks.ALLIUM), b(Blocks.PINK_TULIP), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.AZURE_BLUET), b(Blocks.WHITE_TULIP)))
                else if (c.chance(0.04)) c.tallPlant(c.pick(b(Blocks.LILAC), b(Blocks.PEONY)))
            }
        }
    }

    private fun psychic(c: Ctx) {
        val crystal = c.noise(7.0, 4) > 0.6
        when (c.variant) {
            0 -> { // crystal garden
                c.ground(if (crystal) b(Blocks.AMETHYST_BLOCK) else c.pick(b(Blocks.CALCITE), b(Blocks.CALCITE), b(Blocks.SMOOTH_QUARTZ)), b(Blocks.CALCITE))
                when {
                    crystal && c.chance(0.08) -> c.place(c.pick(b(Blocks.AMETHYST_CLUSTER), b(Blocks.LARGE_AMETHYST_BUD), b(Blocks.MEDIUM_AMETHYST_BUD)))
                    c.chance(0.003) && c.roomy() -> c.spike(b(Blocks.AMETHYST_BLOCK), 2 + c.random.nextInt(4))
                }
            }
            1 -> { // floating blocks over warped moss
                c.ground(if (crystal) b(Blocks.WARPED_NYLIUM) else c.pick(b(Blocks.CALCITE), b(Blocks.SMOOTH_QUARTZ)), b(Blocks.CALCITE))
                when {
                    c.chance(0.005) && c.roomy() -> c.place(c.pick(b(Blocks.PURPUR_BLOCK), b(Blocks.AMETHYST_BLOCK), b(Blocks.WARPED_WART_BLOCK)), 3 + c.random.nextInt(4))
                    c.chance(0.003) -> c.place(b(Blocks.END_ROD))
                    crystal && c.chance(0.08) -> c.place(c.pick(b(Blocks.WARPED_ROOTS), b(Blocks.WARPED_FUNGUS)))
                }
            }
            else -> { // mind temple ruins
                c.ground(c.pick(b(Blocks.CALCITE), b(Blocks.QUARTZ_BLOCK), b(Blocks.PURPUR_BLOCK)), b(Blocks.CALCITE))
                when {
                    c.chance(0.003) && c.roomy() -> { c.column(b(Blocks.PURPUR_PILLAR), 3 + c.random.nextInt(2)); c.place(b(Blocks.END_ROD), 5) }
                    c.chance(0.0015) && c.roomy(8) -> c.ruinWall(b(Blocks.QUARTZ_BRICKS), 4, 3)
                }
            }
        }
    }

    private fun steel(c: Ctx) {
        when (c.variant) {
            0 -> { // scrapyard
                c.ground(c.pick(b(Blocks.GRAVEL), b(Blocks.POLISHED_ANDESITE), b(Blocks.COBBLESTONE), b(Blocks.WEATHERED_COPPER)), b(Blocks.STONE))
                when {
                    c.chance(0.004) && c.roomy() -> c.boulder(c.pick(b(Blocks.POLISHED_ANDESITE), b(Blocks.SMOOTH_STONE)), b(Blocks.IRON_BARS))
                    c.chance(0.006) -> c.place(b(Blocks.IRON_BARS))
                    c.chance(0.003) -> c.slab(b(Blocks.SMOOTH_STONE_SLAB))
                    c.chance(0.002) -> c.place(c.pick(b(Blocks.CHIPPED_ANVIL), b(Blocks.DAMAGED_ANVIL)))
                }
            }
            1 -> { // old factory floor with chimneys
                c.ground(if (Math.floorMod(c.x + c.z, 6) == 0) b(Blocks.POLISHED_ANDESITE) else c.pick(b(Blocks.SMOOTH_STONE), b(Blocks.STONE_BRICKS)), b(Blocks.STONE))
                if (c.chance(0.0015) && c.roomy(8)) c.column(b(Blocks.POLISHED_ANDESITE), 5 + c.random.nextInt(4))
                else if (c.chance(0.004)) c.column(b(Blocks.IRON_BARS), 2)
            }
            else -> { // metal ridge
                c.ground(c.pick(b(Blocks.POLISHED_ANDESITE), b(Blocks.TUFF), b(Blocks.STONE), b(Blocks.OXIDIZED_COPPER)), b(Blocks.STONE))
                if (c.chance(0.004) && c.roomy()) c.spike(c.pick(b(Blocks.POLISHED_ANDESITE), b(Blocks.TUFF_BRICKS)), 2 + c.random.nextInt(4))
            }
        }
    }

    private fun electric(c: Ctx) {
        when (c.variant) {
            0 -> { // storm plains
                c.ground(if (c.noise(9.0) > 0.6) b(Blocks.YELLOW_TERRACOTTA) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.chance(0.003) && c.roomy() -> { c.column(b(Blocks.POLISHED_ANDESITE), 2); c.place(b(Blocks.LIGHTNING_ROD), 3) }
                    c.chance(0.08) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            1 -> { // power station ruins
                c.ground(c.pick(b(Blocks.SMOOTH_STONE), b(Blocks.STONE_BRICKS), b(Blocks.GRAVEL), b(Blocks.YELLOW_CONCRETE_POWDER)), b(Blocks.STONE))
                when {
                    c.chance(0.003) && c.roomy() -> { c.column(b(Blocks.IRON_BARS), 3); c.place(b(Blocks.END_ROD), 4) }
                    c.chance(0.002) -> c.place(b(Blocks.REDSTONE_LAMP))
                }
            }
            else -> { // charged ridge of copper and yellow stone
                c.ground(c.pick(b(Blocks.STONE), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.ANDESITE), b(Blocks.WEATHERED_COPPER)), b(Blocks.STONE))
                if (c.chance(0.003) && c.roomy()) c.place(b(Blocks.LIGHTNING_ROD)) else if (c.chance(0.003)) c.spike(b(Blocks.YELLOW_TERRACOTTA), 2 + c.random.nextInt(3))
            }
        }
    }

    private fun ice(c: Ctx) {
        when (c.variant) {
            0 -> { // glacier with ice spikes
                val n = c.noise(8.0)
                c.ground(when { n > 0.66 -> b(Blocks.BLUE_ICE); n > 0.55 -> b(Blocks.PACKED_ICE); else -> b(Blocks.SNOW_BLOCK) }, b(Blocks.PACKED_ICE))
                when {
                    c.chance(0.0012) && c.roomy(6) -> c.feature(MiscOverworldFeatures.ICE_SPIKE)
                    c.chance(0.003) && c.roomy() -> c.spike(b(Blocks.PACKED_ICE), 2 + c.random.nextInt(4))
                    c.chance(0.3) -> c.place(b(Blocks.SNOW))
                }
            }
            1 -> { // snowy taiga
                c.ground(b(Blocks.SNOW_BLOCK), b(Blocks.DIRT))
                when {
                    c.chance(0.012) && c.roomy(6) -> c.feature(TreeFeatures.SPRUCE)
                    c.chance(0.004) -> c.place(b(Blocks.FERN))
                    c.chance(0.5) -> c.place(b(Blocks.SNOW))
                }
            }
            else -> { // frozen lake
                val lake = c.noise(16.0) > 0.5
                if (lake && isFlatEnough(c)) { c.level.setBlock(c.surface, b(Blocks.ICE), FLAGS); c.filler(b(Blocks.WATER), 1) }
                else { c.ground(if (c.noise(14.0) > 0.45) b(Blocks.PACKED_ICE) else b(Blocks.SNOW_BLOCK), b(Blocks.PACKED_ICE)); if (c.chance(0.25)) c.place(b(Blocks.SNOW)) }
            }
        }
    }

    /** Biomes for custom types from other mods: tinted ground in a colour picked from the type's name. */
    private fun custom(c: Ctx, zone: String) {
        val tints = listOf(
            Blocks.PURPLE_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
            Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
        )
        val tint = tints[Math.floorMod(zone.hashCode(), tints.size)]
        c.ground(if (c.noise(9.0) > 0.55) b(tint) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        if (c.chance(0.003) && c.roomy()) c.spike(b(tint), 2 + c.random.nextInt(3)) else if (c.chance(0.05)) c.place(b(Blocks.SHORT_GRASS))
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

    fun value(seed: Long, x: Double, z: Double): Double {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = smooth(x - x0)
        val fz = smooth(z - z0)
        val a = hash(seed, x0, z0)
        val b = hash(seed, x0 + 1, z0)
        val c = hash(seed, x0, z0 + 1)
        val d = hash(seed, x0 + 1, z0 + 1)
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)
}
