package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.life.Guardian
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The Guardian's Rise, built to fit where it stands and who it's for. Its stone comes from the ground around it
 * (sandstone in the desert, packed ice on the peak, mossy stone in the forest, nether bricks on a crimson volcano…),
 * it's round and sits into the slope with the ground easing up to it, and its four pillars and their tops are in the
 * style of the guardian's type, hung with its colours.
 */
object GuardianRise {
    private const val FLAGS = Block.UPDATE_CLIENTS
    private const val R = 4

    /** The worked stone for the floor and the rougher stone for its rim, by the ground it stands on. */
    private class Stone(val floor: Block, val rim: Block, val fill: Block)

    private val STONES: Map<Block, Stone> = buildMap {
        fun put(s: Stone, vararg ground: Block) = ground.forEach { put(it, s) }
        put(Stone(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE), Blocks.SAND, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE)
        put(Stone(Blocks.SMOOTH_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE, Blocks.RED_SANDSTONE), Blocks.RED_SAND, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA)
        put(Stone(Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.PACKED_ICE), Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE)
        put(Stone(Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.DIRT), Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK)
        put(Stone(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.STONE), Blocks.STONE, Blocks.ANDESITE, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.TUFF, Blocks.GRANITE)
        put(Stone(Blocks.DEEPSLATE_TILES, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE), Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE)
        put(Stone(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE, Blocks.BLACKSTONE), Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.MAGMA_BLOCK)
        put(Stone(Blocks.NETHER_BRICKS, Blocks.NETHERRACK, Blocks.NETHERRACK), Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM)
        put(Stone(Blocks.POLISHED_BLACKSTONE, Blocks.SOUL_SOIL, Blocks.SOUL_SOIL), Blocks.SOUL_SOIL, Blocks.SOUL_SAND)
        put(Stone(Blocks.MUD_BRICKS, Blocks.PACKED_MUD, Blocks.MUD), Blocks.MUD, Blocks.PACKED_MUD, Blocks.CLAY)
        put(Stone(Blocks.POLISHED_DIORITE, Blocks.CALCITE, Blocks.CALCITE), Blocks.CALCITE, Blocks.DIORITE)
        put(Stone(Blocks.WAXED_CUT_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER, Blocks.STONE), Blocks.WAXED_WEATHERED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER)
        put(Stone(Blocks.END_STONE_BRICKS, Blocks.END_STONE, Blocks.END_STONE), Blocks.END_STONE)
        put(Stone(Blocks.PRISMARINE_BRICKS, Blocks.PRISMARINE, Blocks.SAND), Blocks.PRISMARINE)
    }
    private val DEFAULT = Stone(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.STONE)

    /** A type's pillars: what they're made of, how tall, what caps them and what sits on top. */
    private class Style(val pillar: Block, val height: Int, val cap: BlockState, val top: BlockState?, val pedestal: Block)

