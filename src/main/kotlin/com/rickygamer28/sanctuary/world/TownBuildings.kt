package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.life.Requests
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.StandingSignBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * The town's buildings, unlocked by the Valley Heart: a lookout tower, a berry market and a rest house with a
 * healing machine. They're kept like landmarks (the builder works around them, and they're taken down and put up
 * again when the town is rebuilt), stored as "building:<kind>".
 */
object TownBuildings {
    const val PREFIX = "building:"

    /** Each type's colour, for the Guardian's Rise. */
    private val TYPE_WOOL: Map<String, Block> = mapOf(
        "fire" to Blocks.RED_WOOL, "water" to Blocks.BLUE_WOOL, "grass" to Blocks.GREEN_WOOL, "electric" to Blocks.YELLOW_WOOL,
        "ice" to Blocks.LIGHT_BLUE_WOOL, "fighting" to Blocks.ORANGE_WOOL, "poison" to Blocks.PURPLE_WOOL, "ground" to Blocks.BROWN_WOOL,
        "flying" to Blocks.WHITE_WOOL, "psychic" to Blocks.PINK_WOOL, "bug" to Blocks.LIME_WOOL, "rock" to Blocks.GRAY_WOOL,
        "ghost" to Blocks.PURPLE_WOOL, "dragon" to Blocks.CYAN_WOOL, "dark" to Blocks.BLACK_WOOL, "steel" to Blocks.LIGHT_GRAY_WOOL,
        "fairy" to Blocks.PINK_WOOL, "normal" to Blocks.WHITE_WOOL,
    )
    private const val FLAGS = Block.UPDATE_CLIENTS

    private val NAMES = mapOf("basket" to "the Gathering Post", "shrine" to "the Heart Shrine", "tower" to "a lookout tower",
        "market" to "a berry market", "hatchery" to "a hatchery", "rest" to "a rest house", "rise" to "the Guardian's Rise")
    private val HALF = mapOf("basket" to 3, "shrine" to 4, "tower" to 4, "market" to 5, "hatchery" to 5, "rest" to 5, "rise" to 4)

    /** Every block the buildings are made of (so the valley's cleanup leaves them alone). */
    val BLOCKS: Set<Block> = setOf(
        Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB, Blocks.DARK_OAK_SLAB,
        Blocks.LADDER, Blocks.GLASS_PANE, Blocks.LANTERN, Blocks.POLISHED_ANDESITE, Blocks.OAK_FENCE, Blocks.RED_WOOL, Blocks.WHITE_WOOL,
        Blocks.BARREL, Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK, Blocks.OAK_SIGN, Blocks.OAK_PLANKS, Blocks.STRIPPED_OAK_LOG,
        Blocks.OAK_DOOR, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB, Blocks.RED_CARPET, Blocks.FLOWER_POT, Blocks.POTTED_RED_TULIP,
        Blocks.COBBLESTONE, Blocks.SPRUCE_TRAPDOOR, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_DOOR, Blocks.CHISELED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
        Blocks.CARTOGRAPHY_TABLE, Blocks.COMPOSTER, Blocks.SWEET_BERRY_BUSH, Blocks.DIRT, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS,
        Blocks.RED_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.YELLOW_CARPET, Blocks.BRICKS, Blocks.CAMPFIRE, Blocks.FURNACE, Blocks.BOOKSHELF,
        Blocks.POTTED_DANDELION, Blocks.POTTED_PINK_TULIP, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_QUARTZ, Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_PILLAR,
        Blocks.PINK_STAINED_GLASS, Blocks.PEARLESCENT_FROGLIGHT, Blocks.GOLD_BLOCK, Blocks.PINK_CANDLE, Blocks.SPRUCE_FENCE,
        Blocks.STONE_BRICK_STAIRS, Blocks.SMOOTH_STONE, Blocks.SEA_LANTERN,
    ) + TYPE_WOOL.values.toSet()

    fun isBuilding(key: String) = key.startsWith(PREFIX)

    /** Takes a building down so it goes up again (the Rise, when the guardian changes). */
    fun restyle(s: Sanctuary, kind: String) {
        s.landmarks.remove(PREFIX + kind)?.let { if (it != Landmarks.NONE) s.staleLandmarks[PREFIX + kind] = it }
    }

