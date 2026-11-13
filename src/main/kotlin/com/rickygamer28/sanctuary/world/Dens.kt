package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs

/**
 * Dens: small mounds with a tunnel in each biome (a burrow, an igloo, a cave mouth, a crypt…). Residents going to
 * rest walk into one and are gone; residents coming out walk out of one. They're added on finished ground (by the
 * valley's check pass), so existing valleys get them without a rebuild.
 */
object Dens {
    private class Style(val main: Block, val accent: Block, val cap: Block, val floor: Block? = null, val deco: Block? = null)

    private val STYLES: Map<String, Style> = mapOf(
        ZoneLayout.HUB to Style(Blocks.ROOTED_DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, deco = Blocks.SHORT_GRASS),
        "grass" to Style(Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, Blocks.MOSS_BLOCK, deco = Blocks.FLOWERING_AZALEA),
        "bug" to Style(Blocks.ROOTED_DIRT, Blocks.MUD, Blocks.MOSS_BLOCK, deco = Blocks.BROWN_MUSHROOM),
        "fairy" to Style(Blocks.MOSS_BLOCK, Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, deco = Blocks.PINK_PETALS),
        "water" to Style(Blocks.MOSSY_COBBLESTONE, Blocks.CLAY, Blocks.MOSSY_COBBLESTONE, floor = Blocks.GRAVEL),
        "fire" to Style(Blocks.BLACKSTONE, Blocks.BASALT, Blocks.BLACKSTONE, floor = Blocks.MAGMA_BLOCK),
        "ice" to Style(Blocks.PACKED_ICE, Blocks.SNOW_BLOCK, Blocks.SNOW_BLOCK, deco = Blocks.SNOW),
        "electric" to Style(Blocks.TUFF, Blocks.WEATHERED_COPPER, Blocks.TUFF),
        "psychic" to Style(Blocks.CALCITE, Blocks.AMETHYST_BLOCK, Blocks.CALCITE, deco = Blocks.AMETHYST_CLUSTER),
        "poison" to Style(Blocks.MUD, Blocks.PACKED_MUD, Blocks.PACKED_MUD, deco = Blocks.BROWN_MUSHROOM),
        "dark" to Style(Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE),
        "ghost" to Style(Blocks.COBBLED_DEEPSLATE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE, deco = Blocks.SOUL_LANTERN),
        "rock" to Style(Blocks.STONE, Blocks.ANDESITE, Blocks.STONE),
        "ground" to Style(Blocks.SANDSTONE, Blocks.TERRACOTTA, Blocks.SANDSTONE, deco = Blocks.DEAD_BUSH),
        "steel" to Style(Blocks.POLISHED_ANDESITE, Blocks.RAW_IRON_BLOCK, Blocks.STONE),
        "fighting" to Style(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS),
        "dragon" to Style(Blocks.DEEPSLATE, Blocks.OBSIDIAN, Blocks.DEEPSLATE),
    )
    private val CUSTOM = Style(Blocks.STONE, Blocks.COBBLESTONE, Blocks.STONE)

    private const val GRID = 32
    private const val CAP = 600
    private const val FLAGS = Block.UPDATE_CLIENTS

    class Den(val center: BlockPos, val zone: String, val dir: Direction) {
        /** The open end of the tunnel, where residents walk in and out. */
        val mouth: BlockPos get() = center.relative(dir, 2).above()
        /** Inside, at the end of the tunnel. */
        val inside: BlockPos get() = center.relative(dir, 1).above()
        /** Just in front of it, outside. */
        val front: BlockPos get() = center.relative(dir, 4).above()
    }

    fun all(s: Sanctuary): Sequence<Den> = s.dens.entries.asSequence().mapNotNull { (k, v) -> decode(k, v) }

    private fun decode(k: Long, v: String): Den? {
        val zone = v.substringBefore(':')
        val dir = Direction.from2DDataValue(v.substringAfter(':').toIntOrNull() ?: return null)
        return Den(BlockPos.of(k), zone, dir)
    }

    /** Is the den still standing (and its tunnel still open)? */
    fun intact(level: ServerLevel, d: Den): Boolean =
        level.getBlockState(d.mouth).isAir && !level.getBlockState(d.center.above(3)).isAir

    /** Dens of [zone] (or any den, if [zone] is null) in loaded chunks. */
    fun near(level: ServerLevel, s: Sanctuary, zone: String?): List<Den> =
        all(s).filter { (zone == null || it.zone == zone) && level.isLoaded(it.mouth) }.toList()

    private fun b(block: Block): BlockState = block.defaultBlockState()

