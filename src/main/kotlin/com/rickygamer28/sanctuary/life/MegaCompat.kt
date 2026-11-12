package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.neoforged.fml.ModList

/**
 * Mega Evolution during play-fights (needs Cobblemon: Mega Showdown).
 * Not wired up yet in this version: Mega Showdown's API still has to be hooked in.
 */
object MegaCompat {
    val megaShowdownLoaded: Boolean by lazy { ModList.get().isLoaded("mega_showdown") }

    @Suppress("UNUSED_PARAMETER")
    fun maybeMega(entity: PokemonEntity) {
        if (!megaShowdownLoaded) return
        // TODO: hook Mega Showdown so residents holding their Mega Stone can Mega Evolve (35%, 30 s – 5 min).
    }
}
