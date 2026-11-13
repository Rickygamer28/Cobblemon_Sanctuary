package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryDimension
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CarvedPumpkinBlock
import net.minecraft.world.level.block.PinkPetalsBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import kotlin.math.abs

/**
 * Little spots around each biome that residents go to and do something with: a mossy log to sit on, a campfire to
 * warm up at, a rock pool to splash in, a training dummy to punch… Each one is remembered in the sanctuary so
 * residents can find it (and pick one as their favourite).
 */
object Props {
    /** What a resident does at a spot. */
    enum class Action { SIT, NAP, WARM, SPLASH, SNIFF, ROLL, TRAIN, CHARGE, SHIMMER, HAUNT, PICNIC, DIG, PLAY, EAT }

    class Kind(
        val id: String,
        /** How it's described, e.g. "a mossy log". */
        val label: String,
        val action: Action,
        /** Residents stand on top of it (a log, a boulder, a blanket) rather than next to it. */
        val perch: Boolean,
        /** Types that love it and visit it more often. */
        val types: Set<String>,
        /** Others nearby come along (a picnic, a campfire). */
        val gathering: Boolean = false,
        /** Somewhere soft to sleep. */
        val bed: Boolean = false,
        /** Only on dirt or grass (flowers and the like). */
        val soil: Boolean = false,
        /** Builds it on the column; returns the spot residents walk to (null if it couldn't be built). */
        val build: ((BiomeDresser.Ctx) -> BlockPos?)? = null,
        /** Placed by a player: every resident likes it. */
        val placed: Boolean = false,
    )

    private fun b(block: Block): BlockState = block.defaultBlockState()
    private fun axisLog(block: Block, axis: Direction.Axis) = b(block).setValue(RotatedPillarBlock.AXIS, axis)

    private val HORIZONTAL = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val AROUND = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

    // ------------------------------------------------------------------ building blocks

    private fun BiomeDresser.Ctx.log(block: Block, extra: (BlockPos) -> Unit = {}): BlockPos {
        val axis = if (random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z
        val len = 3 + random.nextInt(2)
        for (i in -1 until len - 1) {
            val p = if (axis == Direction.Axis.X) above.east(i) else above.south(i)
            at(p, axisLog(block, axis))
            extra(p.above())
        }
        return above
    }

    private fun BiomeDresser.Ctx.mound(main: BlockState, accent: BlockState, topper: BlockState?): BlockPos {
        at(above, main)
        at(above.above(), if (random.nextInt(3) == 0) accent else main)
        for ((dx, dz) in HORIZONTAL) at(above.offset(dx, 0, dz), if (random.nextInt(3) == 0) accent else main)
        for ((dx, dz) in AROUND.drop(4)) if (random.nextBoolean()) at(above.offset(dx, 0, dz), main)
        topper?.let { at(above.above(2), it) }
        return above.above()
    }

    private fun BiomeDresser.Ctx.ring(radius: Int, every: Int, state: () -> BlockState) {
        var i = 0
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val d2 = dx * dx + dz * dz
            if (d2 < radius * radius - radius || d2 > radius * radius + radius) continue
            if (i++ % every == 0) at(above.offset(dx, 0, dz), state())
        }
    }

    private fun BiomeDresser.Ctx.around(count: Int, state: () -> BlockState) {
        for ((dx, dz) in AROUND.shuffled(java.util.Random(random.nextLong())).take(count)) at(above.offset(dx, 0, dz), state())
    }

    private fun flower(c: BiomeDresser.Ctx, vararg blocks: Block) = b(blocks[c.random.nextInt(blocks.size)])

    private fun petals(c: BiomeDresser.Ctx) =
        b(Blocks.PINK_PETALS).setValue(PinkPetalsBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(c.random)).setValue(PinkPetalsBlock.AMOUNT, 1 + c.random.nextInt(4))

    // ------------------------------------------------------------------ the kinds

