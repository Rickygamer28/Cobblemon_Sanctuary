package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.UUID

/**
 * Changes the real Minecraft biome under each sanctuary biome, so grass, leaves and water get the right
 * colours and the weather fits (snow in the Ice biome, no rain in the desert, and so on).
 */
object BiomePainter {
    private val pending = HashMap<UUID, LinkedHashSet<Long>>()

    fun mark(s: Sanctuary, x: Int, z: Int) {
        pending.getOrPut(s.id) { LinkedHashSet() } += ChunkPos.asLong(x shr 4, z shr 4)
    }

    fun forget(id: UUID) { pending.remove(id) }

    /** Repaints up to [max] chunks that were changed. */
    fun tick(level: ServerLevel, s: Sanctuary, max: Int = 6) {
        val set = pending[s.id] ?: return
        if (set.isEmpty()) return
        val biomes = level.registryAccess().registryOrThrow(Registries.BIOME)
        val ring = ZoneLayout.ring()
        val holders = HashMap<String, Holder<Biome>?>()
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
                    val hit = ZoneLayout.locate(s, x, z, ring)
                    val zone = hit?.zone
                    val inside = zone != null && zone in s.unlocked && (zone == ZoneLayout.HUB || hit.dist <= ZoneLayout.extent(s, zone))
                    val holder = if (inside) holders.getOrPut("$zone") {
                        BiomeDresser.biomeKey(zone!!, BiomeDresser.variantOf(s, zone))?.let { k -> biomes.getHolder(k).orElse(null) }
                    } else null
                    holder ?: chunk.getNoiseBiome(qx, qy, qz)
                }
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
