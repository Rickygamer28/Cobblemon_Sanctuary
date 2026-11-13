package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * How a resident behaves, from its nature. Each value is a multiplier on how often it does something
 * (1.0 = like everyone else).
 */
enum class Personality(
    val label: String,
    val blurb: String,
    /** Meeting other residents. */
    val meet: Double,
    /** Play-fights. */
    val fight: Double,
    /** Games of tag. */
    val tag: Double,
    /** Nuzzles, greetings, and how fast it makes friends. */
    val affection: Double,
    /** Wandering across its whole biome. */
    val explore: Double,
    /** Berry meals. */
    val appetite: Double,
    /** Dozing off during the day. */
    val doze: Double,
    /** Visiting the logs, rocks, pools and other spots of its biome. */
    val props: Double,
    /** Going back to its favourite spot. */
    val homebody: Double,
    /** Slips away from crowds. */
    val shy: Boolean = false,
) {
    PLAYFUL("Playful", "Loves games of tag and is always up for fun", 1.4, 1.2, 2.0, 1.0, 1.2, 1.0, 0.3, 1.0, 0.8),
    BOLD("Bold", "Picks play-fights and explores fearlessly", 1.1, 2.0, 0.8, 0.8, 1.4, 1.0, 0.4, 0.8, 0.7),
    GENTLE("Gentle", "Makes friends easily and loves a nuzzle", 1.2, 0.6, 1.0, 1.7, 0.9, 1.0, 0.8, 1.0, 1.0),
    LAID_BACK("Laid-back", "Naps in the sun and never misses a meal", 0.8, 0.6, 0.6, 1.0, 0.5, 1.8, 3.0, 0.9, 1.5),
    SHY("Shy", "Keeps away from crowds and sticks to its favourite spot", 0.5, 0.6, 0.8, 1.2, 0.7, 1.0, 1.0, 1.0, 2.4, shy = true),
    CURIOUS("Curious", "Explores everywhere and pokes at everything", 1.0, 1.0, 1.1, 1.0, 1.8, 1.1, 0.5, 1.9, 0.8),
    /** Personalities turned off in the config: everyone behaves the same. */
    NEUTRAL("", "", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);

    companion object {
        private val BY_NATURE = mapOf(
            "naughty" to PLAYFUL, "impish" to PLAYFUL, "hasty" to PLAYFUL, "jolly" to PLAYFUL,
            "brave" to BOLD, "adamant" to BOLD, "bold" to BOLD, "rash" to BOLD, "sassy" to BOLD,
            "lonely" to GENTLE, "docile" to GENTLE, "mild" to GENTLE, "calm" to GENTLE, "gentle" to GENTLE,
            "relaxed" to LAID_BACK, "lax" to LAID_BACK, "quiet" to LAID_BACK,
            "timid" to SHY, "modest" to SHY, "bashful" to SHY, "careful" to SHY,
            "hardy" to CURIOUS, "serious" to CURIOUS, "naive" to CURIOUS, "quirky" to CURIOUS,
        )

        fun of(pokemon: Pokemon): Personality =
            if (!com.rickygamer28.sanctuary.SanctuaryConfig.personalities.get()) NEUTRAL else runCatching { BY_NATURE[pokemon.nature.name.path.lowercase()] }.getOrNull() ?: CURIOUS
    }
}
