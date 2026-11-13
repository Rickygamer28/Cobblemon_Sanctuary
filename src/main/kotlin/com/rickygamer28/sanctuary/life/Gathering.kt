package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Residents bring things to the Gathering Basket: what they'd normally drop in Cobblemon (feathers, scales,
 * berries…), plus berries and apricorns they find in the lusher biomes. It happens while you're away too.
 */
object Gathering {
    private val BERRIES: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "berries"))
    private val APRICORNS: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "apricorns"))
    private val LUSH = setOf("grass", "bug", "fairy", "normal", "water", "poison")

    private fun randomFromTag(tag: TagKey<Item>, level: ServerLevel): ItemStack? {
        val items = BuiltInRegistries.ITEM.getTag(tag).map { it.toList() }.orElse(emptyList())
        if (items.isEmpty()) return null
        return ItemStack(items[level.random.nextInt(items.size)].value())
    }

    /** Called every second for every sanctuary, with or without players. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if (!SanctuaryConfig.basketEnabled.get() || s.basketPos == null) return
        val every = SanctuaryConfig.basketMinutes.get().coerceAtLeast(1) * 60L
        if ((now / 20L + (s.id.hashCode() and 127)) % every != 0L) return
        val roster = rt.roster
        if (roster.isEmpty()) return
        val random = level.random
        val ring = ZoneLayout.ring()
        val finds = (roster.size / 40).coerceIn(1, 4)
        var reported = false
        repeat(finds) {
            val e = roster[random.nextInt(roster.size)]
            val pk = e.pokemon
            val found = ArrayList<ItemStack>()
            // one thing this Pokémon would drop in Cobblemon, a few at most
            if ("drops" !in s.basketOff) runCatching {
                val drops = pk.form.drops.getDrops(pokemon = pk).filterIsInstance<com.cobblemon.mod.common.api.drop.ItemDropEntry>()
                val drop = drops.randomOrNull() ?: return@runCatching
                val item = BuiltInRegistries.ITEM.get(drop.item)
                if (item == Items.AIR) return@runCatching
                val range = drop.quantityRange
                val count = if (range != null) range.first + random.nextInt((range.last - range.first + 1).coerceAtLeast(1)) else drop.quantity
                if (count > 0) found += ItemStack(item, count.coerceAtMost(3))
            }
            // something from its biome
            val home = TypeLogic.homeZone(pk, s.residents[pk.uuid], ring)
            if (home in LUSH && random.nextDouble() < 0.3) {
                if (random.nextDouble() < 0.7) { if ("berries" !in s.basketOff) randomFromTag(BERRIES, level)?.let { found += it } }
                else if ("apricorns" !in s.basketOff) randomFromTag(APRICORNS, level)?.let { found += it }
            }
            val kept = found.filter { add(s, it) }
            if (kept.isEmpty() && found.isNotEmpty() && isFull(s)) return@repeat
            if (!reported && kept.isNotEmpty() && random.nextDouble() < 0.25) {
                reported = true
                s.logEvent("${pk.getDisplayName(false).string} brought ${kept.first().hoverName.string} to the basket.")
            }
        }
        Sanctuaries.data(level).setDirty()
    }

    fun itemCount(s: Sanctuary): Int = (0 until s.basket.containerSize).sumOf { s.basket.getItem(it).count }

    private fun isFull(s: Sanctuary) = (0 until s.basket.containerSize).none { s.basket.getItem(it).isEmpty }

    /** How many of [item] are already in the basket. */
    private fun countOf(s: Sanctuary, item: Item) = (0 until s.basket.containerSize).sumOf { val st = s.basket.getItem(it); if (st.`is`(item)) st.count else 0 }

    /**
     * Puts a find in the basket, unless there's no basket, it's full, or there's already plenty of that item
     * (so the basket fills with a bit of everything instead of piles of the same thing). Returns true if it went in.
     */
    fun add(s: Sanctuary, stack: ItemStack): Boolean {
        if (stack.isEmpty || s.basketPos == null || !SanctuaryConfig.basketEnabled.get()) return false
        val room = SanctuaryConfig.basketItemCap.get() - countOf(s, stack.item)
        if (room <= 0) return false
        val put = stack.copyWithCount(stack.count.coerceAtMost(room))
        val left = s.basket.addItem(put)
        return left.count < put.count
    }
}
