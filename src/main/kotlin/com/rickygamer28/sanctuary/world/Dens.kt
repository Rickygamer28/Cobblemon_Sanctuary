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

    private const val GRID = 36
    private const val CAP = 600
    private const val FLAGS = Block.UPDATE_CLIENTS
    /** Radius of a den's mound. */
    private const val R = 4

    /**
     * A den: [big] ones (1.2.1+) are a wide dome with a 3×3 tunnel into a 3×3×3 hollow, so several residents can
     * come and go at once; the first small ones are upgraded where there's room.
     */
    class Den(val center: BlockPos, val zone: String, val dir: Direction, val big: Boolean) {
        /** The open end of the tunnel, where residents walk in and out. */
        val mouth: BlockPos get() = center.relative(dir, if (big) R else 2).above()
        /** Inside, in the hollow. */
        val inside: BlockPos get() = if (big) center.above() else center.relative(dir, 1).above()
        /** In front of it, outside. */
        val front: BlockPos get() = center.relative(dir, if (big) R + 3 else 4).above()
        /** The roof above the hollow. */
        val roof: BlockPos get() = center.above(if (big) 4 else 3)
    }

    fun all(s: Sanctuary): Sequence<Den> = s.dens.entries.asSequence().mapNotNull { (k, v) -> decode(k, v) }

    private fun decode(k: Long, v: String): Den? {
        val parts = v.split(':')
        if (parts.size < 2) return null
        val dir = Direction.from2DDataValue(parts[1].toIntOrNull() ?: return null)
        return Den(BlockPos.of(k), parts[0], dir, parts.getOrNull(2) == "big")
    }

    private fun encode(zone: String, dir: Direction) = "$zone:${dir.get2DDataValue()}:big"

    /** Is the den still standing (and its tunnel still open)? */
    fun intact(level: ServerLevel, d: Den): Boolean =
        level.getBlockState(d.mouth).isAir && !level.getBlockState(d.roof).isAir

    /** Dens of [zone] (or any den, if [zone] is null) in loaded chunks. */
    fun near(level: ServerLevel, s: Sanctuary, zone: String?): List<Den> =
        all(s).filter { (zone == null || it.zone == zone) && level.isLoaded(it.mouth) }.toList()

    private fun b(block: Block): BlockState = block.defaultBlockState()

    private fun groundAt(level: ServerLevel, x: Int, z: Int) = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1

    /** Room for a big den at [center]: gently sloped, dry, free of paths, landmarks, spots and berry trees. */
    private fun roomFor(level: ServerLevel, s: Sanctuary, center: BlockPos, ring: List<String>, ignore: Set<Long> = emptySet()): Boolean {
        val x = center.x
        val z = center.z
        val span = R + 1
        for (dx in -span..span) for (dz in -span..span) {
            val px = x + dx
            val pz = z + dz
            if (!level.hasChunk(px shr 4, pz shr 4)) return false
            val top = groundAt(level, px, pz)
            val ground = BlockPos(px, top, pz)
            if (ground.asLong() in ignore || ignore.contains(BlockPos(px, 0, pz).asLong())) continue
            if (abs(top - center.y) > 2) return false
            if (!level.getFluidState(ground).isEmpty) return false
            if (abs(dx) == span || abs(dz) == span || (dx == 0 && dz == 0)) {
                if (BiomeDresser.pathAt(s, px, pz, ring) || Landmarks.near(s, px, pz)) return false
            }
        }
        if (s.props.keys.any { BlockPos.of(it).let { p -> abs(p.x - x) <= R + 3 && abs(p.z - z) <= R + 3 } }) return false
        if (s.berrySpots.any { BlockPos.of(it).let { p -> abs(p.x - x) <= R + 2 && abs(p.z - z) <= R + 2 } }) return false
        return true
    }

    /** A direction whose way in is level with the den (not up a step or down a drop). */
    private fun entrance(level: ServerLevel, center: BlockPos, random: net.minecraft.util.RandomSource): Direction? =
        Direction.Plane.HORIZONTAL.toList().shuffled(java.util.Random(random.nextLong())).firstOrNull { d ->
            (R..R + 3).all { i -> val p = center.relative(d, i); abs(groundAt(level, p.x, p.z) - center.y) <= 1 }
        }

    /**
     * Called for every finished column by the valley's check pass: builds a den on one spot per grid cell, if the
     * ground there is gentle and free.
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
        val center = BlockPos(x, groundAt(level, x, z), z)
        if (s.dens.containsKey(center.asLong())) return
        if (s.dens.keys.any { BlockPos.of(it).let { p -> abs(p.x - x) < 16 && abs(p.z - z) < 16 } }) return
        if (hit.dist > s.size.radius - 14 || (zone != ZoneLayout.HUB && hit.edgeDistance < 10)) return
        if (zone == ZoneLayout.HUB && hit.dist < 18) return
        if (center.y < SanctuaryDimension.GROUND_Y) return
        if (!BiomeDresser.isNatural(level.getBlockState(center))) return
        if (!roomFor(level, s, center, ring)) return
        val random = net.minecraft.util.RandomSource.create(s.seed xor center.asLong())
        val dir = entrance(level, center, random) ?: return
        build(level, center, STYLES[zone] ?: CUSTOM, dir, random)
        s.dens[center.asLong()] = encode(zone, dir)
    }

    /** Turns the first, small dens into big ones where there's room (a couple at a time). */
    fun upgrade(level: ServerLevel, s: Sanctuary) {
        if (!SanctuaryConfig.dens.get()) return
        val ring = ZoneLayout.ring()
        var done = 0
        for (d in all(s).filter { !it.big }.toList()) {
            if (done >= 2) return
            if (!level.isLoaded(d.center) || !level.hasChunk((d.center.x + R + 1) shr 4, (d.center.z + R + 1) shr 4) ||
                !level.hasChunk((d.center.x - R - 1) shr 4, (d.center.z - R - 1) shr 4)) continue
            done++
            val random = net.minecraft.util.RandomSource.create(s.seed xor d.center.asLong())
            // the old mound stands on the ground: measure around it, not on top of it
            val old = HashSet<Long>()
            for (dx in -2..2) for (dz in -2..2) old += BlockPos(d.center.x + dx, 0, d.center.z + dz).asLong()
            if (!roomFor(level, s, d.center, ring, old)) { s.dens[d.center.asLong()] = encode(d.zone, d.dir).removeSuffix(":big") + ":small"; continue }
            val dir = if ((R..R + 3).all { i -> val p = d.center.relative(d.dir, i); abs(groundAt(level, p.x, p.z) - d.center.y) <= 1 }) d.dir
                else entrance(level, d.center, random) ?: d.dir
            build(level, d.center, STYLES[d.zone] ?: CUSTOM, dir, random)
            s.dens[d.center.asLong()] = encode(d.zone, dir)
        }
    }

    private fun build(level: ServerLevel, center: BlockPos, st: Style, dir: Direction, random: net.minecraft.util.RandomSource) {
        fun put(p: BlockPos, block: Block) {
            val cur = level.getBlockState(p)
            if (cur.isAir || cur.canBeReplaced() || BiomeDresser.isNatural(cur)) level.setBlock(p, b(block), FLAGS)
        }
        fun pick() = if (random.nextInt(4) == 0) st.accent else st.main
        val base = center.above()
        // a wide dome: about 4 high in the middle, down to 1 at the edge
        var topY = 0
        for (dx in -R..R) for (dz in -R..R) {
            val d = Math.sqrt((dx * dx + dz * dz).toDouble())
            if (d > R + 0.4) continue
            val h = Math.round(Math.sqrt(Math.max(0.0, (R + 0.6) * (R + 0.6) - d * d)) * 0.85).toInt().coerceAtLeast(1)
            if (dx == 0 && dz == 0) topY = h
            for (dy in 0 until h) put(base.offset(dx, dy, dz), if (dy == h - 1) st.cap else pick())
            // where the ground dips, fill under it so the dome doesn't float
            for (dy in 1..3) {
                val q = base.offset(dx, -dy, dz)
                val cur = level.getBlockState(q)
                if (cur.isAir || cur.canBeReplaced()) level.setBlock(q, b(st.main), FLAGS) else break
            }
        }
        // the hollow (3×3×3) and a tunnel 3 wide and 3 high out to the edge
        val side = dir.clockWise
        for (dx in -1..1) for (dz in -1..1) for (dy in 0..2) level.setBlock(base.offset(dx, dy, dz), b(Blocks.AIR), FLAGS)
        for (i in 2..R + 1) for (w in -1..1) for (dy in 0..2) {
            val p = base.relative(dir, i).relative(side, w).above(dy)
            if (i > R && !level.getBlockState(p).isAir && !level.getBlockState(p).canBeReplaced()) continue
            level.setBlock(p, b(Blocks.AIR), FLAGS)
        }
        // a frame around the way in, so it reads as an entrance
        for (dy in 0..3) for (w in listOf(-2, 2)) put(base.relative(dir, R).relative(side, w).above(dy), st.cap)
        for (w in -2..2) put(base.relative(dir, R).relative(side, w).above(3), st.cap)
        // floor
        st.floor?.let { f -> for (dx in -1..1) for (dz in -1..1) level.setBlock(center.offset(dx, 0, dz), b(f), FLAGS) }
        // a little decoration on top
        st.deco?.let { deco ->
            val top = base.above(topY)
            if (level.getBlockState(top).isAir && !level.getBlockState(top.below()).isAir) level.setBlock(top, b(deco), FLAGS)
        }
    }
}