    private fun b(block: Block): BlockState = block.defaultBlockState()
    private val LIT_CAMPFIRE get() = b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true)
    private val SOUL_CAMPFIRE get() = b(Blocks.SOUL_CAMPFIRE).setValue(CampfireBlock.LIT, true)
    private fun leaves(block: Block) = b(block).setValue(LeavesBlock.PERSISTENT, true)

    private val STYLES: Map<String, Style> by lazy { mapOf(
        "fire" to Style(Blocks.POLISHED_BLACKSTONE_BRICKS, 4, b(Blocks.MAGMA_BLOCK), LIT_CAMPFIRE, Blocks.MAGMA_BLOCK),
        "water" to Style(Blocks.PRISMARINE_BRICKS, 4, b(Blocks.DARK_PRISMARINE), b(Blocks.SEA_LANTERN), Blocks.SEA_LANTERN),
        "grass" to Style(Blocks.MOSSY_STONE_BRICKS, 3, leaves(Blocks.FLOWERING_AZALEA_LEAVES), leaves(Blocks.AZALEA_LEAVES), Blocks.MOSS_BLOCK),
        "electric" to Style(Blocks.WAXED_CUT_COPPER, 4, b(Blocks.GLOWSTONE), b(Blocks.LIGHTNING_ROD), Blocks.WAXED_COPPER_BLOCK),
        "ice" to Style(Blocks.PACKED_ICE, 5, b(Blocks.BLUE_ICE), b(Blocks.BLUE_ICE), Blocks.BLUE_ICE),
        "fighting" to Style(Blocks.STONE_BRICKS, 3, b(Blocks.CHISELED_STONE_BRICKS), b(Blocks.ANVIL), Blocks.SMOOTH_STONE),
        "poison" to Style(Blocks.MUD_BRICKS, 3, b(Blocks.AMETHYST_BLOCK), b(Blocks.AMETHYST_CLUSTER), Blocks.AMETHYST_BLOCK),
        "ground" to Style(Blocks.SMOOTH_SANDSTONE, 5, b(Blocks.CHISELED_SANDSTONE), null, Blocks.CHISELED_SANDSTONE),
        "flying" to Style(Blocks.QUARTZ_PILLAR, 6, b(Blocks.CHISELED_QUARTZ_BLOCK), b(Blocks.WHITE_WOOL), Blocks.QUARTZ_BLOCK),
        "psychic" to Style(Blocks.PURPUR_PILLAR, 4, b(Blocks.AMETHYST_BLOCK), b(Blocks.END_ROD), Blocks.PURPUR_BLOCK),
        "bug" to Style(Blocks.HONEYCOMB_BLOCK, 3, b(Blocks.HONEY_BLOCK), b(Blocks.BEE_NEST), Blocks.HONEYCOMB_BLOCK),
        "rock" to Style(Blocks.COBBLESTONE, 4, b(Blocks.STONE), null, Blocks.POLISHED_ANDESITE),
        "ghost" to Style(Blocks.DEEPSLATE_TILES, 4, b(Blocks.CHISELED_DEEPSLATE), SOUL_CAMPFIRE, Blocks.CRYING_OBSIDIAN),
        "dragon" to Style(Blocks.OBSIDIAN, 6, b(Blocks.PURPUR_BLOCK), b(Blocks.END_ROD), Blocks.PURPUR_BLOCK),
        "dark" to Style(Blocks.POLISHED_BLACKSTONE_BRICKS, 4, b(Blocks.CRYING_OBSIDIAN), b(Blocks.SOUL_LANTERN), Blocks.CRYING_OBSIDIAN),
        "steel" to Style(Blocks.POLISHED_ANDESITE, 4, b(Blocks.IRON_BLOCK), b(Blocks.LANTERN), Blocks.IRON_BLOCK),
        "fairy" to Style(Blocks.CALCITE, 4, b(Blocks.PINK_STAINED_GLASS), b(Blocks.AMETHYST_CLUSTER), Blocks.PINK_GLAZED_TERRACOTTA),
        "normal" to Style(Blocks.QUARTZ_PILLAR, 4, b(Blocks.CHISELED_QUARTZ_BLOCK), b(Blocks.LANTERN), Blocks.QUARTZ_BLOCK),
    ) }

    private val WOOL = mapOf(
        "fire" to Blocks.RED_WOOL, "water" to Blocks.BLUE_WOOL, "grass" to Blocks.GREEN_WOOL, "electric" to Blocks.YELLOW_WOOL,
        "ice" to Blocks.LIGHT_BLUE_WOOL, "fighting" to Blocks.ORANGE_WOOL, "poison" to Blocks.PURPLE_WOOL, "ground" to Blocks.BROWN_WOOL,
        "flying" to Blocks.WHITE_WOOL, "psychic" to Blocks.PINK_WOOL, "bug" to Blocks.LIME_WOOL, "rock" to Blocks.GRAY_WOOL,
        "ghost" to Blocks.PURPLE_WOOL, "dragon" to Blocks.CYAN_WOOL, "dark" to Blocks.BLACK_WOOL, "steel" to Blocks.LIGHT_GRAY_WOOL,
        "fairy" to Blocks.PINK_WOOL, "normal" to Blocks.WHITE_WOOL,
    )

    /** Every block a Rise can be made of (so the valley's cleanup leaves it alone). */
    val BLOCKS: Set<Block> by lazy {
        (STONES.values + DEFAULT).flatMap { listOf(it.floor, it.rim) }.toSet() +
            STYLES.values.flatMap { listOfNotNull(it.pillar, it.cap.block, it.top?.block, it.pedestal) } + WOOL.values +
            setOf(Blocks.SOUL_LANTERN, Blocks.LANTERN, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.OAK_SIGN)
    }

    private fun groundY(level: net.minecraft.server.level.ServerLevel, x: Int, z: Int): Int {
        var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        repeat(16) { if (BiomeDresser.isNatural(level.getBlockState(BlockPos(x, y, z)))) return y; y-- }
        return y
    }

    fun build(level: net.minecraft.server.level.ServerLevel, s: Sanctuary, base: BlockPos, facing: Direction) {
        val r = level.random
        // what the ground around it is made of
        val around = HashMap<Block, Int>()
        for (dx in -R - 4..R + 4) for (dz in -R - 4..R + 4) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            if (d < R + 2 || d > R + 4) continue
            val st = level.getBlockState(BlockPos(base.x + dx, groundY(level, base.x + dx, base.z + dz), base.z + dz))
            if (BiomeDresser.isNatural(st)) around.merge(st.block, 1, Int::plus)
        }
        val ground = around.maxByOrNull { it.value }?.key ?: Blocks.GRASS_BLOCK
        val stone = STONES[ground] ?: DEFAULT
        val types = Guardian.types(s)
        val style = STYLES[types.firstOrNull() ?: "normal"] ?: STYLES.getValue("normal")
        val colours = types.mapNotNull { WOOL[it] }.ifEmpty { listOf(Blocks.WHITE_WOOL) }
        val y0 = base.y

        BiomeDresser.removeTreesIn(level, base, R + 10, y0 - 2, y0 + 30)
        for (dx in -R - 4..R + 4) for (dz in -R - 4..R + 4) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            val x = base.x + dx
            val z = base.z + dz
            val top = groundY(level, x, z)
            if (d <= R + 0.5) {
                // the platform: cleared above, filled with the land's own stone below
                for (y in y0 + 1..y0 + 24) {
                    val p = BlockPos(x, y, z)
                    val st = level.getBlockState(p)
                    if (!st.isAir && !ValleyBlocks.isMods(st)) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS)
                }
                for (y in y0 - 6 until y0) {
                    val p = BlockPos(x, y, z)
                    val st = level.getBlockState(p)
                    if (st.isAir || !st.fluidState.isEmpty || !st.isSolid) level.setBlock(p, b(stone.fill), FLAGS)
                }
                val edge = d > R - 0.7
                val block = when {
                    edge -> if (r.nextInt(5) == 0) ground else stone.rim
                    r.nextInt(7) == 0 -> stone.rim
                    else -> stone.floor
                }
                level.setBlock(BlockPos(x, y0, z), b(block), FLAGS)
            } else {
                // around it the ground eases to the platform's height: a block of change for each block out
                val out = (d - R).toInt().coerceAtLeast(1)
                if (top > y0 + out) for (y in y0 + out + 1..top) {
                    val p = BlockPos(x, y, z)
                    if (BiomeDresser.isNatural(level.getBlockState(p))) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS)
                }
                if (top > y0 + out) level.setBlock(BlockPos(x, y0 + out, z), b(ground), FLAGS)
                if (top < y0 - out) {
                    for (y in top + 1 until y0 - out) level.setBlock(BlockPos(x, y, z), b(stone.fill), FLAGS)
                    level.setBlock(BlockPos(x, y0 - out, z), b(ground), FLAGS)
                }
            }
        }

        // the pedestal the guardian stands on, and a low ring of the rim stone around it
        level.setBlock(base, b(stone.floor), FLAGS)
        level.setBlock(base.above(), b(style.pedestal), FLAGS)
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) level.setBlock(base.offset(dx, 0, dz), b(stone.rim), FLAGS)

        // four pillars in the guardian's style, hung with its colours
        val spots = listOf(-3 to -2, 3 to -2, -3 to 2, 3 to 2).map { (x, z) -> if (facing.axis == Direction.Axis.Z) x to z else z to x }
        for ((i, p) in spots.withIndex()) {
            val (dx, dz) = p
            // pillars of rock are rough standing stones of uneven height
            val h = style.height + if (style.pillar == Blocks.COBBLESTONE) r.nextInt(3) - 1 else 0
            for (y in 1..h) level.setBlock(base.offset(dx, y, dz), b(style.pillar), FLAGS)
            level.setBlock(base.offset(dx, h + 1, dz), style.cap, FLAGS)
            style.top?.let { t ->
                if (t.block == Blocks.WHITE_WOOL) {
                    // flags for the flyers
                    val ox = if (dx > 0) 1 else -1
                    level.setBlock(base.offset(dx, h + 2, dz), b(Blocks.QUARTZ_PILLAR), FLAGS)
                    level.setBlock(base.offset(dx + ox, h + 2, dz), b(colours[i % colours.size]), FLAGS)
                } else if (t.block == Blocks.AZALEA_LEAVES) {
                    // a little crown of leaves for the grass types
                    for (ox in -1..1) for (oz in -1..1) level.setBlock(base.offset(dx + ox, h + 2, dz + oz), t, FLAGS)
                } else level.setBlock(base.offset(dx, h + 2, dz), if (t.hasProperty(LanternBlock.HANGING)) t.setValue(LanternBlock.HANGING, false) else t, FLAGS)
            }
            // a strip of colour down the outer side
            val ox = if (abs(dx) >= abs(dz)) (if (dx > 0) 1 else -1) else 0
            val oz = if (ox == 0) (if (dz > 0) 1 else -1) else 0
            val wool = colours[i % colours.size]
            for (y in (h - 2).coerceAtLeast(2)..h) level.setBlock(base.offset(dx + ox, y, dz + oz), b(wool), FLAGS)
        }
    }
}
