package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.features.MiscOverworldFeatures
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.data.worldgen.features.VegetationFeatures
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import kotlin.math.floor

/**
 * Turns one terrain column into the look of its biome. Only natural ground is touched,
 * so player builds are left alone.
 */
object BiomeDresser {
    private const val FLAGS = Block.UPDATE_CLIENTS

    class Ctx(
        val level: ServerLevel,
        val s: Sanctuary,
        val surface: BlockPos,
        val random: RandomSource,
        val variant: Int,
        val hit: ZoneLayout.Hit,
    ) {
        val above: BlockPos get() = surface.above()
        fun chance(p: Double) = random.nextDouble() < p
        fun noise(scale: Double, salt: Int = 0) = Noise.value(s.seed + salt, surface.x / scale, surface.z / scale)

        fun top(state: BlockState) { level.setBlock(surface, state, FLAGS) }
        fun filler(state: BlockState, depth: Int = 2) {
            for (i in 1..depth) {
                val p = surface.below(i)
                if (isNatural(level.getBlockState(p))) level.setBlock(p, state, FLAGS)
            }
        }
        fun ground(top: BlockState, filler: BlockState = top) { top(top); filler(filler) }
        fun place(state: BlockState, offset: Int = 1) {
            val p = surface.above(offset)
            if (level.getBlockState(p).isAir) level.setBlock(p, state, FLAGS)
        }
        fun column(state: BlockState, height: Int) { for (i in 1..height) place(state, i) }
        fun feature(key: ResourceKey<ConfiguredFeature<*, *>>) {
            runCatching {
                level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent {
                    it.value().place(level, level.chunkSource.generator, random, above)
                }
            }
        }
        fun pick(vararg states: BlockState): BlockState = states[random.nextInt(states.size)]
    }