    /**
     * Called for every finished column by the valley's check pass: builds a den on one spot per grid cell, if the
     * ground there is flat and free.
     */
    fun maybeBuild(level: ServerLevel, s: Sanctuary, x: Int, z: Int, ring: List<String>) {
        val salt = 900
        val cx = Math.floorDiv(x, GRID)
        val cz = Math.floorDiv(z, GRID)
        if (Math.floorMod(x, GRID) != (Noise.hash(s.seed + salt, cx, cz) * GRID).toInt() ||
            Math.floorMod(z, GRID) != (Noise.hash(s.seed + salt + 1, cx, cz) * GRID).toInt()) return
        if (s.dens.size >= CAP || !SanctuaryConfig.dens.get()) return
        val hit = ZoneLayout.locate(s, x, z, ring) ?: return
        val zone = hit.zone
        if (zone != ZoneLayout.HUB && zone !in s.unlocked) return
        val center = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z)
        if (s.dens.containsKey(center.asLong())) return
        if (hit.dist > s.size.radius - 12 || (zone != ZoneLayout.HUB && hit.edgeDistance < 8)) return
        if (zone == ZoneLayout.HUB && hit.dist < 16) return
        if (center.y < SanctuaryDimension.GROUND_Y) return
        // flat, dry and free all around (room for the mound and the way in)
        for (dx in -4..4) for (dz in -4..4) {
            val px = x + dx
            val pz = z + dz
            if (!level.hasChunk(px shr 4, pz shr 4)) return
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1
            if (abs(top - center.y) > 2) return
            val ground = BlockPos(px, top, pz)
            if (!level.getFluidState(ground).isEmpty || !BiomeDresser.isNatural(level.getBlockState(ground))) return
            for (dy in 1..3) {
                val st = level.getBlockState(ground.above(dy))
                if (!st.isAir && !(st.canBeReplaced() && st.fluidState.isEmpty)) return
            }
            if (abs(dx) == 4 || abs(dz) == 4 || (dx == 0 && dz == 0)) {
                if (BiomeDresser.pathAt(s, px, pz, ring) || Landmarks.near(s, px, pz)) return
            }
        }
        if (s.props.keys.any { BlockPos.of(it).let { p -> abs(p.x - x) <= 5 && abs(p.z - z) <= 5 } }) return
        if (s.berrySpots.any { BlockPos.of(it).let { p -> abs(p.x - x) <= 4 && abs(p.z - z) <= 4 } }) return
        val random = net.minecraft.util.RandomSource.create(s.seed xor center.asLong())
        // the way in must be at the den's own level, not up a step or down a drop
        fun groundAt(p: BlockPos) = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x, p.z) - 1
        val dir = Direction.Plane.HORIZONTAL.toList().shuffled(java.util.Random(random.nextLong())).firstOrNull { d ->
            (2..4).all { i -> abs(groundAt(center.relative(d, i)) - center.y) <= 1 }
        } ?: return
        build(level, center, STYLES[zone] ?: CUSTOM, dir, random)
        s.dens[center.asLong()] = "$zone:${dir.get2DDataValue()}"
    }

    private fun build(level: ServerLevel, center: BlockPos, st: Style, dir: Direction, random: net.minecraft.util.RandomSource) {
        fun put(p: BlockPos, block: Block) {
            val cur = level.getBlockState(p)
            if (cur.isAir || cur.canBeReplaced()) level.setBlock(p, b(block), FLAGS)
        }
        fun pick() = if (random.nextInt(4) == 0) st.accent else st.main
        val base = center.above()
        // a rounded mound: 5×5 without corners, then 3×3, then a cap
        for (dx in -2..2) for (dz in -2..2) {
            if (abs(dx) == 2 && abs(dz) == 2) continue
            val p = base.offset(dx, 0, dz)
            put(p, pick())
            // where the ground dips, fill under it so the mound doesn't float
            for (dy in 1..2) { val q = p.below(dy); val cur = level.getBlockState(q); if (cur.isAir || cur.canBeReplaced()) put(q, st.main) else break }
        }
        for (dx in -1..1) for (dz in -1..1) put(base.offset(dx, 1, dz), if (abs(dx) + abs(dz) == 2) st.cap else pick())
        put(base.above(2), st.cap)
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) if (random.nextBoolean()) put(base.offset(dx, 2, dz), st.cap)
        // the hollow and the tunnel to it (two high)
        for (i in 0..2) for (dy in 0..1) level.setBlock(base.relative(dir, i).above(dy), b(Blocks.AIR), FLAGS)
        st.floor?.let { level.setBlock(center, b(it), FLAGS) }
        // a little decoration on top
        st.deco?.let { deco ->
            val top = base.above(3)
            if (level.getBlockState(top).isAir && !level.getBlockState(top.below()).isAir) {
                level.setBlock(top, b(deco), FLAGS)
            }
        }
    }
}
