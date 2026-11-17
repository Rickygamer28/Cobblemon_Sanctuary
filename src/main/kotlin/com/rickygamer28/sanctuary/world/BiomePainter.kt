package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.UUID
import kotlin.math.sqrt

/**
 * Paints the Sanctuary's own biomes under each type biome, so grass, leaves, water, sky and fog get that type's
 * colours, particles, music and weather (snow in the Ice biome, no rain in the desert, and so on).
 */
object BiomePainter {
    /** Height where the painted biomes are compared (they're the same all the way up). */
    private val SanctuaryDimensionY = com.rickygamer28.sanctuary.SanctuaryDimension.GROUND_Y
    private val pending = HashMap<UUID, LinkedHashSet<Long>>()

    fun mark(s: Sanctuary, x: Int, z: Int) {
        pending.getOrPut(s.id) { LinkedHashSet() } += ChunkPos.asLong(x shr 4, z shr 4)
    }

    fun forget(id: UUID) { pending.remove(id) }

    /** Repaints up to [max] chunks that were changed. */
    fun tick(level: ServerLevel, s: Sanctuary, max: Int = 8) {
        val set = pending[s.id] ?: return
        if (set.isEmpty()) return
        val biomes = level.registryAccess().registryOrThrow(Registries.BIOME)
        val ring = ZoneLayout.ring()
        val holders = HashMap<ResourceKey<Biome>, Holder<Biome>?>()
        fun holder(key: ResourceKey<Biome>) = holders.getOrPut(key) { biomes.getHolder(key).orElse(null) }
        val outer = (s.size.radius + Sanctuaries.RIM).toDouble()
        val done = ArrayList<ChunkAccess>()
        val it = set.iterator()
        while (it.hasNext() && done.size < max) {
            val key = it.next()
            it.remove()
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            if (!level.hasChunk(cx, cz)) continue
            val chunk = level.getChunk(cx, cz)
            try {
                val resolver = BiomeResolver { qx, qy, qz, _ ->
                    val x = (qx shl 2) + 2
                    val z = (qz shl 2) + 2
                    val ddx = (x - s.center.x).toDouble()
                    val ddz = (z - s.center.z).toDouble()
                    val dist = sqrt(ddx * ddx + ddz * ddz)
                    val biome = when {
                        dist > outer -> null
                        dist > s.size.radius -> BiomeDresser.RIM_BIOME
                        else -> {
                            val zone = ZoneLayout.locate(s, x, z, ring)?.zone
                            if (zone != null && zone in s.unlocked) BiomeDresser.biomeKey(zone) else BiomeDresser.MEADOW_BIOME
                        }
                    }
                    biome?.let { holder(it) } ?: chunk.getNoiseBiome(qx, qy, qz)
                }
                // only when something actually changes: re-sending makes the player's game redraw the whole chunk
                // (water flickers while it does), and a chunk is marked again each time another column in it is built
                val section = chunk.getSectionIndex(SanctuaryDimensionY)
                val qy = SanctuaryDimensionY shr 2
                var changed = false
                loop@ for (qx in (cx shl 2) until (cx shl 2) + 4) for (qz in (cz shl 2) until (cz shl 2) + 4) {
                    val want = resolver.getNoiseBiome(qx, qy, qz, level.chunkSource.randomState().sampler())
                    if (want != chunk.getNoiseBiome(qx, qy, qz)) { changed = true; break@loop }
                }
                if (section < 0 || !changed) continue
                chunk.fillBiomesFromNoise(resolver, level.chunkSource.randomState().sampler())
                chunk.setUnsaved(true)
                done += chunk
            } catch (e: Exception) {
                CobblemonSanctuary.LOGGER.warn("Couldn't paint biomes in chunk $cx,$cz", e)
            }
        }
        if (done.isNotEmpty()) level.chunkSource.chunkMap.resendBiomesForChunks(done)
    }
}
