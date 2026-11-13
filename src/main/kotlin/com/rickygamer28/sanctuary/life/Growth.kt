package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.server.level.ServerLevel
import kotlin.math.roundToInt

/**
 * Growing up in the sanctuary: every Pokémon living there slowly grows fonder of its trainer (enough, in time, for
 * friendship evolutions), and play-fights give a little experience.
 */
object Growth {
    private val SOURCE = SidemodExperienceSource(CobblemonSanctuary.MOD_ID)

    /** Friendship that friendship evolutions need. */
    const val FOND = 160

    /** Called every second, whether anyone is in the sanctuary or not. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val minutes = SanctuaryConfig.friendshipGrowthMinutes.get()
        val points = SanctuaryConfig.friendshipGrowthPoints.get()
        if (minutes <= 0 || points <= 0) return
        if ((now / 20L + (s.id.hashCode() and 1023)) % (60L * minutes) != 0L) return
        for (e in rt.roster) fonder(s, e.pokemon, points)
    }

    /** Raises a Pokémon's friendship, and notes when it becomes fond enough to evolve by friendship. */
    fun fonder(s: Sanctuary, pokemon: Pokemon, points: Int) {
        runCatching {
            val before = pokemon.friendship
            if (before >= 255) return
            pokemon.incrementFriendship(points)
            if (before < FOND && pokemon.friendship >= FOND) {
                s.logEvent("${pokemon.getDisplayName(false).string} has grown really fond of its trainer.")
            }
        }
    }

    /** A little experience after a play-fight. */
    fun playFightXp(s: Sanctuary, r: LiveResident) {
        if (!SanctuaryConfig.playFightXp.get()) return
        val pokemon = r.pokemon
        if (pokemon.level >= SanctuaryConfig.playFightXpMaxLevel.get()) return
        val xp = ((5 + pokemon.level) * SanctuaryConfig.playFightXpRate.get()).roundToInt()
        if (xp <= 0) return
        runCatching {
            val result = pokemon.addExperience(SOURCE, xp)
            if (result.newLevel > result.oldLevel) s.logEvent("${r.name} grew to level ${result.newLevel} from play-fighting!")
        }.onFailure { CobblemonSanctuary.LOGGER.debug("Sanctuary: couldn't give play-fight experience", it) }
    }
}
