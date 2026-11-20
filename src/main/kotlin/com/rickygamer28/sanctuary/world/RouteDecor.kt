package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.life.Requests
import com.rickygamer28.sanctuary.life.Residents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.StandingSignBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What the Valley Heart adds to the valley once it's built: benches, flower posts and signposts along the routes,
 * and lantern posts around the landmarks. They go up a bit at a time where the ground is loaded, and come back if
 * a rebuild takes them away.
 */
object RouteDecor {
    private const val FLAGS = Block.UPDATE_CLIENTS
    private const val STEP = 26.0
    private val POTS = listOf(Blocks.POTTED_POPPY, Blocks.POTTED_DANDELION, Blocks.POTTED_BLUE_ORCHID, Blocks.POTTED_ALLIUM, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_OXEYE_DAISY)

    private fun b(block: Block): BlockState = block.defaultBlockState()

    /** Called every two seconds once the valley is built. */
    fun tick(level: ServerLevel, s: Sanctuary) {
        val lv = Requests.level(s)
        if (lv >= Requests.LIGHTS_LEVEL) lights(level, s)
        if (lv < Requests.DECOR_LEVEL) return
        heal(level, s)
        var placed = 0
        for (zone in s.habitats.keys) {
            if (zone !in s.unlocked) continue
            val points = BiomeDresser.routePoints(s, zone, STEP)
            if (points.size < 3) continue
            for ((i, p) in points.withIndex()) {
                if (i == 0) continue
                val key = "$zone#$i"
                if (key in s.decorDone) continue
                val x = floor(p.x).toInt()
                val z = floor(p.z).toInt()
                if (!level.hasChunk(x shr 4, z shr 4) || !level.hasChunk((x + 4) shr 4, (z + 4) shr 4) || !level.hasChunk((x - 4) shr 4, (z - 4) shr 4)) continue
                val last = i == points.size - 1
                val kind = when { i == 1 || last -> "sign"; Math.floorMod(i + zone.hashCode(), 2) == 0 -> "bench"; else -> "flowers" }
                val side = if ((i + zone.hashCode()) % 2 == 0) 1 else -1
                runCatching { put(level, s, zone, p, kind, side, last) }
                s.decorDone += key
                if (++placed >= 3) break
            }
            if (placed >= 3) break
        }
        if (placed > 0) Sanctuaries.data(level).setDirty()
    }

