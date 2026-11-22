package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** A wild Pokémon staying in the sanctuary for a day. */
class Visitor(val id: UUID, val pokemon: CompoundTag, val zone: String, val arrived: Long, var trust: Int = 0, var lastPet: Long = 0L) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("Id", id); it.put("Mon", pokemon); it.putString("Zone", zone); it.putLong("At", arrived); it.putInt("Trust", trust)
    }

    companion object {
        fun load(t: CompoundTag) = Visitor(t.getUUID("Id"), t.getCompound("Mon"), t.getString("Zone"), t.getLong("At"), t.getInt("Trust"))
    }
}

/**
 * Wild visitors: about once a day a Pokémon nobody owns wanders into the valley and stays a day in a habitat that
 * suits it. Pet it (with the Journal) and feed it a berry, and residents keep it company; once it trusts you,
 * ask it to stay and it moves into your PC. The higher the Valley Heart, the more often they come and the rarer
 * they can be (now and then a shiny, at the Golden Heart). Legendaries, mythicals, Ultra Beasts and paradox
 * Pokémon never visit.
 */
object Visitors {
    const val TAG = "sanctuary_visitor"
    private const val STAY = 24000L
    const val TRUST_TO_STAY = 60
    private val NEVER = setOf("legendary", "mythical", "ultra_beast", "paradox")

    /** The visitors' Pokémon out in the world right now. */
    private val out = HashMap<UUID, PokemonEntity>()
    private val nextAt = HashMap<UUID, Long>()

    fun isVisitor(e: PokemonEntity) = e.tags.contains(TAG)

    private fun pokemonOf(level: ServerLevel, v: Visitor): Pokemon? = runCatching { Pokemon().loadFromNBT(level.registryAccess(), v.pokemon) }.getOrNull()

    /** Called every second for every sanctuary. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        if (!SanctuaryConfig.visitorsEnabled.get()) return
        if ((now / 20L) % 5L != 0L) return
        // a visitor whose day is over goes; one that was caught is gone
        for (v in s.visitors.toList()) {
            val e = out[v.id]
            if (e != null && (e.isRemoved || e.pokemon.isPlayerOwned())) {
                out.remove(v.id)
                if (e.pokemon.isPlayerOwned()) { s.visitors.remove(v); s.logEvent("The visiting ${e.pokemon.species.translatedName.string} was caught."); continue }
            }
            if (now - v.arrived > STAY) {
                s.visitors.remove(v)
                out.remove(v.id)?.discard()
                s.logEvent("The visiting ${name(level, v)} left the valley.")
                Sanctuaries.data(level).setDirty()
            }
        }
        // someone new arrives
        val lv = Requests.level(s)
        val every = (STAY / (SanctuaryConfig.visitorsPerDay.get().coerceAtLeast(0.05) * (1.0 + lv / 4.0))).toLong()
        val due = nextAt.getOrPut(s.id) { now + every / 2 }
        if (now >= due && s.visitors.size < 3 && rt.roster.isNotEmpty()) {
            nextAt[s.id] = now + every
            arrive(level, s, rt, now)?.let { v ->
                s.visitors += v
                val n = name(level, v)
                s.logEvent("A wild $n is visiting the ${Residents.zoneName(v.zone)}!")
                for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.sendSystemMessage(
                    Component.literal("✦ A wild $n is visiting the ${Residents.zoneName(v.zone)}! Befriend it and it might stay.").withStyle(ChatFormatting.AQUA))
                Sanctuaries.data(level).setDirty()
            }
        }
        // out when someone's near its habitat, kept close to it; residents nearby help it settle in
        for (v in s.visitors) {
            val anchor = ZoneLayout.anchor(s, v.zone) ?: continue
            val near = players.any { it.blockPosition().closerThan(BlockPos(anchor.first, it.blockY, anchor.second), 96.0) }
            val e = out[v.id]
            if (!near) { if (e != null) { e.discard(); out.remove(v.id) }; continue }
            if (e == null || e.isRemoved) { spawn(level, s, v, anchor)?.let { out[v.id] = it }; continue }
            if (e.distanceToSqr(anchor.first + 0.5, e.y, anchor.second + 0.5) > 28.0 * 28.0)
                Residents.walkTo(e, Residents.surface(level, anchor.first, anchor.second), 0.5f, 3)
            if (level.random.nextInt(3) == 0) level.sendParticles(ParticleTypes.END_ROD, e.x, e.y + e.bbHeight + 0.3, e.z, 2, 0.2, 0.1, 0.2, 0.0)
            val company = rt.live.values.count { it.entity.distanceToSqr(e) < 100.0 }
            if (company >= 2 && level.random.nextInt(4) == 0) v.trust = (v.trust + 1).coerceAtMost(100)
        }
    }

    private fun name(level: ServerLevel, v: Visitor) = pokemonOf(level, v)?.species?.translatedName?.string ?: "Pokémon"

    /** Picks who comes: a Pokémon that suits an awake habitat, about as strong as the residents. */
    private fun arrive(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long): Visitor? {
        val r = level.random
        val lv = Requests.level(s)
        val awake = s.unlocked.filter { it in s.habitats && it != ZoneLayout.HUB }.toSet()
        if (awake.isEmpty()) return null
        val pool = runCatching { PokemonSpecies.implemented }.getOrDefault(emptyList()).filter { sp ->
            sp.labels.none { it in NEVER } && sp.types.any { t -> ZoneLayout.habitatOf(t.name.lowercase()) in awake }
        }
        if (pool.isEmpty()) return null
        // common ones come most; the Heart makes rarer ones likelier
        val flat = (lv / 20.0).coerceIn(0.0, 0.75)
        val weights = pool.map { Math.pow(it.catchRate.coerceAtLeast(3).toDouble(), 1.0 - flat) }
        var pick = r.nextDouble() * weights.sum()
        var sp = pool.last()
        for ((i, w) in weights.withIndex()) { pick -= w; if (pick <= 0) { sp = pool[i]; break } }
        val levels = rt.roster.map { it.pokemon.level }.sorted()
        val level0 = (levels[levels.size / 2] + r.nextInt(11) - 5).coerceIn(1, 100)
        val pk = runCatching { sp.create(level0) }.getOrNull() ?: return null
        if (r.nextInt(if (lv >= Requests.GOLDEN_LEVEL) 64 else 512) == 0) pk.shiny = true
        val zone = sp.types.mapNotNull { ZoneLayout.habitatOf(it.name.lowercase()) }.firstOrNull { it in awake } ?: return null
        val tag = runCatching { pk.saveToNBT(level.registryAccess(), CompoundTag()) }.getOrNull() ?: return null
        return Visitor(UUID.randomUUID(), tag, zone, now)
    }

