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
    private const val FLAGS = Block.UPDATE_CLIENTS

    private val NAMES = mapOf("tower" to "a lookout tower", "market" to "a berry market", "rest" to "a rest house")
    private val HALF = mapOf("tower" to 3, "market" to 4, "rest" to 5)

    /** Every block the buildings are made of (so the valley's cleanup leaves them alone). */
    val BLOCKS: Set<Block> = setOf(
        Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB, Blocks.DARK_OAK_SLAB,
        Blocks.LADDER, Blocks.GLASS_PANE, Blocks.LANTERN, Blocks.POLISHED_ANDESITE, Blocks.OAK_FENCE, Blocks.RED_WOOL, Blocks.WHITE_WOOL,
        Blocks.BARREL, Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK, Blocks.OAK_SIGN, Blocks.OAK_PLANKS, Blocks.STRIPPED_OAK_LOG,
        Blocks.OAK_DOOR, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB, Blocks.RED_CARPET, Blocks.FLOWER_POT, Blocks.POTTED_RED_TULIP,
        Blocks.COBBLESTONE, Blocks.SPRUCE_TRAPDOOR,
    )

    fun isBuilding(key: String) = key.startsWith(PREFIX)

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
        val lv = Requests.level(s)
        for ((kind, need) in Requests.BUILDINGS) {
            if (lv < need || built(s, kind) || s.staleLandmarks.containsKey(PREFIX + kind)) continue
            val spot = find(level, s, kind) ?: continue
            runCatching { build(level, s, kind, spot.first, spot.second) }.onFailure {
                com.rickygamer28.sanctuary.CobblemonSanctuary.LOGGER.warn("Couldn't build the town's $kind", it)
            }
            s.landmarks[PREFIX + kind] = spot.first.asLong()
            s.logEvent("${NAMES[kind]?.replaceFirstChar { it.uppercase() }} went up in the town!")
            for (p in level.players()) if (s.isMember(p.uuid)) (p as? net.minecraft.server.level.ServerPlayer)?.displayClientMessage(
                Component.literal("♥ ${NAMES[kind]?.replaceFirstChar { it.uppercase() }} went up in the town!").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE), false)
            Sanctuaries.data(level).setDirty()
            return
        }
    }

    private fun floorBlock(kind: String): Block = when (kind) { "tower" -> Blocks.STONE_BRICKS; "market" -> Blocks.POLISHED_ANDESITE; else -> Blocks.OAK_PLANKS }

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
        val half = HALF[kind] ?: 4
        val hub = s.size.hubRadius
        val ring = ZoneLayout.ring(s)
        val salt = kind.hashCode()
        // in the town if there's room, else in the meadows just outside it
        for (frac in listOf(0.55, 0.65, 0.45, 0.75, 0.35, 0.85, 1.3, 1.5, 1.7)) for (step in 0 until 28) {
            val a = (step + (salt and 7) * 0.37) * Math.PI * 2 / 28
            val r = if (frac > 1) hub + (frac - 1) * 40 else hub * frac
            if (r < half + 6 || (frac < 1 && r > hub - half - 1) || r > s.size.radius - half - 8) continue
            val cx = s.center.x + (cos(a) * r).toInt()
            val cz = s.center.z + (sin(a) * r).toInt()
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
                if (idx < 0 || !s.dressed.get(idx) || ZoneLayout.locate(s, x, z, ring)?.zone != ZoneLayout.HUB ||
                    (inside && BiomeDresser.pathAt(s, x, z, ring)) || Caves.at(s, x, z)?.mouth == true) { ok = false; break@loop }
                val y = groundY(level, x, z) ?: run { ok = false; null } ?: break@loop
                if (!level.getBlockState(BlockPos(x, y + 1, z)).fluidState.isEmpty) { ok = false; break@loop }
                heights += y
            }
            if (!ok || heights.isEmpty() || heights.max() - heights.min() > 2) continue
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
        BiomeDresser.removeTreesIn(level, base, half + 6, base.y - 2, base.y + 30)
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
            "tower" -> tower(c)
            "market" -> market(c)
            else -> restHouse(c, s)
        }
    }

    private fun sign(c: B, dx: Int, dz: Int, lines: List<String>) {
        c.set(dx, 1, dz, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8))
        (c.level.getBlockEntity(c.pos(dx, 1, dz)) as? SignBlockEntity)?.let { be ->
            var text = SignText()
            lines.forEachIndexed { i, l -> text = text.setMessage(i, Component.literal(l)) }
            be.setText(text, true); be.setWaxed(true); be.setChanged()
        }
    }

    /** A tall spruce lookout: a ladder up the inside to a railed platform under a little roof. */
    private fun tower(c: B) {
        c.fill(-2, 2, 0, 0, -2, 2, Blocks.STONE_BRICKS)
        for (y in 1..9) for (x in -2..2) for (z in -2..2) {
            val corner = kotlin.math.abs(x) == 2 && kotlin.math.abs(z) == 2
            val wall = kotlin.math.abs(x) == 2 || kotlin.math.abs(z) == 2
            when {
                corner -> c.set(x, y, z, Blocks.SPRUCE_LOG)
                wall -> c.set(x, y, z, if ((x == 0 || z == 0) && y % 3 == 2) Blocks.GLASS_PANE else Blocks.SPRUCE_PLANKS)
            }
        }
        // the door (an opening) facing the town
        c.set(0, 1, -2, Blocks.AIR); c.set(0, 2, -2, Blocks.AIR)
        for (y in 1..10) c.set(1, y, 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH))
        for (x in -3..3) for (z in -3..3) if (!(x == 1 && z == 1)) c.set(x, 10, z, Blocks.SPRUCE_PLANKS)
        c.set(1, 10, 2, Blocks.SPRUCE_PLANKS)
        for (x in -3..3) for (z in -3..3) if (kotlin.math.abs(x) == 3 || kotlin.math.abs(z) == 3) c.set(x, 11, z, Blocks.SPRUCE_FENCE)
        for (x in listOf(-3, 3)) for (z in listOf(-3, 3)) { c.set(x, 12, z, Blocks.SPRUCE_FENCE); c.set(x, 13, z, Blocks.SPRUCE_FENCE) }
        for ((i, r) in listOf(3, 2, 1, 0).withIndex()) for (x in -r..r) for (z in -r..r) c.set(x, 14 + i, z, Blocks.DARK_OAK_SLAB)
        c.set(0, 13, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        c.set(0, 9, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        c.set(0, 10, 0, Blocks.SPRUCE_PLANKS)
    }

    /** Two striped stalls with crates of produce, and a sign. */
    private fun market(c: B) {
        c.fill(-4, 4, 0, 0, -3, 3, Blocks.POLISHED_ANDESITE)
        val produce = listOf(Blocks.MELON, Blocks.BARREL, Blocks.PUMPKIN, Blocks.HAY_BLOCK)
        for (side in listOf(-1, 1)) {
            val x0 = if (side < 0) -4 else 1
            val x1 = if (side < 0) -1 else 4
            for (x in listOf(x0, x1)) for (z in listOf(-2, 2)) for (y in 1..3) c.set(x, y, z, Blocks.OAK_FENCE)
            for (x in x0..x1) for (z in -3..2) c.set(x, 4, z, if (Math.floorMod(x, 2) == 0) Blocks.RED_WOOL else Blocks.WHITE_WOOL)
            for ((i, x) in (x0..x1).withIndex()) {
                c.set(x, 1, -1, produce[(i + side + 2) % produce.size])
                c.set(x, 1, 1, produce[(i + side + 4) % produce.size])
            }
            c.set((x0 + x1) / 2, 3, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        }
        c.set(0, 1, 2, Blocks.POTTED_RED_TULIP)
        sign(c, 0, -3, listOf("", "Berry Market", "Fresh every day!", ""))
    }

    /** A little cottage with a red roof, beds for residents and a healing machine. */
    private fun restHouse(c: B, s: Sanctuary) {
        // floor, walls, door and windows
        for (x in -4..4) for (z in -3..3) c.set(x, 0, z, if (kotlin.math.abs(x) == 4 || kotlin.math.abs(z) == 3) Blocks.COBBLESTONE else Blocks.OAK_PLANKS)
        for (y in 1..3) for (x in -4..4) for (z in -3..3) {
            val corner = kotlin.math.abs(x) == 4 && kotlin.math.abs(z) == 3
            val wall = kotlin.math.abs(x) == 4 || kotlin.math.abs(z) == 3
            if (corner) c.set(x, y, z, Blocks.STRIPPED_OAK_LOG)
            else if (wall) c.set(x, y, z, if (y == 2 && (Math.floorMod(x, 3) == 2 && kotlin.math.abs(z) == 3 || z == 0 && kotlin.math.abs(x) == 4)) Blocks.GLASS_PANE else Blocks.OAK_PLANKS)
        }
        val door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
        c.set(0, 1, -3, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))
        c.set(0, 2, -3, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        // a red gabled roof
        for ((i, z) in listOf(-4, -3, -2, -1).withIndex()) for (x in -5..5) {
            c.set(x, 4 + i, z, Blocks.BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH))
            c.set(x, 4 + i, -z, Blocks.BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH))
        }
        for (x in -5..5) c.set(x, 8, 0, Blocks.BRICK_SLAB)
        for (x in listOf(-4, 4)) {
            for (z in -3..3) c.set(x, 4, z, Blocks.OAK_PLANKS)
            for (z in -2..2) c.set(x, 5, z, Blocks.OAK_PLANKS)
            for (z in -1..1) c.set(x, 6, z, Blocks.OAK_PLANKS)
            c.set(x, 7, 0, Blocks.OAK_PLANKS)
        }
        // inside: a rug, lanterns, the healing machine and two beds residents can nap in
        for (x in -1..1) for (z in -1..1) c.set(x, 1, z, Blocks.RED_CARPET)
        c.set(-3, 1, -2, Blocks.LANTERN); c.set(3, 1, -2, Blocks.LANTERN)
        BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("cobblemon", "healing_machine")).takeIf { it != Blocks.AIR }?.let { healer ->
            c.set(0, 1, 2, healer.defaultBlockState())
        }
        val bed = com.rickygamer28.sanctuary.registry.ModContent.POKEMON_BED.get()
        for (x in listOf(-3, 3)) {
            c.set(x, 1, 2, bed.defaultBlockState())
            s.props[c.pos(x, 1, 2).asLong()] = "player_bed"
        }
        sign(c, 2, -4, listOf("", "Rest House", "Heal up inside", ""))
    }
}