    val KINDS: Map<String, Kind> = listOf(
        Kind("mossy_log", "a mossy log", Action.SIT, true, setOf("grass", "bug", "normal", "fairy")) { c ->
            c.log(Blocks.OAK_LOG) { if (c.random.nextInt(2) == 0) c.at(it, b(Blocks.MOSS_CARPET)) }
        },
        Kind("rotten_log", "an old rotting log", Action.SIT, true, setOf("bug", "poison", "dark", "ghost")) { c ->
            val spot = c.log(Blocks.SPRUCE_LOG) { if (c.random.nextInt(3) == 0) c.at(it, flower(c, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM)) }
            c.at(c.above.north(), b(Blocks.BROWN_MUSHROOM))
            spot
        },
        Kind("hollow_log", "a hollow dark log", Action.SIT, true, setOf("dark", "ghost", "bug")) { c ->
            c.log(Blocks.DARK_OAK_LOG) { if (c.random.nextInt(2) == 0) c.at(it, if (c.random.nextInt(3) == 0) b(Blocks.RED_MUSHROOM) else b(Blocks.MOSS_CARPET)) }
        },
        Kind("driftwood", "a piece of driftwood", Action.SIT, true, setOf("water", "normal", "flying")) { c -> c.log(Blocks.STRIPPED_BIRCH_LOG) },
        Kind("stump", "a tree stump", Action.SIT, true, setOf("grass", "bug", "normal")) { c ->
            c.place(b(Blocks.OAK_LOG))
            c.around(2) { if (c.random.nextBoolean()) b(Blocks.MOSS_CARPET) else b(Blocks.BROWN_MUSHROOM) }
            c.above
        },
        Kind("flower_bed", "a bed of flowers", Action.SNIFF, false, setOf("grass", "bug", "fairy", "normal"), soil = true) { c ->
            for (dx in -2..2) for (dz in -2..2) {
                if (dx * dx + dz * dz > 5 || c.random.nextInt(5) < 2) continue
                c.at(c.above.offset(dx, 0, dz), c.patch(4.0, 820, b(Blocks.POPPY), b(Blocks.DANDELION), b(Blocks.ALLIUM), b(Blocks.AZURE_BLUET), b(Blocks.OXEYE_DAISY), b(Blocks.CORNFLOWER), b(Blocks.PINK_TULIP)))
            }
            c.at(c.above, b(Blocks.OXEYE_DAISY))
            c.above
        },
        Kind("flower_ring", "a fairy ring of flowers", Action.SNIFF, false, setOf("fairy", "psychic", "grass"), soil = true) { c ->
            c.ring(2, 1) { flower(c, Blocks.ALLIUM, Blocks.PINK_TULIP, Blocks.LILY_OF_THE_VALLEY, Blocks.AZURE_BLUET) }
            c.at(c.above, petals(c))
            c.above
        },
        Kind("moss_mound", "a soft mossy mound", Action.NAP, true, setOf("grass", "bug", "fairy", "normal"), bed = true) { c ->
            c.mound(b(Blocks.MOSS_BLOCK), b(Blocks.MOSS_BLOCK), b(Blocks.MOSS_CARPET))
        },
        Kind("picnic", "a picnic blanket", Action.PICNIC, true, setOf("normal", "fairy", "grass"), gathering = true, bed = true) { c ->
            for (dx in -1..1) for (dz in -1..1) c.at(c.above.offset(dx, 0, dz), b(if (Math.floorMod(dx + dz, 2) == 0) Blocks.RED_CARPET else Blocks.WHITE_CARPET))
            c.at(c.above.offset(2, 0, 0), b(Blocks.HAY_BLOCK))
            c.above
        },
        Kind("campfire", "a campfire", Action.WARM, false, setOf("fire", "normal", "ground", "fighting"), gathering = true) { c ->
            c.place(b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true))
            for ((dx, dz) in HORIZONTAL) {
                val axis = if (dx != 0) Direction.Axis.Z else Direction.Axis.X
                c.at(c.above.offset(dx * 2, 0, dz * 2), axisLog(Blocks.STRIPPED_OAK_LOG, axis))
            }
            c.above
        },
        Kind("warm_rocks", "some warm volcanic rocks", Action.WARM, false, setOf("fire", "ground", "dragon")) { c ->
            c.top(b(Blocks.MAGMA_BLOCK))
            c.around(5) { if (c.random.nextBoolean()) b(Blocks.BLACKSTONE) else b(Blocks.SMOOTH_BASALT) }
            c.surface
        },
        Kind("rock_pool", "a little rock pool", Action.SPLASH, false, setOf("water", "ice", "bug", "grass")) { c ->
            c.pool(b(Blocks.GRAVEL))
            c.at(c.above, b(Blocks.LILY_PAD))
            c.around(6) { if (c.random.nextBoolean()) b(Blocks.COBBLESTONE) else b(Blocks.MOSSY_COBBLESTONE) }
            c.surface
        },
        Kind("muddy_puddle", "a muddy puddle", Action.ROLL, false, setOf("poison", "ground", "water", "bug")) { c ->
            c.pool(b(Blocks.MUD))
            c.around(4) { if (c.random.nextBoolean()) b(Blocks.BROWN_MUSHROOM) else b(Blocks.MUD) }
            c.surface
        },
        Kind("frozen_pond", "a frozen pond", Action.ROLL, false, setOf("ice", "water")) { c ->
            c.pool(b(Blocks.PACKED_ICE))
            c.top(b(Blocks.ICE))
            c.around(5) { b(Blocks.SNOW) }
            c.surface
        },
        Kind("snow_pile", "a pile of fresh snow", Action.ROLL, true, setOf("ice"), bed = true) { c ->
            c.mound(b(Blocks.SNOW_BLOCK), b(Blocks.SNOW_BLOCK), b(Blocks.SNOW))
        },
        Kind("lightning_rod", "a lightning rod", Action.CHARGE, false, setOf("electric", "steel")) { c ->
            c.place(b(Blocks.WEATHERED_COPPER)); c.place(b(Blocks.LIGHTNING_ROD), 2)
            c.above
        },
        Kind("copper_rocks", "some copper rocks", Action.CHARGE, true, setOf("electric", "steel")) { c ->
            c.boulder(b(Blocks.EXPOSED_COPPER), b(Blocks.WEATHERED_COPPER), 1)
            c.above
        },
        Kind("crystal", "a glowing crystal", Action.SHIMMER, false, setOf("psychic", "fairy", "rock", "dragon")) { c ->
            c.place(b(Blocks.AMETHYST_BLOCK)); c.place(b(Blocks.AMETHYST_CLUSTER), 2)
            c.around(3) { if (c.random.nextBoolean()) b(Blocks.AMETHYST_CLUSTER) else b(Blocks.LARGE_AMETHYST_BUD) }
            c.above
        },
        Kind("meditation_stone", "a meditation stone", Action.SHIMMER, true, setOf("psychic", "fighting", "fairy")) { c ->
            for (dx in -1..1) for (dz in -1..1) c.at(c.above.offset(dx, 0, dz), b(Blocks.SMOOTH_STONE_SLAB).setValue(SlabBlock.TYPE, SlabType.BOTTOM))
            for ((dx, dz) in listOf(2 to 0, -2 to 0)) c.at(c.above.offset(dx, 0, dz), b(Blocks.WHITE_CANDLE).setValue(CandleBlock.LIT, true))
            c.above
        },
        Kind("mushroom_ring", "a ring of mushrooms", Action.SNIFF, false, setOf("dark", "poison", "ghost", "fairy", "bug")) { c ->
            c.ring(2, 1) { flower(c, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM) }
            c.at(c.above, b(Blocks.MOSS_CARPET))
            c.above
        },
        Kind("soul_lantern", "a soul lantern", Action.HAUNT, false, setOf("ghost", "dark")) { c ->
            c.lampPost(b(Blocks.DARK_OAK_FENCE), b(Blocks.SOUL_LANTERN), 2)
            c.around(2) { b(Blocks.COBBLESTONE_WALL) }
            c.above
        },
        Kind("old_bones", "a pile of old bones", Action.DIG, false, setOf("ghost", "ground", "dark", "dragon")) { c ->
            c.place(axisLog(Blocks.BONE_BLOCK, if (c.random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z))
            c.around(3) { axisLog(Blocks.BONE_BLOCK, listOf(Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z)[c.random.nextInt(3)]) }
            c.above
        },
        Kind("boulder", "a big boulder", Action.SIT, true, setOf("rock", "ground", "fighting", "steel")) { c ->
            c.boulder(b(Blocks.STONE), b(Blocks.MOSSY_COBBLESTONE), 1)
            c.above
        },
        Kind("stone_circle", "a circle of standing stones", Action.SIT, true, setOf("rock", "psychic", "fairy", "normal"), gathering = true) { c ->
            c.top(b(Blocks.MOSSY_COBBLESTONE))
            for (i in 0 until 5) {
                val a = Math.PI * 2 * i / 5 + c.random.nextDouble() * 0.4
                val p = c.above.offset((Math.cos(a) * 3).toInt(), 0, (Math.sin(a) * 3).toInt())
                c.at(p, b(Blocks.STONE)); c.at(p.above(), if (c.random.nextBoolean()) b(Blocks.STONE) else b(Blocks.ANDESITE))
            }
            c.surface
        },
        Kind("sand_mound", "a sandy mound", Action.ROLL, true, setOf("ground", "fire"), bed = true) { c ->
            c.mound(b(Blocks.SAND), b(Blocks.SANDSTONE), b(Blocks.DEAD_BUSH))
        },
        Kind("scrap_pile", "a heap of scrap metal", Action.DIG, false, setOf("steel", "electric")) { c ->
            c.place(b(Blocks.CHIPPED_ANVIL))
            c.around(3) { if (c.random.nextBoolean()) b(Blocks.IRON_BARS) else b(Blocks.RAW_IRON_BLOCK) }
            c.above
        },
        Kind("iron_rock", "an iron-rich rock", Action.DIG, true, setOf("steel", "rock")) { c ->
            c.boulder(b(Blocks.STONE), b(Blocks.RAW_IRON_BLOCK), 1)
            c.above
        },
        Kind("training_dummy", "a training dummy", Action.TRAIN, false, setOf("fighting", "normal", "steel")) { c ->
            c.place(b(Blocks.SPRUCE_FENCE)); c.place(b(Blocks.HAY_BLOCK), 2)
            c.place(b(Blocks.CARVED_PUMPKIN).setValue(CarvedPumpkinBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(c.random)), 3)
            c.above
        },
        Kind("lifting_rock", "a heavy training rock", Action.TRAIN, false, setOf("fighting", "rock")) { c ->
            c.place(b(Blocks.COBBLESTONE)); c.place(b(Blocks.STONE), 2)
            c.around(2) { b(Blocks.COBBLESTONE) }
            c.above
        },
        Kind("ancient_pillar", "a crumbling old pillar", Action.SHIMMER, false, setOf("dragon", "psychic", "rock", "ghost")) { c ->
            val h = 3 + c.random.nextInt(3)
            for (i in 1..h) c.place(if (c.random.nextInt(3) == 0) b(Blocks.CRACKED_STONE_BRICKS) else if (c.random.nextInt(3) == 0) b(Blocks.MOSSY_STONE_BRICKS) else b(Blocks.STONE_BRICKS), i)
            if (c.random.nextBoolean()) c.place(b(Blocks.CHISELED_STONE_BRICKS), h + 1)
            c.around(2) { b(Blocks.MOSSY_STONE_BRICKS) }
            c.above
        },
        Kind("hoard", "a glittering hoard", Action.DIG, false, setOf("dragon", "steel", "dark", "ground")) { c ->
            c.place(b(Blocks.GOLD_BLOCK))
            c.around(3) { if (c.random.nextInt(3) == 0) b(Blocks.AMETHYST_CLUSTER) else b(Blocks.RAW_GOLD_BLOCK) }
            c.above
        },
        // not built: residents can pick a berry tree as their favourite spot too (and Grass and Water types tend them)
        Kind("berry_tree", "a berry tree", Action.SNIFF, false, setOf("grass", "bug", "normal", "fairy", "water")),
        // decorations players place
        Kind("player_bed", "a Pokémon bed", Action.NAP, true, emptySet(), bed = true, placed = true),
        Kind("player_toy", "a bouncy ball", Action.PLAY, false, emptySet(), gathering = true, placed = true),
        Kind("player_trough", "a feeding trough", Action.EAT, false, emptySet(), gathering = true, placed = true),
    ).associateBy { it.id }