    /** When each missing building may look for room again. */
    private val nextTry = HashMap<Pair<java.util.UUID, String>, Long>()

    /** The town is being rebuilt: its buildings come down and go up again on the new ground. */
    fun rebuildAll(s: Sanctuary) {
        for (k in s.landmarks.keys.filter { isBuilding(it) }) {
            val v = s.landmarks.remove(k) ?: continue
            if (v != Landmarks.NONE) s.staleLandmarks[k] = v
        }
    }

    fun built(s: Sanctuary, kind: String) = s.landmarks.containsKey(PREFIX + kind)

    /** Called every two seconds once the valley is built: puts up at most one building that's due. */
    fun tick(level: ServerLevel, s: Sanctuary) {
        if (s.staleLandmarks.isNotEmpty()) return
        // one taken away (a repair, a cleanup): let it be built again
        for ((k, v) in s.landmarks.entries.toList()) {
            if (!isBuilding(k) || v == Landmarks.NONE) continue
            val p = BlockPos.of(v)
            if (level.hasChunk(p.x shr 4, p.z shr 4) && !floorBlock(k.removePrefix(PREFIX)).let { level.getBlockState(p).`is`(it) }) {
                s.landmarks.remove(k); s.staleLandmarks[k] = v
                return
            }
        }
        // the Heart Shrine grows: put it up again at its new size
        if (built(s, "shrine") && (s.stats["shrineTier"] ?: 0) != Requests.shrineTier(s)) {
            s.landmarks.remove(PREFIX + "shrine")?.let { s.staleLandmarks[PREFIX + "shrine"] = it }
            return
        }
        val lv = Requests.level(s)
        for ((kind, need) in Requests.BUILDINGS) {
            if (lv < need || built(s, kind) || s.staleLandmarks.containsKey(PREFIX + kind)) continue
            // looking for room takes a little work: at most every half minute per building
            val key = s.id to kind
            if (level.gameTime < (nextTry[key] ?: 0L)) continue
            nextTry[key] = level.gameTime + 600L
            val spot = find(level, s, kind) ?: continue
            nextTry.remove(key)
            runCatching { build(level, s, kind, spot.first, spot.second) }.onFailure {
                com.rickygamer28.sanctuary.CobblemonSanctuary.LOGGER.warn("Couldn't build the town's $kind", it)
            }
            s.landmarks[PREFIX + kind] = spot.first.asLong()
            if (need > 0) {
                s.logEvent("${NAMES[kind]?.replaceFirstChar { it.uppercase() }} went up in the town!")
                for (p in level.players()) if (s.isMember(p.uuid)) (p as? net.minecraft.server.level.ServerPlayer)?.displayClientMessage(
                    Component.literal("♥ ${NAMES[kind]?.replaceFirstChar { it.uppercase() }} went up in the town!").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE), false)
            }
            Sanctuaries.data(level).setDirty()
            return
        }
    }

    private fun floorBlock(kind: String): Block = when (kind) {
        "tower" -> Blocks.STONE_BRICKS; "market" -> Blocks.POLISHED_ANDESITE; "basket" -> Blocks.SPRUCE_PLANKS; "shrine" -> Blocks.CHISELED_QUARTZ_BLOCK
        "rise" -> Blocks.SMOOTH_STONE
        else -> Blocks.OAK_PLANKS
    }

    /** The ground under (x, z): its top natural block, under any plants, logs and leaves. */
    private fun groundY(level: ServerLevel, x: Int, z: Int): Int? {
        var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        repeat(16) {
            val st = level.getBlockState(BlockPos(x, y, z))
            if (BiomeDresser.isNatural(st)) return if (st.fluidState.isEmpty) y else null
            y--
        }
        return null
    }

    /** A flat, dry spot in the town, off its paths and away from everything else, with the door facing the middle. */
    private fun find(level: ServerLevel, s: Sanctuary, kind: String): Pair<BlockPos, Direction>? {
        // the Guardian's Rise goes in its guardian's own habitat (by the town while there's none, or no room)
        if (kind == "rise") {
            val g = com.rickygamer28.sanctuary.life.Guardian.of(s, com.rickygamer28.sanctuary.life.Residents.runtime(s))
            val home = g?.let { runCatching { TypeLogic.homeZone(it, s.residents[it.uuid], ZoneLayout.ring(s), s) }.getOrNull() }
            if (home != null && home != ZoneLayout.HUB && home in s.habitats) {
                val (ax, az) = ZoneLayout.anchor(s, home)
                val spots = ArrayList<Pair<Int, Int>>()
                for (r in listOf(18, 22, 26, 14, 30, 34, 38)) for (step in 0 until 24) {
                    val a = step * Math.PI * 2 / 24
                    spots += (ax + (cos(a) * r).toInt()) to (az + (sin(a) * r).toInt())
                }
                search(level, s, kind, home, spots, maxSlope = 6)?.let { return it }
            }
        }
        val hub = s.size.hubRadius
        val half = HALF[kind] ?: 4
        val salt = kind.hashCode()
        // in the town if there's room, else in the meadows just outside it
        val spots = ArrayList<Pair<Int, Int>>()
        for (frac in listOf(0.55, 0.65, 0.45, 0.75, 0.35, 0.85, 1.3, 1.5, 1.7, 1.9, 2.2, 2.5, 2.8, 3.2)) for (step in 0 until 28) {
            val a = (step + (salt and 7) * 0.37) * Math.PI * 2 / 28
            val r = if (frac > 1) hub + (frac - 1) * 40 else hub * frac
            if (r < half + 6 || (frac < 1 && r > hub - half - 1) || r > s.size.radius - half - 8) continue
            spots += (s.center.x + (cos(a) * r).toInt()) to (s.center.z + (sin(a) * r).toInt())
        }
        return search(level, s, kind, ZoneLayout.HUB, spots, maxSlope = 4)
    }

    /** The first of [spots] that's flat and dry enough, all in [zone], off its paths and away from everything else. */
    private fun search(level: ServerLevel, s: Sanctuary, kind: String, zone: String, spots: List<Pair<Int, Int>>, maxSlope: Int): Pair<BlockPos, Direction>? {
        val half = HALF[kind] ?: 4
        val ring = ZoneLayout.ring(s)
        for ((cx, cz) in spots) {
            // apart from the landmarks and the other buildings
            if (s.landmarks.values.any { it != Landmarks.NONE && BlockPos.of(it).let { p -> (p.x - cx) * (p.x - cx) + (p.z - cz) * (p.z - cz) < 15 * 15 } }) continue
            var ok = true
            val heights = ArrayList<Int>()
            loop@ for (dx in -half - 1..half + 1) for (dz in -half - 1..half + 1) {
                val x = cx + dx
                val z = cz + dz
                if (!level.hasChunk(x shr 4, z shr 4)) { ok = false; break@loop }
                val idx = ColumnOrder.indexOf(x - s.center.x, z - s.center.z)
                val inside = kotlin.math.abs(dx) <= half && kotlin.math.abs(dz) <= half
                if (idx < 0 || !s.dressed.get(idx) || ZoneLayout.locate(s, x, z, ring)?.zone != zone ||
                    (inside && BiomeDresser.pathAt(s, x, z, ring)) || Caves.at(s, x, z)?.mouth == true) { ok = false; break@loop }
                val y = groundY(level, x, z) ?: run { ok = false; null } ?: break@loop
                if (!level.getBlockState(BlockPos(x, y + 1, z)).fluidState.isEmpty) { ok = false; break@loop }
                heights += y
            }
            // (some slope is fine: the floor is levelled, cut into the high side and filled under the low side)
            if (!ok || heights.isEmpty() || heights.max() - heights.min() > maxSlope) continue
            val floor = heights.sorted()[heights.size / 2]
            val toMiddle = Direction.getNearest((s.center.x - cx).toDouble(), 0.0, (s.center.z - cz).toDouble())
            return BlockPos(cx, floor, cz) to toMiddle
        }
        return null
    }

    /** Places blocks in the building's own frame (its door on the north side), turned to face the town's middle. */
    private class B(val level: ServerLevel, val base: BlockPos, facing: Direction) {
        val rot: Rotation = when (facing) { Direction.EAST -> Rotation.CLOCKWISE_90; Direction.SOUTH -> Rotation.CLOCKWISE_180; Direction.WEST -> Rotation.COUNTERCLOCKWISE_90; else -> Rotation.NONE }
        fun pos(dx: Int, dy: Int, dz: Int): BlockPos {
            val (x, z) = when (rot) { Rotation.CLOCKWISE_90 -> -dz to dx; Rotation.CLOCKWISE_180 -> -dx to -dz; Rotation.COUNTERCLOCKWISE_90 -> dz to -dx; else -> dx to dz }
            return base.offset(x, dy, z)
        }
        fun set(dx: Int, dy: Int, dz: Int, st: BlockState) { level.setBlock(pos(dx, dy, dz), st.rotate(rot), FLAGS) }
        fun set(dx: Int, dy: Int, dz: Int, b: Block) = set(dx, dy, dz, b.defaultBlockState())
        fun fill(x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int, b: Block) { for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) set(x, y, z, b) }
    }

    private fun build(level: ServerLevel, s: Sanctuary, kind: String, base: BlockPos, facing: Direction) {
        val half = HALF[kind] ?: 4
        // clear the spot (trees as a whole) and lay a level floor, filled down so it never floats
        // (whole trees in a wider circle, so a big tree next to the spot isn't left with a branch hanging in the air)
        BiomeDresser.removeTreesIn(level, base, half + 10, base.y - 2, base.y + 30)
        for (dx in -half - 1..half + 1) for (dz in -half - 1..half + 1) {
            for (y in base.y + 1..base.y + 24) {
                val p = BlockPos(base.x + dx, y, base.z + dz)
                val st = level.getBlockState(p)
                if (!st.isAir && !ValleyBlocks.isMods(st)) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS)
            }
            val edge = kotlin.math.abs(dx) > half || kotlin.math.abs(dz) > half
            val top = BlockPos(base.x + dx, base.y, base.z + dz)
            if (edge) { if (!BiomeDresser.isNatural(level.getBlockState(top))) level.setBlock(top, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS) }
            for (dy in 1..4) {
                val p = top.below(dy)
                val st = level.getBlockState(p)
                if (st.isAir || !st.fluidState.isEmpty || !st.isSolid) level.setBlock(p, Blocks.DIRT.defaultBlockState(), FLAGS) else break
            }
        }
        val c = B(level, base, facing)
        when (kind) {
            "basket" -> basketPost(c, s)
            "shrine" -> shrine(c, s)
            "tower" -> tower(c)
            "market" -> market(c)
            "hatchery" -> hatchery(c)
            "rise" -> rise(c, s)
            else -> restHouse(c, s)
        }
    }

    private fun sign(c: B, dx: Int, dy: Int, dz: Int, lines: List<String>) {
        c.set(dx, dy, dz, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8))
        (c.level.getBlockEntity(c.pos(dx, dy, dz)) as? SignBlockEntity)?.let { be ->
            var text = SignText()
            lines.forEachIndexed { i, l -> text = text.setMessage(i, Component.literal(l)) }
            be.setText(text, true); be.setWaxed(true); be.setChanged()
        }
    }

    private fun stairs(b: Block, facing: Direction, top: Boolean = false): BlockState = b.defaultBlockState().setValue(StairBlock.FACING, facing)
        .setValue(StairBlock.HALF, if (top) net.minecraft.world.level.block.state.properties.Half.TOP else net.minecraft.world.level.block.state.properties.Half.BOTTOM)
    private fun hanging(): BlockState = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)
    private fun abs(i: Int) = kotlin.math.abs(i)

    /** A square hipped roof: a ring of stairs facing in at each height, filled with [fill], up to a point. */
    private fun hipRoof(c: B, y0: Int, r0: Int, stair: Block, fill: Block, cap: BlockState) {
        var y = y0
        var r = r0
        while (r >= 1) {
            for (x in -r..r) for (z in -r..r) {
                val st = when {
                    abs(x) == r && abs(x) >= abs(z) -> stairs(stair, if (x > 0) Direction.WEST else Direction.EAST)
                    abs(z) == r -> stairs(stair, if (z > 0) Direction.NORTH else Direction.SOUTH)
                    else -> fill.defaultBlockState()
                }
                c.set(x, y, z, st)
            }
            y++; r--
        }
        c.set(0, y, 0, cap)
    }

    /** A gabled roof running along x: stairs on the long sides stepping up to a ridge, the ends filled in. */
    private fun gableRoof(c: B, y0: Int, halfX: Int, halfZ: Int, stair: Block, slab: BlockState, end: Block) {
        for (i in 0..halfZ + 1) {
            val z = halfZ + 1 - i
            for (x in -halfX - 1..halfX + 1) {
                if (z > 0) {
                    c.set(x, y0 + i, -z, stairs(stair, Direction.SOUTH))
                    c.set(x, y0 + i, z, stairs(stair, Direction.NORTH))
                } else c.set(x, y0 + i, 0, slab)
            }
            // the gable ends
            for (x in listOf(-halfX, halfX)) for (zz in -(z - 1)..(z - 1)) if (z - 1 >= 0) c.set(x, y0 + i, zz, end)
        }
    }

    /** The Gathering Post: a little roofed stand with the valley's basket, there from the start. */
    private fun basketPost(c: B, s: Sanctuary) {
        c.fill(-2, 2, 0, 0, -2, 2, Blocks.SPRUCE_PLANKS)
        for (x in listOf(-2, 2)) for (z in listOf(-2, 2)) for (y in 1..3) c.set(x, y, z, Blocks.SPRUCE_LOG)
        hipRoof(c, 4, 3, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB.defaultBlockState())
        c.set(0, 3, 0, hanging())
        c.set(0, 1, 1, com.rickygamer28.sanctuary.registry.ModContent.GATHERING_BASKET.get().defaultBlockState())
        c.set(-1, 1, 1, Blocks.HAY_BLOCK); c.set(1, 1, 1, Blocks.BARREL)
        c.set(-1, 1, -1, Blocks.POTTED_RED_TULIP); c.set(1, 1, -1, Blocks.POTTED_DANDELION)
        sign(c, 2, 1, -3, listOf("", "Gathering Post", "Residents bring", "their finds here"))
        val pos = c.pos(0, 1, 1)
        if (s.basketPos == null || !c.level.getBlockState(s.basketPos!!).`is`(com.rickygamer28.sanctuary.registry.ModContent.GATHERING_BASKET.get())) s.basketPos = pos
    }

    private val HEARTS = listOf(
        listOf(".#.#.", "#####", ".###.", "..#.."),
        listOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."),
        listOf(".###.###.", "#########", "#########", "#########", ".#######.", "..#####..", "...###...", "....#...."),
    )

    /** The Heart Shrine: a stained-glass heart on a quartz stand that grows with the Valley Heart (5 stages). */
    private fun shrine(c: B, s: Sanctuary) {
        val tier = Requests.shrineTier(s)
        val golden = tier >= 5
        val r = if (tier == 1) 3 else 4
        for (x in -r..r) for (z in -r..r) if (x * x + z * z <= r * r + 1) {
            val edge = x * x + z * z > (r - 1) * (r - 1) + 1
            c.set(x, 0, z, if (golden && edge) Blocks.GOLD_BLOCK else if (edge) Blocks.QUARTZ_BRICKS else Blocks.SMOOTH_QUARTZ)
        }
        c.set(0, 0, 0, Blocks.CHISELED_QUARTZ_BLOCK)
        val stand = if (tier >= 3) 3 else 2
        for (y in 1..stand) c.set(0, y, 0, Blocks.QUARTZ_PILLAR)
        val pattern = HEARTS[if (tier == 1) 0 else if (tier <= 3) 1 else 2]
        val w = pattern[0].length
        val base = stand + 1
        fun filled(row: Int, col: Int) = row in pattern.indices && col in 0 until w && pattern[row][col] == '#'
        for ((row, line) in pattern.withIndex()) for ((col, ch) in line.withIndex()) {
            if (ch != '#') continue
            val y = base + (pattern.size - 1 - row)
            val border = !filled(row - 1, col) || !filled(row + 1, col) || !filled(row, col - 1) || !filled(row, col + 1)
            c.set(col - w / 2, y, 0, if (golden && border) Blocks.GOLD_BLOCK else Blocks.PINK_STAINED_GLASS)
        }
        // a glowing core
        c.set(0, base + pattern.size / 2, 0, Blocks.PEARLESCENT_FROGLIGHT)
        if (tier >= 2) for ((x, z) in listOf(-2 to -2, 2 to -2, -2 to 2, 2 to 2)) c.set(x, 1, z, Blocks.POTTED_PINK_TULIP)
        if (tier >= 3) for ((x, z) in listOf(-3 to 0, 3 to 0)) { c.set(x, 1, z, Blocks.SPRUCE_FENCE); c.set(x, 2, z, Blocks.LANTERN) }
        if (tier >= 4) for ((x, z) in listOf(0 to -2, 0 to 2, -1 to 0, 1 to 0))
            c.set(x, 1, z, Blocks.PINK_CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 3).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true))
        sign(c, 0, 1, -r - 1, listOf("", "The Heart Shrine", "Level ${Requests.level(s)}", ""))
        s.stats["shrineTier"] = tier
    }

    /** A stone-and-spruce lookout: a map room below, a ladder up the inside to a railed platform under a peaked roof. */
    private fun tower(c: B) {
        c.fill(-3, 3, 0, 0, -3, 3, Blocks.COBBLESTONE)
        c.fill(-2, 2, 0, 0, -2, 2, Blocks.STONE_BRICKS)
        for (y in 1..9) for (x in -2..2) for (z in -2..2) {
            val corner = abs(x) == 2 && abs(z) == 2
            val wall = abs(x) == 2 || abs(z) == 2
            if (!wall) continue
            val window = (x == 0 || z == 0) && (y == 2 || y == 6 || y == 9)
            c.set(x, y, z, when {
                window -> Blocks.GLASS_PANE
                y <= 3 -> if (corner) Blocks.CHISELED_STONE_BRICKS else if ((x + y + z) % 4 == 0) Blocks.MOSSY_STONE_BRICKS else Blocks.STONE_BRICKS
                corner -> Blocks.SPRUCE_LOG
                else -> Blocks.SPRUCE_PLANKS
            })
        }
        val door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
        c.set(0, 1, -2, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)); c.set(0, 2, -2, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        // the map room
        c.set(-1, 1, 1, Blocks.CARTOGRAPHY_TABLE); c.set(-1, 1, -1, Blocks.BARREL); c.set(1, 1, -1, Blocks.LANTERN)
        for (y in 1..10) c.set(1, y, 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH))
        // the platform and its railing
        for (x in -4..4) for (z in -4..4) if (!(x == 1 && z == 1)) c.set(x, 10, z, Blocks.SPRUCE_PLANKS)
        for (x in -4..4) for (z in -4..4) if (abs(x) == 4 || abs(z) == 4) c.set(x, 11, z, Blocks.SPRUCE_FENCE)
        for (x in listOf(-3, 3)) for (z in listOf(-3, 3)) for (y in 11..13) c.set(x, y, z, Blocks.SPRUCE_LOG)
        // brackets under the overhang
        for (i in -1..1) {
            c.set(i, 9, -3, stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, true)); c.set(i, 9, 3, stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, true))
            c.set(-3, 9, i, stairs(Blocks.SPRUCE_STAIRS, Direction.EAST, true)); c.set(3, 9, i, stairs(Blocks.SPRUCE_STAIRS, Direction.WEST, true))
        }
        hipRoof(c, 14, 4, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE.defaultBlockState())
        c.set(0, 13, 0, hanging())
        // a flag on top
        for (y in 19..21) c.set(0, y, 0, Blocks.SPRUCE_FENCE)
        c.set(1, 21, 0, Blocks.RED_WOOL); c.set(2, 21, 0, Blocks.RED_WOOL); c.set(1, 20, 0, Blocks.RED_WOOL)
    }

    /** A covered market hall: a striped tent roof on spruce posts, crates of produce, berry planters and a sign. */
    private fun market(c: B) {
        c.fill(-5, 5, 0, 0, -4, 4, Blocks.POLISHED_ANDESITE)
        for (x in -4..4) c.set(x, 0, 0, Blocks.SPRUCE_PLANKS)
        c.set(0, 0, 0, Blocks.POLISHED_ANDESITE)
        for (x in listOf(-5, 0, 5)) for (z in listOf(-4, 4)) for (y in 1..3) c.set(x, y, z, Blocks.SPRUCE_LOG)
        // the striped roof
        for (x in -6..6) for (z in -5..5) {
            val y = 4 + when (abs(z)) { 5, 4 -> 0; 3, 2 -> 1; 1 -> 2; else -> 3 }
            c.set(x, y, z, if (Math.floorMod(x, 2) == 0) Blocks.RED_WOOL else Blocks.WHITE_WOOL)
        }
        // stalls along both sides: barrels with produce on top
        val produce = listOf(Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK, Blocks.COMPOSTER, Blocks.MELON, Blocks.PUMPKIN)
        for ((i, x) in listOf(-4, -3, -2, 2, 3, 4).withIndex()) {
            c.set(x, 1, -2, Blocks.BARREL); c.set(x, 2, -2, produce[i])
            c.set(x, 1, 2, Blocks.BARREL); c.set(x, 2, 2, produce[(i + 3) % produce.size])
        }
        // berry planters at the back
        for (x in listOf(-4, -3, 3, 4)) {
            c.set(x, 0, 3, Blocks.DIRT)
            c.set(x, 1, 3, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3))
        }
        for (x in listOf(-3, 3)) c.set(x, 6, 0, hanging())
        c.set(-1, 1, -3, Blocks.POTTED_RED_TULIP); c.set(1, 1, -3, Blocks.POTTED_DANDELION)
        sign(c, 0, 1, -5, listOf("", "Berry Market", "Fresh every day!", ""))
    }

    /** A barn where eggs are kept warm: incubators along the back, straw, lanterns and a red gabled roof. */
    private fun hatchery(c: B) {
        for (x in -4..4) for (z in -3..3) c.set(x, 0, z, if (abs(x) == 4 || abs(z) == 3) Blocks.COBBLESTONE else Blocks.OAK_PLANKS)
        for (y in 1..3) for (x in -4..4) for (z in -3..3) {
            val post = abs(x) == 4 && abs(z) == 3 || x == 0 && abs(z) == 3 || abs(x) == 4 && z == 0
            val wall = abs(x) == 4 || abs(z) == 3
            if (post) c.set(x, y, z, Blocks.DARK_OAK_LOG)
            else if (wall) c.set(x, y, z, if (y == 2 && (abs(x) == 2 && abs(z) == 3 || abs(z) == 1 && abs(x) == 4)) Blocks.GLASS_PANE else Blocks.RED_TERRACOTTA)
        }
        for (x in -4..4) c.set(x, 3, -3, Blocks.DARK_OAK_PLANKS)
        for (x in -4..4) c.set(x, 3, 3, Blocks.DARK_OAK_PLANKS)
        val door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
        c.set(-1, 1, -3, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)); c.set(-1, 2, -3, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        gableRoof(c, 4, 4, 3, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB.defaultBlockState(), Blocks.RED_TERRACOTTA)
        // inside: incubators along the back, straw and lanterns
        val incubator = com.rickygamer28.sanctuary.registry.ModContent.INCUBATOR.get()
        for (x in listOf(-3, -1, 1, 3)) c.set(x, 1, 2, incubator.defaultBlockState())
        for ((x, z) in listOf(-3 to -2, 3 to -2, 3 to 0)) c.set(x, 1, z, Blocks.HAY_BLOCK)
        c.set(-3, 2, -2, Blocks.HAY_BLOCK)
        c.set(-3, 3, -2, Blocks.LANTERN); c.set(3, 2, 0, Blocks.LANTERN)
        for (x in -1..1) c.set(x, 1, 0, Blocks.YELLOW_CARPET)
        c.set(0, 0, 0, Blocks.OAK_PLANKS)
        sign(c, 1, 1, -4, listOf("", "Hatchery", "Eggs hatch here", "on their own"))
    }

    /** A cottage with a red roof and a chimney: a healing machine, beds residents nap in, a reading corner and a rug. */
    private fun restHouse(c: B, s: Sanctuary) {
        for (x in -5..5) for (z in -4..4) c.set(x, 0, z, if (abs(x) == 5 || abs(z) == 4) Blocks.COBBLESTONE else Blocks.OAK_PLANKS)
        for (y in 1..3) for (x in -5..5) for (z in -4..4) {
            val post = abs(x) == 5 && abs(z) == 4 || x == 0 && abs(z) == 4 || abs(x) == 5 && z == 0
            val wall = abs(x) == 5 || abs(z) == 4
            if (post) c.set(x, y, z, Blocks.STRIPPED_OAK_LOG)
            else if (wall) c.set(x, y, z, when {
                y == 2 && (abs(x) in 2..3 && abs(z) == 4 || abs(z) == 2 && abs(x) == 5) -> Blocks.GLASS_PANE
                y == 1 -> Blocks.OAK_PLANKS
                else -> Blocks.WHITE_TERRACOTTA
            })
        }
        val door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
        c.set(-2, 1, -4, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)); c.set(-2, 2, -4, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        gableRoof(c, 4, 5, 4, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB.defaultBlockState(), Blocks.WHITE_TERRACOTTA)
        // a chimney with smoke
        for (y in 1..10) c.set(4, y, 3, Blocks.BRICKS)
        c.set(4, 11, 3, Blocks.CAMPFIRE)
        c.set(4, 1, 2, Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, true))
        // inside
        BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("cobblemon", "healing_machine")).takeIf { it != Blocks.AIR }?.let { c.set(0, 1, 3, it.defaultBlockState()) }
        val bed = com.rickygamer28.sanctuary.registry.ModContent.POKEMON_BED.get()
        for ((x, z) in listOf(-4 to 3, -4 to 1, -2 to 3)) {
            c.set(x, 1, z, bed.defaultBlockState())
            s.props[c.pos(x, 1, z).asLong()] = "player_bed"
        }
        for (y in 1..2) { c.set(4, y, -3, Blocks.BOOKSHELF); c.set(3, y, -3, Blocks.BOOKSHELF) }
        c.set(3, 1, -1, Blocks.OAK_FENCE); c.set(3, 2, -1, Blocks.LANTERN)
        for (x in -1..1) for (z in -1..1) c.set(x, 1, z, Blocks.RED_CARPET)
        c.set(-4, 1, -3, Blocks.POTTED_RED_TULIP)
        c.set(4, 3, -3, Blocks.LANTERN); c.set(-4, 1, -1, Blocks.LANTERN)
        sign(c, 1, 1, -5, listOf("", "Rest House", "Heal up inside", ""))
    }

    /** The Guardian's Rise: a raised stone platform with steps, four pillars hung with the guardian's colours, and a pedestal it stands on. */
    private fun rise(c: B, s: Sanctuary) {
        val types = com.rickygamer28.sanctuary.life.Guardian.types(s)
        val colours = types.mapNotNull { TYPE_WOOL[it] }.ifEmpty { listOf(Blocks.WHITE_WOOL) }
        c.fill(-4, 4, 0, 0, -4, 4, Blocks.STONE_BRICKS)
        for (x in -3..3) for (z in -3..3) c.set(x, 1, z, if (abs(x) == 3 || abs(z) == 3) Blocks.CHISELED_STONE_BRICKS else Blocks.STONE_BRICKS)
        c.set(0, 0, 0, Blocks.SMOOTH_STONE)
        c.set(0, 1, 0, Blocks.SEA_LANTERN)
        for (x in -1..1) c.set(x, 1, -4, stairs(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH))
        for ((i, p) in listOf(-3 to -3, 3 to -3, -3 to 3, 3 to 3).withIndex()) {
            val (x, z) = p
            for (y in 2..5) c.set(x, y, z, Blocks.QUARTZ_PILLAR)
            val wool = colours[i % colours.size]
            c.set(x, 6, z, wool)
            c.set(x, 7, z, Blocks.LANTERN)
            // a strip of colour down the outer side
            val ox = if (x > 0) 1 else -1
            for (y in 3..5) c.set(x + ox, y, z, wool)
        }
        sign(c, 2, 1, -5, listOf("", "Guardian's Rise", if (types.isEmpty()) "awaiting a guardian" else "", ""))
    }
}
