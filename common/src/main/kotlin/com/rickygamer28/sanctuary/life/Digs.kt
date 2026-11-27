package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BrushableBlockEntity
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Dig spots: now and then a Ground or Rock type buries something it found, leaving suspicious sand or gravel. Brush
 * it (a vanilla brush) to dig up what's inside: an evolution stone, candies, berries… At most three at a time; an
 * untouched one settles back after two days.
 */
object Digs {
    private val LOOT: ResourceKey<net.minecraft.world.level.storage.loot.LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath("cobblemon_sanctuary", "archaeology/dig_spot"))

    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        if ((now / 20L) % 3L != 0L) return
        // dug up, or settled back
        for ((packed, at) in s.digs.entries.toList()) {
            val p = BlockPos.of(packed)
            if (!level.hasChunk(p.x shr 4, p.z shr 4)) continue
            val st = level.getBlockState(p)
            val buried = st.`is`(Blocks.SUSPICIOUS_SAND) || st.`is`(Blocks.SUSPICIOUS_GRAVEL)
            if (!buried) { s.digs.remove(packed); s.logEvent("Someone dug up a buried treasure."); continue }
            if (now - at > 48000L) { level.setBlock(p, (if (st.`is`(Blocks.SUSPICIOUS_SAND)) Blocks.SAND else Blocks.GRAVEL).defaultBlockState(), 3); s.digs.remove(packed); continue }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x + 0.5, p.y + 1.2, p.z + 0.5, 2, 0.3, 0.2, 0.3, 0.0)
        }
        if (players.isEmpty() || s.digs.size >= 3 || (now / 20L + (s.id.hashCode() and 127)) % 180L != 0L || level.random.nextInt(3) != 0) return
        val digger = rt.live.values.filter { r -> !r.leaving && !r.cave && runCatching { TypeLogic.currentTypes(r.pokemon).any { it == "ground" || it == "rock" } }.getOrDefault(false) }.randomOrNull() ?: return
        val e = digger.entity
        repeat(8) {
            val x = e.blockX + level.random.nextInt(7) - 3
            val z = e.blockZ + level.random.nextInt(7) - 3
            val p = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z)
            val st = level.getBlockState(p)
            val sand = st.`is`(Blocks.SAND) || st.`is`(Blocks.RED_SAND)
            val gravel = st.`is`(Blocks.GRAVEL) || st.`is`(Blocks.DIRT) || st.`is`(Blocks.COARSE_DIRT) || st.`is`(Blocks.GRASS_BLOCK)
            if (!sand && !gravel || !level.getBlockState(p.above()).isAir) return@repeat
            level.setBlock(p, (if (sand) Blocks.SUSPICIOUS_SAND else Blocks.SUSPICIOUS_GRAVEL).defaultBlockState(), 3)
            (level.getBlockEntity(p) as? BrushableBlockEntity)?.setLootTable(LOOT, level.random.nextLong())
            s.digs[p.asLong()] = now
            level.sendParticles(ParticleTypes.POOF, p.x + 0.5, p.y + 1.0, p.z + 0.5, 8, 0.3, 0.2, 0.3, 0.02)
            val name = digger.pokemon.getDisplayName(false).string
            val where = Residents.zoneName(ZoneLayout.locate(s, x, z)?.zone)
            s.logEvent("$name buried something in the $where. Brush the spot to dig it up!")
            for (pl in players) pl.displayClientMessage(Component.literal("$name buried something in the $where! Find the sparkling spot and brush it.").withStyle(ChatFormatting.YELLOW), true)
            Sanctuaries.data(level).setDirty()
            return
        }
    }
}