    /** A spot just off the path, if the ground there is finished and free. */
    private fun spot(level: ServerLevel, s: Sanctuary, x: Int, z: Int): BlockPos? {
        if (!level.hasChunk(x shr 4, z shr 4)) return null
        if (BiomeDresser.routeDistance(s, x, z) < 2.2 || Landmarks.near(s, x, z)) return null
        val idx = ColumnOrder.indexOf(x - s.center.x, z - s.center.z)
        if (idx < 0 || !s.dressed.get(idx)) return null
        val pos = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)
        val below = level.getBlockState(pos.below())
        if (!BiomeDresser.isNatural(below) || !below.fluidState.isEmpty) return null
        val here = level.getBlockState(pos)
        if (!here.isAir && !here.canBeReplaced()) return null
        if (!level.getBlockState(pos.above()).isAir) return null
        return pos
    }

    private fun put(level: ServerLevel, s: Sanctuary, zone: String, p: BiomeDresser.RoutePoint, kind: String, side: Int, last: Boolean) {
        // across the path, pointing away from it on this side
        val nx = p.dz * side
        val nz = -p.dx * side
        val bx = floor(p.x + nx * 3.2).toInt()
        val bz = floor(p.z + nz * 3.2).toInt()
        val away = Direction.getNearest(nx, 0.0, nz)
        when (kind) {
            "bench" -> {
                val along = Direction.getNearest(p.dx, 0.0, p.dz)
                val a = spot(level, s, bx, bz) ?: return
                val c = spot(level, s, bx + along.stepX, bz + along.stepZ)?.takeIf { it.y == a.y } ?: return
                val stair = b(Blocks.SPRUCE_STAIRS).setValue(StairBlock.FACING, away)
                for (q in listOf(a, c)) { level.setBlock(q, stair, FLAGS); s.decor[q.asLong()] = "bench" }
            }
            "flowers" -> {
                val a = spot(level, s, bx, bz) ?: return
                if (!level.getBlockState(a.above(2)).isAir) return
                level.setBlock(a, b(Blocks.SPRUCE_FENCE), FLAGS)
                val pot = POTS[Math.floorMod(bx * 31 + bz * 17, POTS.size)]
                level.setBlock(a.above(), b(pot), FLAGS)
                s.decor[a.asLong()] = "flowers"
            }
            "sign" -> {
                val a = spot(level, s, bx, bz) ?: return
                // the sign's writing faces the path
                val angle = atan2(nx, -nz)
                val rot = Math.floorMod((angle / (Math.PI / 8)).roundToInt(), 16)
                level.setBlock(a, b(Blocks.OAK_SIGN).setValue(StandingSignBlock.ROTATION, rot), FLAGS)
                val name = Residents.zoneName(zone)
                val lines = if (last) listOf("Welcome to", "the", name, "") else listOf("This way to", "the", name, "→")
                (level.getBlockEntity(a) as? SignBlockEntity)?.let { be ->
                    var text = SignText()
                    lines.forEachIndexed { i, l -> text = text.setMessage(i, Component.literal(l)) }
                    be.setText(text, true)
                    be.setWaxed(true)
                    be.setChanged()
                    level.sendBlockUpdated(a, level.getBlockState(a), level.getBlockState(a), Block.UPDATE_ALL)
                }
                s.decor[a.asLong()] = "sign"
            }
        }
    }

    /** Decorations a rebuild took away: let their route put them up again. */
    private fun heal(level: ServerLevel, s: Sanctuary) {
        if (s.decor.isEmpty()) return
        val missing = ArrayList<Long>()
        for ((packed, kind) in s.decor.entries.take(400)) {
            val p = BlockPos.of(packed)
            if (!level.hasChunk(p.x shr 4, p.z shr 4)) continue
            val st = level.getBlockState(p)
            val ok = when (kind) { "bench" -> st.`is`(Blocks.SPRUCE_STAIRS); "flowers" -> st.`is`(Blocks.SPRUCE_FENCE); else -> st.`is`(Blocks.OAK_SIGN) }
            if (!ok) missing += packed
        }
        if (missing.isEmpty()) return
        for (m in missing) s.decor.remove(m)
        // redo the routes those were on (the ones still standing are skipped: their spots aren't free)
        s.decorDone.clear()
    }

    /** Lantern posts around every landmark. */
    private fun lights(level: ServerLevel, s: Sanctuary) {
        for ((zone, packed) in s.landmarks) {
            if (packed == Landmarks.NONE || zone in s.litLandmarks) continue
            val c = BlockPos.of(packed)
            if (!level.hasChunk((c.x - 8) shr 4, (c.z - 8) shr 4) || !level.hasChunk((c.x + 8) shr 4, (c.z + 8) shr 4)) continue
            val lantern = if (zone == "ghost" || zone == "dark" || zone == "shadow" || zone == "eldritch") Blocks.SOUL_LANTERN else Blocks.LANTERN
            for ((dx, dz) in listOf(6 to 0, -6 to 0, 0 to 6, 0 to -6)) {
                val x = c.x + dx
                val z = c.z + dz
                val pos = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)
                val below = level.getBlockState(pos.below())
                if (!BiomeDresser.isNatural(below) || !below.fluidState.isEmpty) continue
                if (!(1..3).all { level.getBlockState(pos.above(it - 1)).isAir }) continue
                level.setBlock(pos, b(Blocks.SPRUCE_FENCE), FLAGS)
                level.setBlock(pos.above(), b(Blocks.SPRUCE_FENCE), FLAGS)
                level.setBlock(pos.above(2), b(lantern), FLAGS)
            }
            s.litLandmarks += zone
            Sanctuaries.data(level).setDirty()
            return
        }
    }
}