    private fun spawn(level: ServerLevel, s: Sanctuary, v: Visitor, anchor: Pair<Int, Int>): PokemonEntity? {
        if (!level.hasChunk(anchor.first shr 4, anchor.second shr 4)) return null
        val pk = pokemonOf(level, v) ?: return null
        val pos = Residents.surface(level, anchor.first + level.random.nextInt(9) - 4, anchor.second + level.random.nextInt(9) - 4)
        val e = PokemonEntity(level, pk)
        e.addTag(TAG)
        e.setPersistenceRequired()
        e.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360f, 0f)
        return if (level.addFreshEntity(e)) e else null
    }

    /** Someone right-clicked a visitor: a berry feeds it, the Journal pets it (or asks it to stay, once it trusts you). */
    fun interact(player: ServerPlayer, e: PokemonEntity, journal: Boolean): Boolean {
        val level = player.serverLevel()
        val s = Sanctuaries.at(level, e.blockPosition()) ?: return false
        val v = s.visitors.firstOrNull { out[it.id] === e } ?: return false
        if (!s.isMember(player.uuid)) return true
        val n = e.pokemon.species.translatedName.string
        val now = level.gameTime
        val hand = player.mainHandItem
        when {
            !journal && hand.`is`(net.minecraft.tags.ItemTags.create(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobblemon", "berries"))) -> {
                if (!player.isCreative) hand.shrink(1)
                v.trust = (v.trust + 25).coerceAtMost(100)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, e.x, e.y + e.bbHeight, e.z, 6, 0.3, 0.3, 0.3, 0.0)
                player.displayClientMessage(Component.literal("$n eats the berry happily. Trust: ${v.trust}/$TRUST_TO_STAY").withStyle(ChatFormatting.GREEN), true)
            }
            journal && v.trust >= TRUST_TO_STAY -> {
                val pc = runCatching { Cobblemon.storage.getPC(player) }.getOrNull()
                val pk = e.pokemon
                runCatching { pk.setOriginalTrainer(player.uuid) }
                if (pc == null || !pc.add(pk)) { player.displayClientMessage(Component.literal("Your PC has no room for $n.").withStyle(ChatFormatting.RED), true); return true }
                s.visitors.remove(v)
                out.remove(v.id)
                e.discard()
                s.logEvent("The visiting $n decided to stay! It now lives in ${player.gameProfile.name}'s PC.")
                player.sendSystemMessage(Component.literal("✦ $n decided to stay with you! It's in your PC and lives in the sanctuary now.").withStyle(ChatFormatting.AQUA))
                Residents.runtime(s).rosterDirty = true
            }
            journal -> {
                if (now - v.lastPet > 600) { v.trust = (v.trust + 15).coerceAtMost(100); v.lastPet = now }
                level.sendParticles(ParticleTypes.HEART, e.x, e.y + e.bbHeight, e.z, 3, 0.3, 0.2, 0.3, 0.0)
                val hint = if (v.trust >= TRUST_TO_STAY) " It trusts you: use the Journal again to ask it to stay!" else " (feed it a berry too)"
                player.displayClientMessage(Component.literal("$n lets you pet it. Trust: ${v.trust}/$TRUST_TO_STAY.$hint").withStyle(ChatFormatting.GREEN), true)
            }
            else -> return false
        }
        Sanctuaries.data(level).setDirty()
        return true
    }
}
