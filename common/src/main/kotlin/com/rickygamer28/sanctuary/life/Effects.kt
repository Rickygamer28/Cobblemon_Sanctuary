package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel

/**
 * Plays the animations each Pokémon model already has. Names are given in order of preference;
 * models that lack the first one fall back to the next (every model has at least its idle/blink quirks).
 */
object Effects {
    val ATTACK = listOf("physical", "special", "status", "cry")
    val ATTACK_SPECIAL = listOf("special", "physical", "status", "cry")
    val SHOW_OFF = listOf("status", "special", "cry")
    val HIT = listOf("recoil", "cry")
    val CRY = listOf("cry", "status")

    fun play(entity: PokemonEntity, names: List<String>) {
        val level = entity.level() as? ServerLevel ?: return
        val choice = if (entity.isFlying()) names.map { "air_$it" } + names else names
        val packet = PlayPosableAnimationPacket(entity.id, LinkedHashSet(choice), emptyList())
        val near = level.players().filter { it.distanceToSqr(entity) < 64.0 * 64.0 }
        if (near.isNotEmpty()) CobblemonNetwork.sendPacketToPlayers(near, packet)
    }

    fun particles(entity: PokemonEntity, type: ParticleOptions, count: Int, spread: Double = 0.35) {
        val level = entity.level() as? ServerLevel ?: return
        level.sendParticles(type, entity.x, entity.y + entity.bbHeight + 0.3, entity.z, count, spread, 0.2, spread, 0.02)
    }

    fun hearts(entity: PokemonEntity) = particles(entity, ParticleTypes.HEART, 3)
    fun angry(entity: PokemonEntity) = particles(entity, ParticleTypes.ANGRY_VILLAGER, 2)
    fun sparks(entity: PokemonEntity) {
        val level = entity.level() as? ServerLevel ?: return
        level.sendParticles(ParticleTypes.CRIT, entity.x, entity.y + entity.bbHeight * 0.6, entity.z, 8, 0.3, 0.3, 0.3, 0.15)
    }
    fun happy(entity: PokemonEntity) = particles(entity, ParticleTypes.HAPPY_VILLAGER, 5, 0.5)
    fun poof(entity: PokemonEntity) {
        val level = entity.level() as? ServerLevel ?: return
        level.sendParticles(ParticleTypes.POOF, entity.x, entity.y + 0.3, entity.z, 8, 0.4, 0.2, 0.4, 0.02)
    }
}
