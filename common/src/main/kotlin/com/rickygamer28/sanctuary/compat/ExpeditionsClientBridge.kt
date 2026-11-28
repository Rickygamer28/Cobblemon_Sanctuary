package com.rickygamer28.sanctuary.compat

import com.cobblemonexpeditions.network.s2c.SyncStatePayload
import com.rickygamer28.sanctuary.client.ClientHomes
import net.minecraft.network.chat.Component

/** The Expedition Board's side of the home-habitat bonus (only loaded when Cobblemon Expeditions is installed). */
internal object ExpeditionsClientBridge {
    /** The Pokémon whose tooltip is being drawn (the board works out its power just before drawing the tooltip). */
    @Volatile var hovered: Any? = null
    /** The expedition selected on the board, as last seen. */
    @Volatile private var selected: SyncStatePayload.AvailableExpeditionInfo? = null

    private fun suits(home: String, exp: SyncStatePayload.AvailableExpeditionInfo) = exp.preferredTypes.any { it.equals(home, ignoreCase = true) }

    fun bonus(poke: Any, exp: Any): Double {
        val p = poke as? SyncStatePayload.PartyPokemonInfo ?: return 1.0
        val e = exp as? SyncStatePayload.AvailableExpeditionInfo ?: return 1.0
        val (home, _) = ClientHomes.homes[p.uuid] ?: return 1.0
        return if (suits(home, e) && ClientHomes.bonus > 0) 1.0 + ClientHomes.bonus / 100.0 else 1.0
    }

    fun select(exp: Any?) { selected = exp as? SyncStatePayload.AvailableExpeditionInfo }

    @Suppress("UNCHECKED_CAST")
    fun addLines(screen: Any?, lines: MutableList<Any?>) {
        val p = hovered as? SyncStatePayload.PartyPokemonInfo ?: return
        hovered = null
        val (home, name) = ClientHomes.homes[p.uuid] ?: return
        val pct = ClientHomes.bonus
        val exp = selected
        val text = when {
            pct <= 0 -> Component.literal("⌂ Lives in the $name").withColor(0x7CC8F0)
            exp == null -> Component.literal("⌂ Lives in the $name").withColor(0x7CC8F0)
                .append(Component.literal(" (+$pct% where it suits)").withColor(0x9AA4B0))
            suits(home, exp) -> Component.literal("⌂ Home ground: +$pct% on ${exp.name}").withColor(0x55FF55)
            else -> Component.literal("⌂ Lives in the $name").withColor(0x7CC8F0)
                .append(Component.literal(" (no bonus here)").withColor(0x9AA4B0))
        }
        // just under the Power line (or at the end if it isn't found)
        val at = lines.indexOfFirst { (it as? Component)?.string?.contains("Power", ignoreCase = true) == true }
        if (at >= 0) lines.add(at + 1, text) else lines.add(text)
    }
}
