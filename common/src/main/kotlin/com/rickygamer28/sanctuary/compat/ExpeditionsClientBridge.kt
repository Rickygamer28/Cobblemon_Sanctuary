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

    /** The expedition's name: Expeditions keeps it as a translation key ("expedition.cobblemon_expeditions.forest_forage"). */
    private fun nameOf(exp: SyncStatePayload.AvailableExpeditionInfo): String {
        val raw = exp.name
        val shown = Component.translatable(raw).string
        if (shown != raw || ' ' in raw) return shown
        return raw.substringAfterLast('.').split('_', '-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun suits(home: String, exp: SyncStatePayload.AvailableExpeditionInfo) = exp.preferredTypes.any { it.equals(home, ignoreCase = true) }

    fun bonus(poke: Any, exp: Any): Double {
        val p = poke as? SyncStatePayload.PartyPokemonInfo ?: return 1.0
        val e = exp as? SyncStatePayload.AvailableExpeditionInfo ?: return 1.0
        val (home, _) = ClientHomes.homes[p.uuid] ?: return 1.0
        return if (suits(home, e) && ClientHomes.bonus > 0) 1.0 + ClientHomes.bonus / 100.0 else 1.0
    }

    fun select(exp: Any?) { selected = exp as? SyncStatePayload.AvailableExpeditionInfo }

    /** The expedition picked on the board, asked from the board itself (in case it wasn't passed along). */
    private var selectedMethod: java.lang.reflect.Method? = null
    private fun boardSelection(screen: Any?): SyncStatePayload.AvailableExpeditionInfo? {
        if (screen == null) return null
        val m = selectedMethod ?: runCatching { screen.javaClass.getDeclaredMethod("currentlySelectedExpedition").also { it.isAccessible = true } }.getOrNull()?.also { selectedMethod = it }
        return runCatching { m?.invoke(screen) as? SyncStatePayload.AvailableExpeditionInfo }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun addLines(screen: Any?, lines: MutableList<Any?>) {
        val p = hovered as? SyncStatePayload.PartyPokemonInfo ?: return
        hovered = null
        val (home, name) = ClientHomes.homes[p.uuid] ?: return
        val pct = ClientHomes.bonus
        val exp = boardSelection(screen) ?: selected
        val text = when {
            // no expedition picked (or no bonus on this server): just where it lives
            exp == null || pct <= 0 -> Component.literal("⌂ Lives in the $name").withColor(0x7CC8F0)
            suits(home, exp) -> Component.literal("⌂ Home ground bonus: +$pct% on ${nameOf(exp)}").withColor(0x55FF55)
            else -> Component.literal("⌂ No home bonus on ${nameOf(exp)}").withColor(0xFF7070)
                .append(Component.literal(" (lives in the $name)").withColor(0x9AA4B0))
        }
        // just under the Power line (or at the end if it isn't found)
        val at = lines.indexOfFirst { (it as? Component)?.string?.contains("Power", ignoreCase = true) == true }
        if (at >= 0) lines.add(at + 1, text) else lines.add(text)
    }
}
