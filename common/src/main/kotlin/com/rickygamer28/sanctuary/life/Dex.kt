package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.data.Sanctuary

/** The Sanctuary Dex: every species that has ever lived in the sanctuary, and which were born or evolved there. */
object Dex {
    fun id(pk: Pokemon): String = pk.species.resourceIdentifier.toString()

    /** Called every second: once a minute, everyone living here is noted. */
    fun tick(s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if ((now / 20L + (s.id.hashCode() and 63)) % 60L != 0L) return
        for (e in rt.roster) s.dex += id(e.pokemon)
    }

    fun hatched(s: Sanctuary, pk: Pokemon) { s.dex += id(pk); s.dexHatched += id(pk) }
    fun evolved(s: Sanctuary, pk: Pokemon) { s.dex += id(pk); s.dexEvolved += id(pk) }
}