    /** Which spots each biome gets. */
    private val BY_ZONE: Map<String, List<String>> = mapOf(
        ZoneLayout.HUB to listOf("picnic", "campfire", "stump", "flower_bed"),
        "grass" to listOf("mossy_log", "flower_bed", "moss_mound", "stump"),
        "bug" to listOf("rotten_log", "flower_bed", "stump", "mushroom_ring"),
        "fairy" to listOf("flower_ring", "crystal", "moss_mound"),
        "water" to listOf("rock_pool", "driftwood"),
        "fire" to listOf("campfire", "warm_rocks"),
        "ice" to listOf("snow_pile", "frozen_pond"),
        "electric" to listOf("lightning_rod", "copper_rocks", "scrap_pile"),
        "psychic" to listOf("meditation_stone", "crystal", "stone_circle"),
        "poison" to listOf("muddy_puddle", "mushroom_ring", "rotten_log"),
        "dark" to listOf("hollow_log", "mushroom_ring", "old_bones"),
        "ghost" to listOf("soul_lantern", "old_bones", "hollow_log"),
        "rock" to listOf("boulder", "stone_circle", "crystal"),
        "ground" to listOf("sand_mound", "old_bones", "boulder"),
        "steel" to listOf("scrap_pile", "iron_rock"),
        "fighting" to listOf("training_dummy", "lifting_rock", "meditation_stone"),
        "dragon" to listOf("ancient_pillar", "hoard"),
    )
    private val CUSTOM = listOf("boulder", "flower_bed")

