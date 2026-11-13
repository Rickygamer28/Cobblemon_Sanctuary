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
    private const val G = SanctuaryDimension.GROUND_Y
    private const val WATER_Y = G - 1

    private fun b(block: Block): BlockState = block.defaultBlockState()

    /**
     * Blocks this building pass placed as decorations (a boulder, a mushroom cap, an island…) that reach over into
     * columns not built yet. Those columns must not mistake them for old ground or clutter and cut them off.
     */
    private val placed = HashMap<java.util.UUID, HashSet<Long>>()

    private fun placedFor(s: Sanctuary) = placed.getOrPut(s.id) { HashSet() }

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
    ) {
        val above: BlockPos get() = surface.above()
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

        fun top(state: BlockState) { level.setBlock(surface, state, FLAGS) }
        /** Fills below the top, deep enough to cover any exposed cliff side. */
        fun filler(state: BlockState, minDepth: Int = 3) {
            val shift = (noise(30.0, 77) * 3).toInt()
            for (i in 1..max(minDepth, exposure)) {
                val p = surface.below(i)
                if (!isNatural(level.getBlockState(p))) continue
                var st = state
                // soil only a few blocks deep; below it, rock
                if (i > 3 && (st.`is`(Blocks.DIRT) || st.`is`(Blocks.GRASS_BLOCK) || st.`is`(Blocks.MUD) || st.`is`(Blocks.PODZOL) || st.`is`(Blocks.ROOTED_DIRT))) st = b(Blocks.STONE)
                // tall cliffs show layers
                if (exposure > 4 && i > 1) {
                    val accent = STRATA[st.block]
                    if (accent != null && Math.floorMod(p.y + shift, 4) == 0) st = accent
                }
                level.setBlock(p, st, FLAGS)
            }
        }
        fun ground(top: BlockState, filler: BlockState = top) { top(top); filler(filler) }
        /** Layered ground, e.g. the coloured bands of a mesa. */
        fun bands(bands: List<BlockState>, topOverride: BlockState? = null) {
            top(topOverride ?: bands[Math.floorMod(y, bands.size)])
            for (i in 1..max(3, exposure)) {
                val p = surface.below(i)
                if (isNatural(level.getBlockState(p))) level.setBlock(p, bands[Math.floorMod(y - i, bands.size)], FLAGS)
            }
        }
        fun at(pos: BlockPos, state: BlockState) {
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
        fun feature(key: ResourceKey<ConfiguredFeature<*, *>>, protectRadius: Int = 0, protectHeight: Int = 16) {
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
        fun boulder(main: BlockState, accent: BlockState, size: Int = 1) {
            for (dx in -size..size) for (dz in -size..size) for (dy in 0..size) {
                if (dx * dx + dz * dz + dy * dy > size * size + 1) continue
                at(above.offset(dx, dy, dz), if (random.nextInt(4) == 0) accent else main)
            }
        }
        /** A cluster of crystals or columns: a tall centre with shorter ones around it. */
        fun crystalCluster(state: BlockState, tip: BlockState?, height: Int) {
            column(state, height)
            tip?.let { place(it, height + 1) }
            for (d in Direction.Plane.HORIZONTAL) {
                if (random.nextInt(3) == 0) continue
                val h = max(1, height - 1 - random.nextInt(height))
                for (i in 0 until h) at(above.relative(d).above(i), state)
                if (tip != null && random.nextBoolean()) at(above.relative(d).above(h), tip)
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
        fun deadTree(log: BlockState, branch: BlockState) {
            val h = 4 + random.nextInt(4)
            column(log, h)
            repeat(2 + random.nextInt(2)) {
                val d = Direction.Plane.HORIZONTAL.getRandomDirection(random)
                val yy = h - random.nextInt(2)
                at(surface.above(yy).relative(d), branch)
                if (random.nextBoolean()) at(surface.above(yy).relative(d, 2), branch)
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
            val lower = above
            val upper = lower.above()
            if (level.getBlockState(lower).isAir && level.getBlockState(upper).isAir) {
                level.setBlock(lower, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), FLAGS)
                level.setBlock(upper, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), FLAGS)
            }
        }
        /** A shallow pool flush with the ground: water on top, [bed] under it. */
        fun pool(bed: BlockState) {
            level.setBlock(surface, b(Blocks.WATER), FLAGS)
            level.setBlock(surface.below(), bed, FLAGS)
            for (i in 2..3) { val p = surface.below(i); if (isNatural(level.getBlockState(p))) level.setBlock(p, bed, FLAGS) }
        }
        /** Fills the column with water from above the ground up to the water line. */
        fun flood() { for (yy in y + 1..WATER_Y) level.setBlock(BlockPos(x, yy, z), b(Blocks.WATER), FLAGS) }
        fun waterTop(state: BlockState) = at(BlockPos(x, WATER_Y + 1, z), state)
        /** Places a block on the floor of a lake (into the water). */
        fun underwater(state: BlockState) { if (level.getBlockState(above).`is`(Blocks.WATER)) level.setBlock(above, state, FLAGS) }
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
        // route and landmark floors: they must count as ground too, or a rebuild leaves them (and a step) behind
        Blocks.MUD_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_DEEPSLATE,
        Blocks.DEEPSLATE_BRICKS, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.POLISHED_DIORITE,
        Blocks.BRAIN_CORAL_BLOCK, Blocks.TUBE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK,
    )

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
    )

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

    private fun isClutter(st: BlockState): Boolean =
        st.block in CLUTTER || st.block in DECOR || st.`is`(BlockTags.FLOWERS) || st.`is`(BlockTags.SAPLINGS) || st.`is`(BlockTags.CORALS) ||
            (st.fluidState.isEmpty && st.canBeReplaced() && !st.isAir)

    private fun naturalLeaf(st: BlockState) = st.`is`(BlockTags.LEAVES) && !st.getOptionalValue(LeavesBlock.PERSISTENT).orElse(true)

    // ================================================================== terrain shapes

    private fun smooth(t: Double): Double { val c = t.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
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

    private fun anchorDist(s: Sanctuary, zone: String, x: Int, z: Int, ring: List<String>): Double {
        val (ax, az) = ZoneLayout.anchor(s, zone, ring)
        return sqrt(((x - ax) * (x - ax) + (z - az) * (z - az)).toDouble())
    }

    /** A lake mask in [0, 1]: one big lake around the biome's middle, plus a few ponds. */
    private fun lake(s: Sanctuary, zone: String, x: Int, z: Int, wx: Double, wz: Double, ring: List<String>, size: Double): Double {
        val d = anchorDist(s, zone, x, z, ring)
        val main = 1 - d / (s.size.radius * size)
        val ponds = f01(s, wx, wz, 26.0, 4, 3)
        return smooth((main * 0.9 + ponds * 0.55 - 0.42) / 0.22)
    }

    /**
     * Height of a biome above the flat ground at (x, z), before blending. Coordinates are warped by noise
     * so nothing lines up with the block grid.
     */
    private fun rawOffset(s: Sanctuary, zone: String, variant: Int, x: Int, z: Int, ring: List<String>): Double {
        val wx = x + f(s, x.toDouble(), z.toDouble(), 48.0, 901, 2) * 14
        val wz = z + f(s, x.toDouble(), z.toDouble(), 48.0, 902, 2) * 14
        val xd = x.toDouble()
        val zd = z.toDouble()
        return when (zone) {
            ZoneLayout.HUB -> f(s, xd, zd, 60.0, 1, 2) * 1.6
            "water" -> when (variant) {
                2 -> { // mangrove delta: winding channels between low banks
                    val ch = ridge(s, wx, wz, 30.0, 7, 3)
                    -2.6 * smooth((ch - 0.66) / 0.22) + f01(s, xd, zd, 30.0, 8, 2) * 1.2
                }
                else -> {
                    // a smaller lake in the middle, with real land around it: grassy banks and beaches
                    val m = lake(s, zone, x, z, wx, wz, ring, if (variant == 1) 0.32 else 0.27)
                    val depth = if (variant == 1) 5.0 else 8.0
                    val land = 1.6 + f(s, wx, wz, 54.0, 9, 2) * 2.2
                    val base = land * (1 - m) + (-1.0 - depth * m) * m
                    // a few proper islands in the deep part
                    val island = smooth((f01(s, wx, wz, 32.0, 6, 2) - (if (variant == 1) 0.6 else 0.66)) / 0.14) * smooth((m - 0.55) / 0.25)
                    base + island * (2.5 - base)
                }
            }
            "grass" -> when (variant) {
                0 -> f(s, wx, wz, 38.0, 10) * 7 + 3 + ridge(s, wx, wz, 60.0, 11, 2) * 4
                1 -> f(s, wx, wz, 46.0, 12) * 7 + 2
                else -> f(s, wx, wz, 70.0, 13, 3) * 4 + 1
            }
            "bug" -> f(s, wx, wz, 40.0, 14) * 5 + f(s, xd, zd, 12.0, 15, 2) * 1.2 + 1
            "fairy" -> if (variant == 2) f(s, wx, wz, 50.0, 16) * 3 - smooth((f01(s, wx, wz, 16.0, 17, 2) - 0.66) / 0.08) * 2
                else f(s, wx, wz, 52.0, 16) * 6 + 2
            "dark" -> when (variant) {
                1 -> f(s, wx, wz, 44.0, 18) * 5 + 3 - smooth((ridge(s, wx, wz, 70.0, 19, 3) - 0.78) / 0.12) * 16
                else -> f(s, wx, wz, 40.0, 20) * 5 + 2
            }
            "ghost" -> {
                val d = anchorDist(s, zone, x, z, ring) * (1 + f(s, xd, zd, 24.0, 21, 2) * 0.25)
                max(0.0, 15 - d * 0.13) + f(s, wx, wz, 20.0, 22, 3) * 2.2
            }
            "poison" -> if (variant == 2) f(s, wx, wz, 36.0, 23) * 2.5 + 0.5 else f(s, wx, wz, 26.0, 24, 3) * 1.8 - 0.6
            "psychic" -> when (variant) {
                2 -> softTerrace(smooth(f01(s, wx, wz, 60.0, 25, 2)) * 12, 4) + 1
                1 -> f(s, wx, wz, 44.0, 26) * 5 + 2
                else -> f(s, wx, wz, 34.0, 27) * 3.5 + 1
            }
            "steel" -> if (variant == 1) ridge(s, wx, wz, 70.0, 28, 2) * 4 else Math.pow(ridge(s, wx, wz, 64.0, 29, 2), 1.5) * 16 + f(s, xd, zd, 26.0, 30, 2) * 1.5 - 2
            "electric" -> when (variant) {
                2 -> Math.pow(ridge(s, wx, wz, 60.0, 31, 2), 1.5) * 14 - 1
                else -> smooth((f01(s, wx, wz, 56.0, 32, 2) - 0.45) / 0.16) * 8 + f(s, xd, zd, 24.0, 33, 2) * 1.0
            }
            "rock" -> when (variant) {
                2 -> Math.pow(ridge(s, wx, wz, 56.0, 34, 2), 1.4) * 12 + f(s, xd, zd, 22.0, 35, 2) * 1.5
                else -> Math.pow(ridge(s, wx, wz, 64.0, 36, 3), 1.5) * 24 + f(s, xd, zd, 26.0, 37, 2) * 3 - 2
            }
            "fighting" -> if (variant == 2) softTerrace(smooth((f01(s, wx, wz, 70.0, 38, 2) - 0.35) / 0.35) * 12, 6)
                else f(s, wx, wz, 50.0, 40, 3) * 2.2 + 0.5
            "ground" -> when (variant) {
                0 -> { // dunes: long crests whose direction slowly turns
                    val dir = f(s, xd, zd, 160.0, 41, 2) * 1.4
                    val u = wx * kotlin.math.cos(dir) + wz * kotlin.math.sin(dir)
                    val crest = 1 - abs(sin(u / 30.0 + f(s, xd, zd, 90.0, 42, 2) * 2.0))
                    val field = smooth((f01(s, xd, zd, 110.0, 44, 2) - 0.4) / 0.25) // dunes only in some parts
                    Math.pow(crest, 4.0) * 5 * field + f(s, wx, wz, 120.0, 43, 2) * 1.5
                }
                1 -> softTerrace(smooth((f01(s, wx, wz, 56.0, 44, 2) - 0.48) / 0.14) * 20, 4, 3.2)
                else -> f(s, wx, wz, 36.0, 46, 3) * 1.2
            }
            "fire" -> when (variant) {
                0 -> { // volcano with a crater
                    val d = anchorDist(s, zone, x, z, ring) * (1 + f(s, xd, zd, 18.0, 47, 2) * 0.12)
                    val h = max(0.0, 40 - d * 0.82)
                    if (d < 7) h - (7 - d) * 1.7 else h + f(s, xd, zd, 16.0, 48, 2) * 1.2 * smooth(h / 8)
                }
                1 -> softTerrace(smooth((f01(s, wx, wz, 56.0, 49, 2) - 0.38) / 0.24) * 16, 5) - smooth((ridge(s, wx, wz, 80.0, 50, 2) - 0.84) / 0.1) * 9
                else -> softTerrace(f01(s, wx, wz, 40.0, 51, 2) * 6, 2)
            }
            "dragon" -> when (variant) {
                0 -> softTerrace(smooth((f01(s, wx, wz, 56.0, 52, 2) - 0.46) / 0.16) * 24, 6, 3.4)
                1 -> {
                    val d = anchorDist(s, zone, x, z, ring) * (1 + f(s, xd, zd, 26.0, 54, 2) * 0.2)
                    max(0.0, 54 - d * 0.52) * (0.72 + 0.28 * ridge(s, wx, wz, 22.0, 55, 3)) + ridge(s, wx, wz, 40.0, 56, 2) * 6
                }
                else -> f(s, wx, wz, 40.0, 57) * 6 + 2
            }
            "ice" -> when (variant) {
                2 -> -4.5 * lake(s, zone, x, z, wx, wz, ring, 0.38) + f(s, xd, zd, 20.0, 58, 2)
                0 -> f(s, wx, wz, 40.0, 59) * 8 + ridge(s, wx, wz, 22.0, 60, 3) * 5 + 2
                else -> f(s, wx, wz, 44.0, 61) * 6 + 2
            }
            else -> f(s, wx, wz, 34.0, 62) * 4 + 1
        }
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
    fun targetHeight(s: Sanctuary, x: Int, z: Int, ring: List<String>): Int {
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
        return G + off.roundToInt()
    }

    // ================================================================== main entry

    fun variantOf(s: Sanctuary, zone: String): Int =
        s.variants.getOrPut(zone) { (Noise.hash(s.seed, zone.hashCode(), 7) * 3).toInt().coerceIn(0, 2) }

    private fun key(name: String): ResourceKey<Biome> =
        ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("cobblemon_sanctuary", name))

    /** The custom biome painted under each sanctuary biome (custom types from other mods get the meadow). */
    fun biomeKey(zone: String): ResourceKey<Biome> =
        key(if (zone in ZoneLayout.BASE_RING || zone == ZoneLayout.HUB) zone else "meadow")
    val RIM_BIOME: ResourceKey<Biome> = key("rim")
    val MEADOW_BIOME: ResourceKey<Biome> = key("meadow")

    private fun exposure(s: Sanctuary, x: Int, z: Int, y: Int, ring: List<String>): Int {
        var low = y
        for ((ox, oz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) low = min(low, targetHeight(s, x + ox, z + oz, ring))
        return (y - low + 1).coerceIn(3, 48)
    }

    /** Moves the column's top to [target]: builds it up with stone or cuts natural blocks away. */
    private fun reshape(level: ServerLevel, ground: BlockPos, target: Int, keep: Set<Long>? = null): BlockPos {
        if (target > ground.y) {
            for (yy in ground.y + 1..target) {
                val p = BlockPos(ground.x, yy, ground.z)
                if (keep == null || p.asLong() !in keep) level.setBlock(p, b(Blocks.STONE), FLAGS)
            }
        } else if (target < ground.y) {
            for (yy in target + 1..ground.y) {
                val p = BlockPos(ground.x, yy, ground.z)
                if (isNatural(level.getBlockState(p))) level.setBlock(p, b(Blocks.AIR), FLAGS)
            }
        }
        return BlockPos(ground.x, target, ground.z)
    }

    private fun randomFor(s: Sanctuary, x: Int, z: Int): RandomSource =
        RandomSource.create(s.seed xor (x * 341873128712L) xor (z * 132897987541L))

    /** Dresses one column inside the sanctuary. Returns false if it can't be done right now. */
    fun dress(level: ServerLevel, s: Sanctuary, x: Int, z: Int, hit: ZoneLayout.Hit, ring: List<String>): Boolean {
        if (s.oldWallRadius in 1 until s.size.radius && abs(hit.dist - (s.oldWallRadius - 0.8)) < 1.6) clearWall(level, x, z)
        if (SanctuaryConfig.buildWall.get() && hit.dist > s.size.radius - 1.6) return wall(level, s, x, z)
        if (hit.zone !in s.unlocked) return true
        if (Landmarks.near(s, x, z)) return true

        val found = findGround(level, s, x, z) ?: return false
        if (!isNatural(level.getBlockState(found))) return true

        val zone = surfaceZone(s, hit, x, z, ring)
        val target = targetHeight(s, x, z, ring)
        val surface = reshape(level, found, target, placed[s.id])
        val ctx = Ctx(level, s, surface, randomFor(s, x, z), variantOf(s, zone), hit, exposure(s, x, z, target, ring))
        // a winding route from the hub to the biome's landmark
        if (target >= WATER_Y + 1 && onRoute(s, hit, x, z, ring)) {
            val (top, fill) = routeBlocks(hit.zone, variantOf(s, hit.zone))
            ctx.ground(if (ctx.noise(2.5, 91) > 0.82) fill else top, fill)
            if (Math.floorMod(x * 7 + z * 13, 37) == 0 && hit.dist > s.size.hubRadius + 10) {
                // a little lamp along the way, just off the path
                val side = BlockPos(x + 2, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 2, z), z)
                if (level.getBlockState(side).isAir && isNatural(level.getBlockState(side.below()))) {
                    level.setBlock(side, b(Blocks.SPRUCE_FENCE), FLAGS)
                    protect(s, side); protect(s, side.above())
                    level.setBlock(side.above(), b(if (hit.zone == "ghost" || hit.zone == "dark") Blocks.SOUL_LANTERN else Blocks.LANTERN), FLAGS)
                }
            }
            return true
        }
        cobblemonDecor(ctx, zone, target)
        // berry trees in the lusher biomes, where residents meet for meals
        if (zone in BERRY_ZONES && ctx.grid(27, 400 + BERRY_ZONES.indexOf(zone)) && ctx.roomy(6) && target >= WATER_Y + 1) ctx.berryTree()
        // a lake that reaches over the border keeps its water, whichever biome it spills into
        if (zone != "water" && target < WATER_Y && (zone == "water" || hit.neighbour == "water" || hit.zone == "water") && "water" in s.unlocked) {
            water(Ctx(level, s, surface, ctx.random, variantOf(s, "water").let { if (it == 2) 0 else it }, hit, ctx.exposure))
            return true
        }
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

    /**
     * Which biome's ground and plants a column gets. Near a border the two biomes interlock in organic patches
     * (bigger the closer to the border), and the hub's meadow reaches into the biomes the same way.
     */
    private fun surfaceZone(s: Sanctuary, hit: ZoneLayout.Hit, x: Int, z: Int, ring: List<String>): String {
        val xd = x.toDouble()
        val zd = z.toDouble()
        // the hub edge
        val hubEdge = hit.dist - ZoneLayout.hubRadiusAt(s, x, z) // > 0 outside the hub
        if (abs(hubEdge) < 9) {
            val n = f01(s, xd, zd, 11.0, 70, 3)
            val ringZone = if (hit.zone == ZoneLayout.HUB) ZoneLayout.ringZoneAt(s, x, z, ring) else hit.zone
            val pickRing = n + hubEdge / 18.0 > 0.5
            if (hit.zone == ZoneLayout.HUB) return if (pickRing && ringZone != null && ringZone in s.unlocked) ringZone else ZoneLayout.HUB
            if (!pickRing) return ZoneLayout.HUB
        }
        val nb = hit.neighbour ?: return hit.zone
        if (nb !in s.unlocked || hit.edgeDistance >= 14) return hit.zone
        // a field shared by both sides of the border, so the patches line up
        val first = if (hit.zone < nb) hit.zone else nb
        val signed = if (hit.zone == first) hit.edgeDistance else -hit.edgeDistance
        val n = f01(s, xd, zd, 9.0, 71, 3)
        return if (n + signed / 28.0 > 0.5) first else if (first == hit.zone) nb else hit.zone
    }

    /** Is (x, z) on the route that winds from the hub out to [hit]'s landmark? */
    private fun onRoute(s: Sanctuary, hit: ZoneLayout.Hit, x: Int, z: Int, ring: List<String>): Boolean {
        val zone = hit.zone
        if (zone == ZoneLayout.HUB || hit.edgeDistance < 4) return false
        val i = ring.indexOf(zone)
        if (i < 0) return false
        // the route ends at the landmark (which stays where it was built, even after the valley grows)
        val mark = s.landmarks[zone]?.takeIf { it != Landmarks.NONE && !(zone == "fire" && variantOf(s, zone) == 0) }?.let { BlockPos.of(it) }
        val (ax, az) = mark?.let { it.x to it.z } ?: ZoneLayout.anchor(s, zone, ring)
        val adx = (ax - s.center.x).toDouble()
        val adz = (az - s.center.z).toDouble()
        val anchorDist = sqrt(adx * adx + adz * adz)
        if (hit.dist < s.size.hubRadius - 5 || hit.dist > anchorDist - 3) return false
        // distance from the straight line hub → landmark, bent by noise so the route winds
        val len = anchorDist.coerceAtLeast(1.0)
        val ux = adx / len
        val uz = adz / len
        val px = (x - s.center.x).toDouble()
        val pz = (z - s.center.z).toDouble()
        val along = px * ux + pz * uz
        val side = px * uz - pz * ux
        val bend = (Noise.value(s.seed + 909 + i, along / 26.0, 0.5) - 0.5) * 16 * smooth((along - s.size.hubRadius) / 20.0) * smooth((anchorDist - along) / 14.0)
        return abs(side - bend) < 1.3
    }

    private fun routeBlocks(zone: String, variant: Int): Pair<BlockState, BlockState> = when (zone) {
        "water" -> b(Blocks.SAND) to b(Blocks.SANDSTONE)
        "ground" -> if (variant == 2) b(Blocks.MUD_BRICKS) to b(Blocks.PACKED_MUD) else b(Blocks.SMOOTH_SANDSTONE) to b(Blocks.SANDSTONE)
        "rock", "dragon" -> b(Blocks.GRAVEL) to b(Blocks.COBBLESTONE)
        "steel" -> b(Blocks.POLISHED_ANDESITE) to b(Blocks.GRAVEL)
        "fire" -> b(Blocks.POLISHED_BLACKSTONE_BRICKS) to b(Blocks.BLACKSTONE)
        "ghost" -> b(Blocks.SOUL_SOIL) to b(Blocks.COBBLED_DEEPSLATE)
        "psychic" -> b(Blocks.CALCITE) to b(Blocks.SMOOTH_BASALT)
        "ice" -> b(Blocks.PACKED_ICE) to b(Blocks.SNOW_BLOCK)
        "poison" -> b(Blocks.MUD_BRICKS) to b(Blocks.PACKED_MUD)
        "dark" -> b(Blocks.COARSE_DIRT) to b(Blocks.PODZOL)
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
        var p = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z)
        val mine = placed[s.id]
        var guard = 0
        while (guard++ < 120 && p.y > level.minBuildHeight) {
            val st = level.getBlockState(p)
            when {
                st.isAir -> p = p.below()
                mine != null && p.asLong() in mine -> p = p.below() // placed by a neighbour in this pass: keep it, look under it
                st.`is`(BlockTags.LEAVES) -> {
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
                    if (!removeTree(level, s, p)) p = p.below()
                }
                isClutter(st) -> { level.setBlock(p, air, FLAGS); p = p.below() }
                else -> return p
            }
        }
        return null
    }

    private fun nearbyLog(level: ServerLevel, from: BlockPos): BlockPos? {
        for (r in 1..5) for (dx in -r..r) for (dy in -r..1) for (dz in -r..r) {
            if (max(abs(dx), max(abs(dy), abs(dz))) != r) continue
            val p = from.offset(dx, dy, dz)
            if (level.getBlockState(p).`is`(BlockTags.LOGS)) return p
        }
        return null
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
        val spoke = (abs(dx) <= 1 || abs(dz) <= 1) && d > 6
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

    /** Water: a clear lake with beaches and islands, a turquoise coral lagoon, or a mangrove delta. */
    private fun water(c: Ctx) {
        val depth = WATER_Y - c.y
        if (c.variant == 2) { // mangrove delta
            if (depth >= 1) {
                c.flood()
                c.ground(b(Blocks.MUD), b(Blocks.MUD))
                if (c.chance(0.06)) c.waterTop(b(Blocks.LILY_PAD))
            } else {
                c.ground(c.patch(6.0, 1, b(Blocks.GRASS_BLOCK), b(Blocks.MUD), b(Blocks.GRASS_BLOCK)), b(Blocks.MUD))
                when {
                    c.grid(8, 2) && c.roomy() -> c.feature(TreeFeatures.MANGROVE)
                    c.chance(0.05) -> c.column(b(Blocks.SUGAR_CANE), 2 + c.random.nextInt(2))
                    c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            return
        }
        val lagoon = c.variant == 1
        if (depth >= 1) {
            c.flood()
            val bed = when {
                lagoon || depth <= 2 -> b(Blocks.SAND)
                depth <= 4 -> if (c.noise(14.0, 3) > 0.6) b(Blocks.CLAY) else b(Blocks.SAND)
                else -> if (c.noise(16.0, 4) > 0.55) b(Blocks.CLAY) else b(Blocks.GRAVEL)
            }
            c.ground(bed, b(Blocks.SAND))
            when {
                lagoon && depth >= 2 && c.chance(0.2) -> c.underwater(c.pick(b(Blocks.BRAIN_CORAL_BLOCK), b(Blocks.TUBE_CORAL_BLOCK), b(Blocks.FIRE_CORAL_BLOCK), b(Blocks.HORN_CORAL_BLOCK), b(Blocks.BUBBLE_CORAL_BLOCK)))
                lagoon && depth >= 2 && c.chance(0.15) -> c.underwater(c.pick(b(Blocks.BRAIN_CORAL), b(Blocks.TUBE_CORAL), b(Blocks.HORN_CORAL), b(Blocks.FIRE_CORAL_FAN)))
                lagoon && c.chance(0.06) -> c.underwater(b(Blocks.SEA_PICKLE))
                depth >= 3 && c.chance(0.1) -> { val h = 1 + c.random.nextInt(depth - 1); for (i in 1 until h) c.level.setBlock(c.surface.above(i), b(Blocks.KELP_PLANT), FLAGS); c.level.setBlock(c.surface.above(h), b(Blocks.KELP), FLAGS) }
                depth >= 2 && c.chance(0.14) -> c.underwater(b(Blocks.SEAGRASS))
                depth == 1 && c.chance(0.05) -> c.waterTop(b(Blocks.LILY_PAD))
            }
            return
        }
        val beach = c.y <= WATER_Y + 1 && c.noise(5.0, 5) > 0.2
        if (beach) {
            c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE))
            when {
                c.chance(0.05) -> c.column(b(Blocks.SUGAR_CANE), 2 + c.random.nextInt(2))
                lagoon && c.grid(11, 6) && c.roomy() -> c.feature(TreeFeatures.JUNGLE_BUSH)
            }
        } else {
            c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
            when {
                c.grid(13, 7) && c.roomy() -> c.feature(if (lagoon) TreeFeatures.JUNGLE_BUSH else TreeFeatures.BIRCH)
                c.chance(0.2) -> c.place(b(Blocks.SHORT_GRASS))
                c.chance(0.02) -> c.place(b(Blocks.BLUE_ORCHID))
            }
        }
    }

    /** Ground: golden dunes with fossils, banded mesas, or cracked mud flats. */
    private fun ground(c: Ctx) {
        when (c.variant) {
            0 -> {
                c.ground(b(Blocks.SAND), b(Blocks.SANDSTONE))
                when {
                    c.grid(23, 1) && c.roomy(8) -> { // fossil ribs
                        for (i in 0..4) {
                            c.at(c.above.east(i), b(Blocks.BONE_BLOCK).setValue(RotatedPillarBlock.AXIS, Direction.Axis.X))
                            if (i % 2 == 0) { c.at(c.above.east(i).north(), b(Blocks.BONE_BLOCK)); c.at(c.above.east(i).north().above(), b(Blocks.BONE_BLOCK)) }
                        }
                    }
                    c.grid(15, 2) && c.roomy() -> c.boulder(b(Blocks.SANDSTONE), b(Blocks.SMOOTH_SANDSTONE), 1)
                    c.chance(0.006) -> c.column(b(Blocks.CACTUS), 1 + c.random.nextInt(3))
                    c.chance(0.008) -> c.place(b(Blocks.DEAD_BUSH))
                }
            }
            1 -> {
                val bands = listOf(b(Blocks.TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA), b(Blocks.RED_TERRACOTTA), b(Blocks.WHITE_TERRACOTTA))
                c.bands(bands, if (c.y <= G + 1) b(Blocks.RED_SAND) else null)
                when {
                    c.chance(0.006) -> c.place(b(Blocks.DEAD_BUSH))
                    c.y <= G + 1 && c.chance(0.004) -> c.column(b(Blocks.CACTUS), 2)
                }
            }
            else -> {
                val crack = Math.floorMod(c.x + (c.noise(6.0, 4) * 4).toInt(), 5) == 0 || Math.floorMod(c.z + (c.noise(6.0, 5) * 4).toInt(), 5) == 0
                c.ground(if (crack) b(Blocks.MUD) else c.patch(14.0, 6, b(Blocks.PACKED_MUD), b(Blocks.COARSE_DIRT), b(Blocks.PACKED_MUD)), b(Blocks.MUD))
                when {
                    c.chance(0.005) -> c.place(b(Blocks.DEAD_BUSH))
                    c.grid(17, 7) && c.roomy() -> c.boulder(b(Blocks.PACKED_MUD), b(Blocks.MUD_BRICKS))
                }
            }
        }
    }

    /** Rock: rugged grey hills with spires, mossy boulder fields, or tuff and dripstone badlands. */
    private fun rock(c: Ctx) {
        val steep = c.exposure > 4
        if (c.variant == 2) {
            c.ground(if (steep) b(Blocks.TUFF) else c.patch(10.0, 1, b(Blocks.TUFF), b(Blocks.STONE), b(Blocks.DRIPSTONE_BLOCK), b(Blocks.GRAVEL)), b(Blocks.TUFF))
            when {
                c.grid(10, 2) && c.roomy() -> c.crystalCluster(b(Blocks.DRIPSTONE_BLOCK), b(Blocks.POINTED_DRIPSTONE), 2 + c.random.nextInt(3))
                !steep && c.chance(0.02) -> c.place(b(Blocks.POINTED_DRIPSTONE))
            }
            return
        }
        val soft = if (c.variant == 1) b(Blocks.MOSS_BLOCK) else b(Blocks.COBBLESTONE)
        c.ground(if (steep) c.patch(5.0, 3, b(Blocks.STONE), b(Blocks.ANDESITE)) else c.patch(11.0, 4, b(Blocks.STONE), b(Blocks.ANDESITE), b(Blocks.GRAVEL), b(Blocks.STONE), soft), b(Blocks.STONE))
        when {
            c.grid(9, 5) && c.roomy() -> c.feature(MiscOverworldFeatures.FOREST_ROCK, 3, 5)
            c.variant == 0 && c.grid(19, 6) && c.roomy(8) -> c.crystalCluster(b(Blocks.STONE), null, 4 + c.random.nextInt(5))
            c.variant == 1 && c.grid(7, 7) && c.roomy() -> c.boulder(b(Blocks.COBBLESTONE), b(Blocks.MOSSY_COBBLESTONE), 1 + c.random.nextInt(2))
            !steep && c.chance(0.05) -> c.place(b(Blocks.SHORT_GRASS))
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

    /** Fire: a volcano with a glowing crater, a red-rock canyon, or steaming hot springs. */
    private fun fire(c: Ctx) {
        when (c.variant) {
            0 -> {
                val (ax, az) = ZoneLayout.anchor(c.s, "fire")
                val d = sqrt(((c.x - ax) * (c.x - ax) + (c.z - az) * (c.z - az)).toDouble())
                when {
                    d < 4 -> c.ground(b(Blocks.MAGMA_BLOCK), b(Blocks.OBSIDIAN))
                    d < 6.5 -> c.ground(c.pick(b(Blocks.OBSIDIAN), b(Blocks.CRYING_OBSIDIAN), b(Blocks.BLACKSTONE), b(Blocks.MAGMA_BLOCK)), b(Blocks.BLACKSTONE))
                    else -> {
                        val vein = abs(Noise.value(c.s.seed + 9, c.x / 7.0, c.z / 7.0) - 0.5) < 0.035
                        c.ground(if (vein) b(Blocks.MAGMA_BLOCK) else c.patch(9.0, 1, b(Blocks.BASALT), b(Blocks.BLACKSTONE), b(Blocks.SMOOTH_BASALT), b(Blocks.BLACKSTONE)), b(Blocks.BLACKSTONE))
                        when {
                            vein -> {}
                            c.grid(9, 2) && c.roomy() -> c.crystalCluster(b(Blocks.BASALT), null, 2 + c.random.nextInt(4))
                            c.chance(0.01) -> c.place(b(Blocks.DEAD_BUSH))
                        }
                    }
                }
            }
            1 -> {
                val bands = listOf(b(Blocks.RED_TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA), b(Blocks.RED_SANDSTONE), b(Blocks.TERRACOTTA), b(Blocks.ORANGE_TERRACOTTA))
                c.bands(bands, if (c.y <= G + 1) b(Blocks.RED_SAND) else null)
                when {
                    c.chance(0.01) -> c.place(b(Blocks.DEAD_BUSH))
                    c.y <= G + 1 && c.grid(12, 3) && c.roomy() -> c.boulder(b(Blocks.RED_SANDSTONE), b(Blocks.MAGMA_BLOCK))
                }
            }
            else -> {
                if (c.noise(9.0, 5) > 0.62 && c.exposure <= 3) { c.pool(b(Blocks.SMOOTH_BASALT)); return }
                c.ground(c.patch(8.0, 6, b(Blocks.SMOOTH_BASALT), b(Blocks.TUFF), b(Blocks.CALCITE), b(Blocks.SMOOTH_BASALT)), b(Blocks.STONE))
                when {
                    c.grid(10, 7) && c.roomy() -> c.boulder(b(Blocks.SMOOTH_BASALT), b(Blocks.BLACKSTONE))
                    c.chance(0.006) -> c.place(b(Blocks.DEAD_BUSH))
                }
            }
        }
    }

    /** Dragon: giant mesas with bone arches, a towering snowy peak, or ancient ruins. */
    private fun dragon(c: Ctx) {
        when (c.variant) {
            0 -> {
                val bands = listOf(b(Blocks.ORANGE_TERRACOTTA), b(Blocks.TERRACOTTA), b(Blocks.YELLOW_TERRACOTTA), b(Blocks.BROWN_TERRACOTTA), b(Blocks.LIGHT_GRAY_TERRACOTTA))
                c.bands(bands, if (c.exposure <= 3 && c.noise(10.0, 1) > 0.5) b(Blocks.COARSE_DIRT) else null)
                if (c.grid(26, 2) && c.roomy(10)) { // bone arch
                    val h = 4 + c.random.nextInt(3)
                    c.column(b(Blocks.BONE_BLOCK), h)
                    for (i in 1..4) c.at(c.surface.above(h).east(i), b(Blocks.BONE_BLOCK).setValue(RotatedPillarBlock.AXIS, Direction.Axis.X))
                    for (i in 1..h) c.at(c.surface.above(i).east(5), b(Blocks.BONE_BLOCK))
                } else if (c.chance(0.006)) c.place(b(Blocks.DEAD_BUSH))
            }
            1 -> {
                val rise = c.y - G
                when {
                    rise > 30 + c.noise(12.0, 3) * 6 -> { c.ground(b(Blocks.SNOW_BLOCK), b(Blocks.STONE)); if (c.chance(0.4)) c.place(b(Blocks.SNOW)) }
                    rise > 12 || c.exposure > 4 -> c.ground(c.patch(7.0, 4, b(Blocks.STONE), b(Blocks.CALCITE), b(Blocks.STONE), b(Blocks.ANDESITE)), b(Blocks.STONE))
                    else -> {
                        c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                        when {
                            c.grid(10, 5) && c.roomy() -> c.feature(TreeFeatures.SPRUCE)
                            c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
                        }
                    }
                }
            }
            else -> {
                c.ground(if (c.noise(6.0, 6) > 0.84) b(Blocks.MOSSY_STONE_BRICKS) else c.patch(12.0, 6, b(Blocks.GRASS_BLOCK), b(Blocks.GRASS_BLOCK), b(Blocks.COARSE_DIRT)), b(Blocks.DIRT))
                when {
                    c.grid(18, 7) && c.roomy(8) -> c.ruinWall(b(Blocks.MOSSY_STONE_BRICKS), 5 + c.random.nextInt(5), 5)
                    c.grid(14, 8) && c.roomy() -> { c.column(b(Blocks.CHISELED_STONE_BRICKS), 4); c.place(b(Blocks.BONE_BLOCK), 5) }
                    c.chance(0.12) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
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

    /** Ghost: a graveyard on a hill, a forest of dead trees, or haunted soul ruins. */
    private fun ghost(c: Ctx) {
        when (c.variant) {
            0 -> {
                val path = abs(Noise.value(c.s.seed + 11, c.x / 18.0, c.z / 18.0) - 0.5) < 0.04
                c.ground(if (path) b(Blocks.SOUL_SOIL) else c.patch(9.0, 1, b(Blocks.PODZOL), b(Blocks.COARSE_DIRT), b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
                when {
                    path -> {}
                    Math.floorMod(c.x, 4) == 0 && Math.floorMod(c.z, 6) == 0 && c.roomy() && c.exposure <= 2 && c.noise(34.0, 2) > 0.68 -> c.gravestone()
                    c.grid(12, 3) && c.roomy() -> c.deadTree(b(Blocks.STRIPPED_DARK_OAK_LOG), b(Blocks.DARK_OAK_FENCE))
                    c.grid(10, 4) && c.roomy() -> c.lampPost(b(Blocks.SPRUCE_FENCE), b(Blocks.SOUL_LANTERN))
                    c.chance(0.004) -> c.place(b(Blocks.COBWEB))
                    c.chance(0.02) -> c.place(b(Blocks.DEAD_BUSH))
                }
            }
            1 -> {
                c.ground(c.patch(9.0, 5, b(Blocks.PODZOL), b(Blocks.SOUL_SOIL), b(Blocks.PODZOL), b(Blocks.COARSE_DIRT)), b(Blocks.DIRT))
                when {
                    c.grid(6, 6) && c.roomy() -> c.deadTree(b(Blocks.STRIPPED_SPRUCE_LOG), b(Blocks.SPRUCE_FENCE))
                    c.grid(23, 7) && c.roomy() -> c.place(b(Blocks.JACK_O_LANTERN))
                    c.chance(0.005) -> c.place(b(Blocks.COBWEB))
                    c.chance(0.03) -> c.place(b(Blocks.DEAD_BUSH))
                }
            }
            else -> {
                c.ground(c.patch(8.0, 8, b(Blocks.SOUL_SOIL), b(Blocks.SOUL_SAND), b(Blocks.COBBLED_DEEPSLATE), b(Blocks.SOUL_SOIL)), b(Blocks.SOUL_SOIL))
                when {
                    c.grid(16, 9) && c.roomy(8) -> c.ruinWall(b(Blocks.DEEPSLATE_TILES), 4 + c.random.nextInt(4), 4)
                    c.grid(9, 10) && c.roomy() -> c.lampPost(b(Blocks.COBBLED_DEEPSLATE_WALL), b(Blocks.SOUL_LANTERN))
                    c.chance(0.01) -> c.place(b(Blocks.WITHER_ROSE))
                }
            }
        }
    }

    /** Poison: a murky swamp, a toxic purple bog, or a marsh of giant mushrooms. */
    private fun poison(c: Ctx) {
        val puddle = c.noise(9.0, 3) > 0.64 && c.exposure <= 3
        when (c.variant) {
            0 -> {
                if (puddle) { c.pool(b(Blocks.MUD)); if (c.chance(0.15)) c.place(b(Blocks.LILY_PAD)); return }
                c.ground(c.patch(7.0, 1, b(Blocks.GRASS_BLOCK), b(Blocks.MUD), b(Blocks.GRASS_BLOCK)), b(Blocks.MUD))
                when {
                    c.grid(9, 2) && c.roomy() -> c.feature(TreeFeatures.SWAMP_OAK)
                    c.chance(0.02) -> c.place(b(Blocks.BLUE_ORCHID))
                    c.chance(0.015) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                    c.chance(0.12) -> c.place(b(Blocks.SHORT_GRASS))
                }
            }
            1 -> {
                if (puddle) { c.pool(b(Blocks.PURPLE_TERRACOTTA)); return }
                c.ground(c.patch(8.0, 4, b(Blocks.MYCELIUM), b(Blocks.PURPLE_TERRACOTTA), b(Blocks.MUD), b(Blocks.MAGENTA_TERRACOTTA)), b(Blocks.MUD))
                when {
                    c.grid(10, 5) && c.roomy() -> c.crystalCluster(b(Blocks.PURPLE_TERRACOTTA), b(Blocks.AMETHYST_CLUSTER), 1 + c.random.nextInt(2))
                    c.chance(0.02) -> c.place(b(Blocks.RED_MUSHROOM))
                }
            }
            else -> {
                if (puddle && c.chance(0.5)) { c.pool(b(Blocks.MUD)); return }
                c.ground(b(Blocks.MYCELIUM), b(Blocks.DIRT))
                when {
                    c.grid(18, 6) && c.roomy(6) -> c.feature(if (c.random.nextBoolean()) TreeFeatures.HUGE_RED_MUSHROOM else TreeFeatures.HUGE_BROWN_MUSHROOM, 3, 15)
                    c.chance(0.04) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                }
            }
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

    /** Grass: a lush jungle, a leafy forest, or a meadow of tall grass. */
    private fun grass(c: Ctx) {
        c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
        when (c.variant) {
            0 -> when {
                c.grid(11, 1) && c.roomy(7) -> c.feature(TreeFeatures.MEGA_JUNGLE_TREE)
                c.grid(6, 2) && c.roomy() -> c.feature(TreeFeatures.JUNGLE_TREE_NO_VINE)
                c.grid(5, 3) && c.roomy(3) -> c.feature(TreeFeatures.JUNGLE_BUSH)
                c.noise(9.0, 4) > 0.75 && c.chance(0.5) -> c.column(b(Blocks.BAMBOO), 4 + c.random.nextInt(6))
                c.chance(0.008) -> c.place(b(Blocks.MELON))
                c.chance(0.3) -> c.place(c.pick(b(Blocks.FERN), b(Blocks.SHORT_GRASS)))
            }
            1 -> when {
                c.grid(6, 5) && c.roomy() -> c.feature(
                    when (c.random.nextInt(3)) { 0 -> TreeFeatures.FANCY_OAK; 1 -> TreeFeatures.OAK; else -> TreeFeatures.BIRCH },
                )
                c.chance(0.2) -> c.place(b(Blocks.SHORT_GRASS))
                c.chance(0.02) -> c.place(c.pick(b(Blocks.POPPY), b(Blocks.DANDELION)))
            }
            else -> when {
                c.grid(21, 6) && c.roomy() -> c.feature(TreeFeatures.FANCY_OAK)
                c.chance(0.3) -> c.tallPlant(b(Blocks.TALL_GRASS))
                c.chance(0.06) -> c.place(c.patch(8.0, 7, b(Blocks.CORNFLOWER), b(Blocks.ALLIUM), b(Blocks.OXEYE_DAISY), b(Blocks.AZURE_BLUET)))
                c.chance(0.3) -> c.place(b(Blocks.SHORT_GRASS))
            }
        }
    }

    /** Fairy: a cherry blossom grove, a glowing mushroom glade, or flower ponds. */
    private fun fairy(c: Ctx) {
        when (c.variant) {
            0 -> {
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.grid(8, 1) && c.roomy(6) -> c.feature(TreeFeatures.CHERRY)
                    c.noise(6.0, 2) > 0.45 && c.chance(0.6) -> c.place(b(Blocks.PINK_PETALS))
                    c.chance(0.04) -> c.place(c.pick(b(Blocks.PINK_TULIP), b(Blocks.ALLIUM), b(Blocks.LILY_OF_THE_VALLEY)))
                }
            }
            1 -> {
                c.ground(c.patch(9.0, 3, b(Blocks.MOSS_BLOCK), b(Blocks.GRASS_BLOCK), b(Blocks.MOSS_BLOCK)), b(Blocks.DIRT))
                when {
                    c.grid(9, 4) && c.roomy() -> { // glowing toadstool
                        val h = 3 + c.random.nextInt(3)
                        c.column(b(Blocks.MUSHROOM_STEM), h)
                        c.place(b(Blocks.SHROOMLIGHT), h + 1)
                        for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) c.at(c.surface.offset(dx, h + 1, dz), b(Blocks.RED_MUSHROOM_BLOCK))
                        c.place(b(Blocks.RED_MUSHROOM_BLOCK), h + 2)
                    }
                    c.grid(11, 5) && c.roomy() -> c.feature(TreeFeatures.AZALEA_TREE)
                    c.chance(0.04) -> c.place(c.pick(b(Blocks.RED_MUSHROOM), b(Blocks.BROWN_MUSHROOM)))
                    c.chance(0.1) -> c.place(b(Blocks.MOSS_CARPET))
                }
            }
            else -> {
                if (c.noise(10.0, 9) > 0.64 && c.exposure <= 3) { c.pool(b(Blocks.CLAY)); if (c.chance(0.15)) c.place(b(Blocks.LILY_PAD)); return }
                c.ground(b(Blocks.GRASS_BLOCK), b(Blocks.DIRT))
                when {
                    c.grid(15, 6) && c.roomy() -> c.feature(TreeFeatures.CHERRY)
                    c.chance(0.2) -> c.place(c.patch(6.0, 7, b(Blocks.ALLIUM), b(Blocks.PINK_TULIP), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.AZURE_BLUET), b(Blocks.WHITE_TULIP)))
                    c.chance(0.05) -> c.tallPlant(c.pick(b(Blocks.LILAC), b(Blocks.PEONY)))
                }
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

    /** Electric: stormy plains with lightning rods, power station ruins, or a copper ridge. */
    private fun electric(c: Ctx) {
        when (c.variant) {
            0 -> {
                c.ground(c.patch(13.0, 1, b(Blocks.GRASS_BLOCK), b(Blocks.GRASS_BLOCK), b(Blocks.YELLOW_TERRACOTTA)), b(Blocks.DIRT))
                when {
                    c.grid(13, 2) && c.roomy() -> { c.column(b(Blocks.POLISHED_ANDESITE), 3); c.place(b(Blocks.LIGHTNING_ROD), 4) }
                    c.chance(0.25) -> c.place(b(Blocks.SHORT_GRASS))
                    c.chance(0.02) -> c.place(b(Blocks.DANDELION))
                }
            }
            1 -> {
                c.ground(c.patch(9.0, 3, b(Blocks.SMOOTH_STONE), b(Blocks.STONE_BRICKS), b(Blocks.GRAVEL)), b(Blocks.STONE))
                when {
                    c.grid(11, 4) && c.roomy() -> { c.column(b(Blocks.IRON_BARS), 4); c.place(b(Blocks.END_ROD), 5) }
                    c.grid(9, 5) && c.roomy() -> c.place(b(Blocks.REDSTONE_LAMP))
                    c.grid(17, 6) && c.roomy(8) -> c.ruinWall(b(Blocks.STONE_BRICKS), 3 + c.random.nextInt(4), 3)
                }
            }
            else -> {
                val vein = abs(c.noise(22.0, 13) - 0.5) < 0.03
                c.ground(when {
                    vein -> b(Blocks.EXPOSED_COPPER)
                    c.exposure > 4 -> c.patch(16.0, 6, b(Blocks.STONE), b(Blocks.TUFF))
                    else -> c.patch(18.0, 6, b(Blocks.STONE), b(Blocks.TUFF), b(Blocks.STONE), b(Blocks.CALCITE))
                }, b(Blocks.STONE))
                if (c.grid(10, 7) && c.roomy()) c.crystalCluster(b(Blocks.EXPOSED_COPPER), b(Blocks.LIGHTNING_ROD), 2 + c.random.nextInt(3))
            }
        }
    }

    /** Ice: a glacier with ice spikes, a snowy spruce forest, or a frozen lake. */
    private fun ice(c: Ctx) {
        when (c.variant) {
            0 -> {
                c.ground(c.patch(9.0, 1, b(Blocks.SNOW_BLOCK), b(Blocks.SNOW_BLOCK), b(Blocks.PACKED_ICE), b(Blocks.BLUE_ICE)), b(Blocks.PACKED_ICE))
                when {
                    c.grid(16, 2) && c.roomy(8) -> c.feature(MiscOverworldFeatures.ICE_SPIKE, 4, 34)
                    c.grid(8, 3) && c.roomy() -> c.crystalCluster(b(Blocks.PACKED_ICE), null, 2 + c.random.nextInt(4))
                    c.chance(0.5) -> c.place(b(Blocks.SNOW))
                }
            }
            1 -> {
                c.ground(b(Blocks.SNOW_BLOCK), b(Blocks.DIRT))
                when {
                    c.grid(6, 4) && c.roomy() -> c.feature(TreeFeatures.SPRUCE)
                    c.chance(0.6) -> c.place(b(Blocks.SNOW))
                }
            }
            else -> {
                if (c.y < WATER_Y) { // frozen lake: ice on top, water under it
                    c.ground(b(Blocks.PACKED_ICE), b(Blocks.PACKED_ICE))
                    for (yy in c.y + 1 until WATER_Y) c.level.setBlock(BlockPos(c.x, yy, c.z), b(Blocks.WATER), FLAGS)
                    c.level.setBlock(BlockPos(c.x, WATER_Y, c.z), b(Blocks.ICE), FLAGS)
                    return
                }
                c.ground(c.patch(10.0, 5, b(Blocks.SNOW_BLOCK), b(Blocks.SNOW_BLOCK), b(Blocks.PACKED_ICE)), b(Blocks.PACKED_ICE))
                when {
                    c.grid(12, 6) && c.roomy() -> c.feature(TreeFeatures.SPRUCE)
                    c.chance(0.4) -> c.place(b(Blocks.SNOW))
                }
            }
        }
    }

    /** Biomes for custom types from other mods: tinted ground in a colour picked from the type's name. */
    private fun custom(c: Ctx, zone: String) {
        val tints = listOf(
            Blocks.PURPLE_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
            Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
        )
        val tint = b(tints[Math.floorMod(zone.hashCode(), tints.size)])
        c.ground(c.patch(10.0, 1, b(Blocks.GRASS_BLOCK), tint, b(Blocks.GRASS_BLOCK)), b(Blocks.DIRT))
        when {
            c.grid(10, 2) && c.roomy() -> c.crystalCluster(tint, null, 2 + c.random.nextInt(3))
            c.chance(0.15) -> c.place(b(Blocks.SHORT_GRASS))
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
