package com.rickygamer28.sanctuary.client

import net.minecraft.nbt.CompoundTag
import java.util.UUID

/** Where the player's residents live (from the server), for other mods' screens (Cobblemon Expeditions' board). */
object ClientHomes {
    /** Pokémon → (habitat type, habitat name). */
    @Volatile var homes: Map<UUID, Pair<String, String>> = emptyMap()
    @Volatile var bonus: Int = 0

    fun onHomes(tag: CompoundTag) {
        bonus = tag.getInt("bonus")
        val h = tag.getCompound("h")
        homes = h.allKeys.mapNotNull { k ->
            val id = runCatching { UUID.fromString(k) }.getOrNull() ?: return@mapNotNull null
            val v = h.getString(k).split('|')
            id to (v[0] to v.getOrElse(1) { v[0] })
        }.toMap()
    }
}