    /** Blocks used by spots that aren't natural ground, so a rebuild clears them like other decorations. */
    val DECOR_BLOCKS: Set<Block> = setOf(
        Blocks.CAMPFIRE, Blocks.RED_CARPET, Blocks.WHITE_CARPET, Blocks.SMOOTH_STONE_SLAB, Blocks.WHITE_CANDLE,
        Blocks.DARK_OAK_FENCE, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG,
    )

    /** Natural-looking blocks that should count as ground (cut down like rock when the valley is rebuilt). */
    val GROUND_BLOCKS: Set<Block> = setOf(Blocks.RAW_IRON_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.GOLD_BLOCK, Blocks.WEATHERED_COPPER, Blocks.EXPOSED_COPPER)

    private const val GRID = 22
    private const val CAP = 5000

    /** Maybe puts a spot on this column (called once the column is dressed). */
    fun place(c: BiomeDresser.Ctx, zone: String, ring: List<String>, isRoute: (Int, Int) -> Boolean) {
        val s = c.s
        if (s.props.size >= CAP) return
        val kinds = BY_ZONE[zone] ?: CUSTOM
        if (!c.grid(GRID, 800 + Math.floorMod(zone.hashCode(), 89))) return
        if (!c.roomy(8) || (c.hit.zone != ZoneLayout.HUB && c.hit.edgeDistance < 7)) return
        if (c.y < SanctuaryDimension.GROUND_Y) return
        // the hub plaza and paths stay clear
        if (zone == ZoneLayout.HUB && c.hit.dist < 14) return
        if (!c.level.getBlockState(c.above).canBeReplaced()) return
        // flat ground all around, away from paths and landmarks
        for (dx in -3..3) for (dz in -3..3) {
            val x = c.x + dx
            val z = c.z + dz
            if (BiomeDresser.targetHeight(s, x, z, ring) != c.y) return
            if ((abs(dx) == 3 || abs(dz) == 3 || (dx == 0 && dz == 0)) && (isRoute(x, z) || Landmarks.near(s, x, z))) return
        }
        val soil = c.level.getBlockState(c.surface).`is`(BlockTags.DIRT)
        val options = kinds.mapNotNull { KINDS[it] }.filter { it.build != null && (soil || !it.soil) }
        if (options.isEmpty()) return
        val kind = options[c.random.nextInt(options.size)]
        val spot = runCatching { kind.build!!(c) }.getOrNull() ?: return
        if (c.level.getBlockState(spot).isAir) return
        s.props[spot.asLong()] = kind.id
    }

    /** Is the spot still there? */
    fun stillThere(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean = !level.getBlockState(pos).isAir
}
