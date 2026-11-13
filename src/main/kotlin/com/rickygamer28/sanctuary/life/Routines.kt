package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.CobblemonMemories
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Daily life: at night most residents curl up next to a friend and sleep until morning, while Ghost and Dark types
 * come out to play; during the day they sometimes meet at a berry tree for a meal.
 */
object Routines {
    fun isNight(level: ServerLevel): Boolean = (level.dayTime % 24000L) in 13000L..23000L

    /** Ghost and Dark types are night owls. */
    fun nocturnal(r: LiveResident): Boolean = runCatching { TypeLogic.currentTypes(r.pokemon).any { it == "ghost" || it == "dark" } }.getOrDefault(false)

    private fun surface(level: ServerLevel, x: Int, z: Int) = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)

    /** Returns true while the resident is busy with its routine this second. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long): Boolean {
        val night = isNight(level)
        val owl = nocturnal(r)
        // ---- sleeping
        if (r.napping) {
            if (!night || owl) { wake(r); return false }
            keepAsleep(r)
            return true
        }
        if (night && !owl && !r.swimmer && !r.flyerAway()) {
            val spot = r.napSpot
            if (spot != null) {
                if (r.entity.blockPosition().closerThan(spot, 2.5) || now > r.napGoalUntil) startNap(r)
                else if (!r.entity.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) Residents.walkTo(r.entity, spot, 0.35f, 1)
                return true
            }
            if (level.random.nextInt(15) == 0) findNapSpot(level, s, rt, r, now)
            return false
        }
        r.napSpot = null
        // ---- a meal at a berry tree (daytime)
        val snack = r.snackSpot
        if (snack != null) {
            when {
                now > r.snackUntil -> r.snackSpot = null
                r.entity.blockPosition().closerThan(snack, 2.8) -> { eat(level, s, rt, r, snack, now); r.snackSpot = null }
                !r.entity.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET) -> Residents.walkTo(r.entity, snack, 0.45f, 1)
            }
            return true
        }
        if (!night && !r.swimmer && level.random.nextInt(700) == 0) planMeal(level, s, rt, r, now)
        return false
    }

    private fun LiveResident.flyerAway() = migrating

    // ------------------------------------------------------------------ sleeping together

    private fun findNapSpot(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long) {
        val friend = rt.live.values.filter {
            it !== r && !it.napping && it.napSpot == null && !it.swimmer && !nocturnal(it) && !it.entity.isRemoved &&
                it.entity.distanceToSqr(r.entity) < 32.0 * 32.0 && s.relation(r.pokemonId, it.pokemonId) >= 20
        }.maxByOrNull { s.relation(r.pokemonId, it.pokemonId) }
        if (friend != null) {
            val mx = ((r.entity.x + friend.entity.x) / 2).toInt()
            val mz = ((r.entity.z + friend.entity.z) / 2).toInt()
            r.napSpot = surface(level, mx, mz)
            friend.napSpot = surface(level, mx + 1, mz)
            r.napGoalUntil = now + 20L * 30
            friend.napGoalUntil = now + 20L * 30
            Effects.hearts(r.entity); Effects.hearts(friend.entity)
            if (level.random.nextInt(8) == 0) s.logEvent("${r.name} and ${friend.name} curled up together for the night.")
        } else if (level.random.nextInt(3) == 0) {
            r.napSpot = r.entity.blockPosition()
            r.napGoalUntil = now
        }
    }

    private fun startNap(r: LiveResident) {
        r.napping = true
        r.napSpot = null
        Residents.stop(r.entity)
        keepAsleep(r)
    }

    private fun keepAsleep(r: LiveResident) {
        val brain = r.entity.brain
        brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        runCatching { brain.setMemory(CobblemonMemories.POKEMON_DROWSY, true) }
        runCatching { brain.setMemory(CobblemonMemories.POKEMON_SLEEPING, true) }
    }

    fun wake(r: LiveResident) {
        r.napping = false
        r.napSpot = null
        val brain = r.entity.brain
        runCatching { brain.eraseMemory(CobblemonMemories.POKEMON_SLEEPING) }
        runCatching { brain.eraseMemory(CobblemonMemories.POKEMON_DROWSY) }
        if (r.entity.level().random.nextInt(3) == 0) Effects.play(r.entity, Effects.CRY)
    }

    // ------------------------------------------------------------------ meals at berry trees

    private fun isBerryTree(level: ServerLevel, pos: BlockPos): Boolean {
        val st = level.getBlockState(pos)
        if (st.`is`(Blocks.SWEET_BERRY_BUSH)) return true
        val id = BuiltInRegistries.BLOCK.getKey(st.block)
        return id.namespace == "cobblemon" && id.path.endsWith("_berry")
    }

    private fun planMeal(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long) {
        val here = r.entity.blockPosition()
        val spot = s.berrySpots.asSequence().map { BlockPos.of(it) }
            .filter { it.distSqr(here) < 48.0 * 48.0 }
            .minByOrNull { it.distSqr(here) } ?: return
        if (!level.isLoaded(spot)) return
        if (!isBerryTree(level, spot)) { s.berrySpots.remove(spot.asLong()); return }
        val group = listOf(r) + rt.live.values.filter {
            it !== r && it.snackSpot == null && !it.napping && !it.swimmer && now >= it.busyUntil &&
                it.entity.distanceToSqr(r.entity) < 14.0 * 14.0 && s.relation(r.pokemonId, it.pokemonId) >= 0
        }.shuffled().take(2)
        group.forEachIndexed { i, m ->
            val dir = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)[i % 4]
            m.snackSpot = surface(level, spot.x + dir.first * 2, spot.z + dir.second * 2)
            m.snackUntil = now + 20L * 30
            m.waypoints.clear()
        }
        if (group.size > 1 && level.random.nextInt(5) == 0) s.logEvent("${group.joinToString(", ") { it.name }} met at a berry tree for a snack.")
    }

    private fun eat(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, spot: BlockPos, now: Long) {
        r.busyUntil = now + 100
        Residents.stop(r.entity)
        r.entity.lookControl.setLookAt(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5)
        Effects.play(r.entity, listOf("eat", "status", "cry"))
        val st = level.getBlockState(spot)
        val item = st.block.asItem().takeIf { it != Items.AIR } ?: Items.SWEET_BERRIES
        level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, ItemStack(item)), r.entity.x, r.entity.y + r.entity.bbHeight * 0.8, r.entity.z, 8, 0.2, 0.1, 0.2, 0.05)
        Effects.happy(r.entity)
        // sharing a meal brings them closer
        for (other in rt.live.values) {
            if (other === r || other.entity.distanceToSqr(r.entity) > 5.0 * 5.0) continue
            if (other.busyUntil > now || other.snackSpot != null) s.addRelation(r.pokemonId, other.pokemonId, 2)
        }
        @Suppress("UNUSED_EXPRESSION") BlockTags.LEAVES
    }
}