    fun isNatural(state: BlockState): Boolean =
        state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
            state.`is`(BlockTags.TERRACOTTA) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.SNOW_BLOCK) ||
            state.`is`(Blocks.CLAY) || state.`is`(Blocks.SANDSTONE) || state.`is`(Blocks.RED_SANDSTONE) ||
            state.`is`(Blocks.PACKED_ICE) || state.`is`(Blocks.ICE) || state.`is`(Blocks.CALCITE) ||
            state.`is`(Blocks.BLACKSTONE) || state.`is`(Blocks.BASALT) || state.`is`(Blocks.SMOOTH_BASALT) ||
            state.`is`(Blocks.SOUL_SOIL) || state.`is`(Blocks.AMETHYST_BLOCK) || state.`is`(Blocks.PACKED_MUD) ||
            state.`is`(Blocks.POLISHED_ANDESITE) || state.`is`(Blocks.COBBLESTONE) || state.`is`(Blocks.MOSSY_COBBLESTONE) ||
            state.`is`(Blocks.DIRT_PATH) || state.`is`(Blocks.SMOOTH_STONE)

    /** Dresses one column. Returns false if the column can't be dressed right now. */
    fun dress(level: ServerLevel, s: Sanctuary, x: Int, z: Int, hit: ZoneLayout.Hit, ring: List<String>): Boolean {
        val surface = findGround(level, s, x, z) ?: return true
        var state = level.getBlockState(surface)
        val random = RandomSource.create(s.seed xor (x * 341873128712L) xor (z * 132897987541L))

        // blend borders: near an edge, sometimes use the neighbour's look
        var zone = hit.zone
        val nb = hit.neighbour
        if (nb != null && nb in s.unlocked && hit.edgeDistance < 4.0 && random.nextDouble() < 0.5 - hit.edgeDistance / 8.0) zone = nb

        if (state.fluidState.`is`(FluidTags.WATER) && zone != "water") {
            // rivers and ponds: fill shallow water so the biome continues across; leave deep water (oceans) alone
            if (!SanctuaryConfig.fillRivers.get() || !fillShallowWater(level, surface)) return true
            state = level.getBlockState(surface)
        }
        if (!isNatural(state) && !state.fluidState.`is`(FluidTags.WATER)) return true

        // clear small plants so nothing floats
        for (i in 1..2) {
            val p = surface.above(i)
            val st = level.getBlockState(p)
            if (!st.isAir && st.canBeReplaced() && st.fluidState.isEmpty) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS)
        }

        val variant = s.variants.getOrPut(zone) { (Noise.hash(s.seed, zone.hashCode(), 7) * 3).toInt().coerceIn(0, 2) }
        val ctx = Ctx(level, s, surface, random, variant, hit)
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

    private fun b(block: Block): BlockState = block.defaultBlockState()

    /**
     * The ground of a column, below trees and plants. Natural trees (logs with natural leaves attached)
     * are cleared on the way down; anything else on top (player builds) makes the column be skipped.
     */
    private fun findGround(level: ServerLevel, s: Sanctuary, x: Int, z: Int): BlockPos? {
        val top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1
        var p = BlockPos(x, top, z)
        var guard = 0
        while (guard++ < 48 && p.y > level.minBuildHeight) {
            val st = level.getBlockState(p)
            when {
                st.isAir -> p = p.below()
                st.`is`(BlockTags.LEAVES) -> {
                    if (!SanctuaryConfig.clearTrees.get() || st.getOptionalValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT).orElse(false)) return null
                    p = p.below()
                }
                st.`is`(BlockTags.LOGS) -> {
                    if (!SanctuaryConfig.clearTrees.get() || !removeTree(level, s, p)) return null
                }
                st.fluidState.isEmpty && st.canBeReplaced() -> p = p.below()
                st.`is`(Blocks.SNOW) || st.`is`(Blocks.VINE) || st.`is`(Blocks.BAMBOO) || st.`is`(Blocks.SUGAR_CANE) || st.`is`(Blocks.CACTUS) || st.`is`(BlockTags.FLOWERS) || st.`is`(BlockTags.SAPLINGS) -> p = p.below()
                st.`is`(Blocks.BROWN_MUSHROOM_BLOCK) || st.`is`(Blocks.RED_MUSHROOM_BLOCK) || st.`is`(Blocks.MUSHROOM_STEM) -> {
                    if (!SanctuaryConfig.clearTrees.get()) return null
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS); p = p.below()
                }
                else -> return p
            }
        }
        return null
    }

    /** Removes a natural tree (connected logs + non-persistent leaves). Returns false if it isn't a natural tree. */
    private fun removeTree(level: ServerLevel, s: Sanctuary, start: BlockPos): Boolean {
        val seen = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue += start
        seen += start
        var naturalLeaves = false
        while (queue.isNotEmpty() && seen.size < 900) {
            val p = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val n = p.offset(dx, dy, dz)
                if (n in seen) continue
                val st = level.getBlockState(n)
                val isLog = st.`is`(BlockTags.LOGS)
                val isLeaf = st.`is`(BlockTags.LEAVES) && !st.getOptionalValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT).orElse(true)
                if (isLeaf) naturalLeaves = true
                if (isLog || isLeaf) { seen += n; queue += n }
            }
        }
        if (!naturalLeaves) return false
        // keep trees the sanctuary planted itself (their base column is already dressed) and trees rooted outside
        val logs = seen.filter { level.getBlockState(it).`is`(BlockTags.LOGS) }.ifEmpty { listOf(start) }
        val lowest = logs.minOf { it.y }
        val limit = ColumnOrder.countWithin(s.size.radius)
        for (base in logs.filter { it.y == lowest }) {
            val idx = ColumnOrder.indexOf(base.x - s.center.x, base.z - s.center.z)
            if (idx < 0 || idx >= limit || s.dressed.get(idx)) return false
        }
        val air = Blocks.AIR.defaultBlockState()
        for (p in seen) level.setBlock(p, air, FLAGS)
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

    // ---------------------------------------------------------------- biomes

    private fun hub(c: Ctx) {
        val s = c.s
        val dx = c.surface.x - s.center.x
        val dz = c.surface.z - s.center.z
        val hubR = s.size.hubRadius
        val onRing = c.hit.dist in (hubR - 4.5)..(hubR - 2.5)
        val onSpoke = (kotlin.math.abs(dx) <= 1 || kotlin.math.abs(dz) <= 1) && c.hit.dist > 4
        if (onRing || onSpoke) {
            c.ground(b(Blocks.DIRT_PATH), b(Blocks.DIRT))
        } else {
            c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
            when {
                c.chance(0.006) -> c.feature(VegetationFeatures.FLOWER_DEFAULT)
                c.chance(0.05) -> c.place(c.pick(b(Blocks.SHORT_GRASS), b(Blocks.SHORT_GRASS), b(Blocks.DANDELION), b(Blocks.POPPY), b(Blocks.OXEYE_DAISY)))
            }
        }
    }

    private fun water(c: Ctx) {
        val lake = c.noise(22.0) * 0.7 + c.noise(8.0, 11) * 0.3
        val wet = when (c.variant) {
            2 -> kotlin.math.abs(kotlin.math.sin(c.surface.x / 9.0 + c.noise(30.0) * 6) - kotlin.math.cos(c.surface.z / 11.0)) < 0.35 // river delta
            else -> lake > 0.48
        }
        if (wet && isFlatEnough(c)) {
            c.top(b(Blocks.WATER))
            c.filler(if (c.variant == 1) b(Blocks.SAND) else c.pick(b(Blocks.SAND), b(Blocks.CLAY), b(Blocks.GRAVEL)), 1)
            if (c.variant == 1 && c.chance(0.06)) {
                c.level.setBlock(c.surface.below(), c.pick(b(Blocks.BRAIN_CORAL_BLOCK), b(Blocks.TUBE_CORAL_BLOCK), b(Blocks.FIRE_CORAL_BLOCK), b(Blocks.HORN_CORAL_BLOCK)), FLAGS)
            } else if (c.chance(0.04)) {
                c.place(b(Blocks.LILY_PAD))
            }
        } else {
            when (c.variant) {
                2 -> c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT)).also { if (c.chance(0.05)) c.place(b(Blocks.SUGAR_CANE)) }
                else -> {
                    c.ground(if (lake > 0.38) b(Blocks.SAND) else b(Blocks.GRASS_BLOCK), b(Blocks.SAND))
                    if (lake <= 0.38 && c.chance(0.04)) c.place(b(Blocks.SHORT_GRASS))
                    if (lake > 0.38 && c.chance(0.03)) c.place(b(Blocks.SUGAR_CANE))
                }
            }
        }
    }

    private fun isFlatEnough(c: Ctx): Boolean {
        val l = c.level
        val y = c.surface.y
        for ((ox, oz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val h = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.surface.x + ox, c.surface.z + oz) - 1
            if (h < y) return false
        }
        return true
    }

    private fun ground(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE)); if (c.chance(0.004)) c.feature(VegetationFeatures.PATCH_CACTUS) else if (c.chance(0.006)) c.place(b(Blocks.DEAD_BUSH)) }
            1 -> {
                val band = floor(c.surface.y / 3.0).toInt() and 3
                c.ground(if (c.noise(10.0) > 0.55) b(Blocks.RED_SAND) else listOf(b(Blocks.TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA))[band], b(Blocks.TERRACOTTA))
                if (c.chance(0.006)) c.place(b(Blocks.DEAD_BUSH))
            }
            else -> { c.ground(if (c.noise(7.0) > 0.5) b(Blocks.MUD) else b(Blocks.PACKED_MUD), b(Blocks.MUD)); if (c.chance(0.003)) c.place(b(Blocks.DEAD_BUSH)) }
        }
    }

    private fun rock(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(c.pick(b(Blocks.STONE), b(Blocks.STONE), b(Blocks.ANDESITE), b(Blocks.COBBLESTONE)), b(Blocks.STONE)); if (c.chance(0.004)) c.feature(MiscOverworldFeatures.FOREST_ROCK) }
            1 -> { c.ground(c.pick(b(Blocks.GRAVEL), b(Blocks.STONE), b(Blocks.ANDESITE)), b(Blocks.STONE)); if (c.chance(0.01)) c.column(c.pick(b(Blocks.COBBLESTONE), b(Blocks.ANDESITE), b(Blocks.STONE)), 1 + c.random.nextInt(2)) }
            else -> { c.ground(c.pick(b(Blocks.TUFF), b(Blocks.DEEPSLATE), b(Blocks.STONE)), b(Blocks.TUFF)); if (c.chance(0.006)) c.column(b(Blocks.COBBLED_DEEPSLATE), 1 + c.random.nextInt(3)) }
        }
    }

    private fun fighting(c: Ctx) {
        when (c.variant) {
            0 -> { // dojo ruins
                val tiled = (c.surface.x and 7) < 2 || (c.surface.z and 7) < 2
                c.ground(if (tiled) c.pick(b(Blocks.STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS), b(Blocks.MOSSY_STONE_BRICKS)) else b(Blocks.COARSE_DIRT), b(Blocks.DIRT))
                if (c.chance(0.003)) c.column(b(Blocks.STONE_BRICK_WALL), 2 + c.random.nextInt(2))
            }
            1 -> { c.ground(c.pick(b(Blocks.COARSE_DIRT), b(Blocks.DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT)); if (c.chance(0.003)) c.place(b(Blocks.HAY_BLOCK)) else if (c.chance(0.002)) c.place(b(Blocks.TARGET)) }
            else -> { c.ground(c.pick(b(Blocks.STONE), b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT)), b(Blocks.STONE)); if (c.chance(0.004)) c.feature(MiscOverworldFeatures.FOREST_ROCK) }
        }
    }

    private fun fire(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(c.pick(b(Blocks.BASALT), b(Blocks.BLACKSTONE), b(Blocks.SMOOTH_BASALT)), b(Blocks.BLACKSTONE)); if (c.chance(0.004)) c.column(b(Blocks.BASALT), 1 + c.random.nextInt(3)) }
            1 -> { c.ground(c.pick(b(Blocks.RED_SAND), b(Blocks.RED_TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA)), b(Blocks.RED_SANDSTONE)); if (c.chance(0.006)) c.place(b(Blocks.DEAD_BUSH)) }
            else -> { // hot springs
                if (c.noise(9.0, 5) > 0.66 && isFlatEnough(c)) { c.top(b(Blocks.WATER)); c.filler(b(Blocks.SMOOTH_BASALT), 1) }
                else { c.ground(c.pick(b(Blocks.SMOOTH_BASALT), b(Blocks.TUFF), b(Blocks.COARSE_DIRT)), b(Blocks.STONE)) }
            }
        }
    }

    private fun dragon(c: Ctx) {
        when (c.variant) {
            0 -> {
                val band = floor(c.surface.y / 2.0).toInt() and 3
                c.ground(listOf(b(Blocks.ORANGE_TERRACOTTA), b(Blocks.TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA))[band], b(Blocks.TERRACOTTA))
                if (c.chance(0.0025)) c.column(b(Blocks.BONE_BLOCK), 1 + c.random.nextInt(3))
            }
            1 -> { c.ground(if (c.noise(12.0) > 0.6) b(Blocks.SNOW_BLOCK) else c.pick(b(Blocks.STONE), b(Blocks.CALCITE)), b(Blocks.STONE)); if (c.chance(0.003)) c.column(b(Blocks.STONE), 1 + c.random.nextInt(2)) }
            else -> { c.ground(c.pick(b(Blocks.MOSSY_STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.STONE)); if (c.chance(0.003)) c.column(b(Blocks.BONE_BLOCK), 2) }
        }
    }

    private fun dark(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(c.pick(b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT)); if (c.chance(0.012)) c.feature(TreeFeatures.DARK_OAK) else if (c.chance(0.01)) c.place(b(Blocks.BROWN_MUSHROOM)) }
            1 -> { c.ground(c.pick(b(Blocks.DEEPSLATE), b(Blocks.TUFF), b(Blocks.COBBLED_DEEPSLATE)), b(Blocks.DEEPSLATE)) }
            else -> { c.ground(c.pick(b(Blocks.COBBLESTONE), b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COARSE_DIRT)), b(Blocks.DIRT)); if (c.chance(0.004)) c.column(b(Blocks.COBBLESTONE_WALL), 1 + c.random.nextInt(2)) }
        }
    }

    private fun ghost(c: Ctx) {
        when (c.variant) {
            0 -> { // graveyard
                c.ground(c.pick(b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                when {
                    c.chance(0.006) -> c.column(c.pick(b(Blocks.COBBLESTONE_WALL), b(Blocks.MOSSY_COBBLESTONE_WALL)), 1)
                    c.chance(0.0015) -> c.place(b(Blocks.COBWEB))
                    c.chance(0.002) -> { c.place(b(Blocks.OAK_FENCE)); c.place(b(Blocks.SOUL_LANTERN), 2) }
                }
            }
            1 -> { c.ground(c.pick(b(Blocks.PODZOL), b(Blocks.PODZOL), b(Blocks.COARSE_DIRT)), b(Blocks.DIRT)); if (c.chance(0.01)) c.feature(TreeFeatures.SPRUCE) else if (c.chance(0.002)) c.place(b(Blocks.COBWEB)) }
            else -> { c.ground(c.pick(b(Blocks.SOUL_SOIL), b(Blocks.COBBLED_DEEPSLATE), b(Blocks.COARSE_DIRT)), b(Blocks.DIRT)); if (c.chance(0.004)) c.column(b(Blocks.COBBLED_DEEPSLATE_WALL), 1 + c.random.nextInt(2)) }
        }
    }

    private fun poison(c: Ctx) {
        val puddle = c.noise(8.0, 3) > 0.68 && isFlatEnough(c)
        if (puddle) { c.top(b(Blocks.WATER)); c.filler(b(Blocks.MUD), 1); if (c.chance(0.1)) c.place(b(Blocks.LILY_PAD)); return }
        when (c.variant) {
            0 -> { c.ground(c.pick(b(Blocks.MUD), b(Blocks.GRASS_BLOCK), b(Blocks.GRASS_BLOCK)), b(Blocks.MUD)); if (c.chance(0.01)) c.place(b(Blocks.BLUE_ORCHID)) else if (c.chance(0.008)) c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM))) }
            1 -> { c.ground(c.pick(b(Blocks.MUD), b(Blocks.MYCELIUM)), b(Blocks.MUD)); if (c.chance(0.01)) c.place(b(Blocks.RED_MUSHROOM)) }
            else -> { c.ground(b(Blocks.MYCELIUM), b(Blocks.DIRT)); if (c.chance(0.003)) c.feature(c.random.nextBoolean().let { if (it) TreeFeatures.HUGE_RED_MUSHROOM else TreeFeatures.HUGE_BROWN_MUSHROOM }) else if (c.chance(0.02)) c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM))) }
        }
    }

    private fun bug(c: Ctx) {
        c.ground(if (c.noise(9.0) > 0.6) b(Blocks.MOSS_BLOCK) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        when (c.variant) {
            0 -> if (c.chance(0.006)) c.feature(TreeFeatures.AZALEA_TREE) else if (c.chance(0.12)) c.place(c.pick(b(Blocks.SHORT_GRASS), b(Blocks.FERN)))
            1 -> if (c.chance(0.012)) c.feature(VegetationFeatures.FLOWER_FLOWER_FOREST) else if (c.chance(0.05)) c.place(b(Blocks.SHORT_GRASS))
            else -> if (c.chance(0.003)) { c.place(b(Blocks.OAK_LOG)) } else if (c.chance(0.1)) c.place(b(Blocks.SHORT_GRASS))
        }
    }

    private fun grass(c: Ctx) {
        c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        when (c.variant) {
            0 -> if (c.chance(0.012)) c.feature(TreeFeatures.JUNGLE_TREE_NO_VINE) else if (c.chance(0.06)) c.place(c.pick(b(Blocks.FERN), b(Blocks.SHORT_GRASS)))
            1 -> if (c.chance(0.012)) c.feature(if (c.random.nextBoolean()) TreeFeatures.OAK else TreeFeatures.BIRCH) else if (c.chance(0.05)) c.place(b(Blocks.SHORT_GRASS))
            else -> if (c.chance(0.01)) c.feature(VegetationFeatures.FLOWER_DEFAULT) else if (c.chance(0.15)) c.place(b(Blocks.SHORT_GRASS))
        }
    }

    private fun fairy(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT)); if (c.chance(0.008)) c.feature(TreeFeatures.CHERRY) else if (c.chance(0.02)) c.feature(VegetationFeatures.FLOWER_CHERRY) }
            1 -> { c.ground(if (c.noise(8.0) > 0.5) b(Blocks.MOSS_BLOCK) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT)); if (c.chance(0.01)) c.place(b(Blocks.RED_MUSHROOM)) else if (c.chance(0.004)) c.place(b(Blocks.SHROOMLIGHT)) }
            else -> {
                if (c.noise(10.0, 9) > 0.7 && isFlatEnough(c)) { c.top(b(Blocks.WATER)); c.filler(b(Blocks.CLAY), 1); if (c.chance(0.08)) c.place(b(Blocks.LILY_PAD)) }
                else { c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT)); if (c.chance(0.08)) c.place(c.pick(b(Blocks.ALLIUM), b(Blocks.PINK_TULIP), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.AZURE_BLUET))) }
            }
        }
    }

    private fun psychic(c: Ctx) {
        val crystal = c.noise(7.0, 4) > 0.62
        when (c.variant) {
            0 -> { c.ground(if (crystal) b(Blocks.AMETHYST_BLOCK) else b(Blocks.CALCITE), b(Blocks.CALCITE)); if (crystal && c.chance(0.05)) c.place(b(Blocks.AMETHYST_CLUSTER)) }
            1 -> { c.ground(c.pick(b(Blocks.CALCITE), b(Blocks.SMOOTH_STONE)), b(Blocks.CALCITE)); if (c.chance(0.004)) c.place(b(Blocks.PURPUR_BLOCK), 2 + c.random.nextInt(3)) else if (c.chance(0.003)) c.place(b(Blocks.END_ROD)) }
            else -> { c.ground(c.pick(b(Blocks.CALCITE), b(Blocks.MOSSY_STONE_BRICKS), b(Blocks.PURPUR_BLOCK)), b(Blocks.CALCITE)); if (c.chance(0.003)) c.column(b(Blocks.PURPUR_PILLAR), 2 + c.random.nextInt(2)) }
        }
    }

    private fun steel(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(c.pick(b(Blocks.GRAVEL), b(Blocks.POLISHED_ANDESITE), b(Blocks.COBBLESTONE)), b(Blocks.STONE)); if (c.chance(0.006)) c.place(b(Blocks.IRON_BARS)) else if (c.chance(0.002)) c.place(b(Blocks.CHAIN)) }
            1 -> { c.ground(c.pick(b(Blocks.SMOOTH_STONE), b(Blocks.STONE_BRICKS)), b(Blocks.STONE)); if (c.chance(0.003)) c.column(b(Blocks.IRON_BARS), 2 + c.random.nextInt(2)) }
            else -> { c.ground(c.pick(b(Blocks.POLISHED_ANDESITE), b(Blocks.TUFF), b(Blocks.STONE)), b(Blocks.STONE)); if (c.chance(0.004)) c.column(b(Blocks.POLISHED_ANDESITE), 1 + c.random.nextInt(2)) }
        }
    }

    private fun electric(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(if (c.noise(9.0) > 0.6) b(Blocks.YELLOW_TERRACOTTA) else b(Blocks.GRASS_BLOCK), b(Blocks.DIRT)); if (c.chance(0.002)) c.place(b(Blocks.LIGHTNING_ROD)) else if (c.chance(0.06)) c.place(b(Blocks.SHORT_GRASS)) }
            1 -> { c.ground(c.pick(b(Blocks.SMOOTH_STONE), b(Blocks.STONE_BRICKS), b(Blocks.GRAVEL)), b(Blocks.STONE)); if (c.chance(0.003)) c.column(b(Blocks.IRON_BARS), 2) else if (c.chance(0.002)) c.place(b(Blocks.END_ROD)) }
            else -> { c.ground(c.pick(b(Blocks.STONE), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.ANDESITE)), b(Blocks.STONE)); if (c.chance(0.002)) c.place(b(Blocks.LIGHTNING_ROD)) }
        }
    }

    private fun ice(c: Ctx) {
        when (c.variant) {
            0 -> { c.ground(if (c.noise(8.0) > 0.6) b(Blocks.PACKED_ICE) else b(Blocks.SNOW_BLOCK), b(Blocks.SNOW_BLOCK)); if (c.chance(0.0008)) c.feature(MiscOverworldFeatures.ICE_SPIKE) else if (c.chance(0.3)) c.place(b(Blocks.SNOW)) }
            1 -> { c.ground(b(Blocks.SNOW_BLOCK), b(Blocks.DIRT)); if (c.chance(0.01)) c.feature(TreeFeatures.SPRUCE) else if (c.chance(0.4)) c.place(b(Blocks.SNOW)) }
            else -> { c.ground(if (c.noise(14.0) > 0.45) b(Blocks.PACKED_ICE) else b(Blocks.SNOW_BLOCK), b(Blocks.PACKED_ICE)); if (c.chance(0.2)) c.place(b(Blocks.SNOW)) }
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
        if (c.chance(0.05)) c.place(b(Blocks.SHORT_GRASS))
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
