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
        val every = (SanctuaryConfig.basketMinutes.get().coerceAtLeast(1) * 60L).let { if (Requests.basketFaster(s)) it * 3 / 4 else it }
        if ((now / 20L + (s.id.hashCode() and 127)) % every != 0L) return
        val roster = rt.roster
        if (roster.isEmpty()) return
        val random = level.random
        val ring = ZoneLayout.ring(s)
        val finds = (roster.size / 40).coerceIn(1, 4) + Requests.basketExtraFinds(s)
        // the berry market: someone always brings a berry
        if (com.rickygamer28.sanctuary.world.TownBuildings.built(s, "market") && "berries" !in s.basketOff) randomFromTag(BERRIES, level)?.let { add(s, it) }
        gift(level, s, rt)
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
            val home = TypeLogic.homeZone(pk, s.residents[pk.uuid], ring, s)
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

    /** What a grateful resident might bring, by its type. */
    private val GIFTS = mapOf(
        "fire" to listOf("blaze_powder", "magma_cream", "fire_charge"), "water" to listOf("prismarine_shard", "prismarine_crystals", "cod"),
        "grass" to listOf("sweet_berries", "apple", "moss_block"), "bug" to listOf("honeycomb", "string", "slime_ball"),
        "electric" to listOf("redstone", "glowstone_dust", "copper_ingot"), "ice" to listOf("snowball", "packed_ice", "blue_ice"),
        "rock" to listOf("flint", "iron_nugget", "amethyst_shard"), "ground" to listOf("clay_ball", "gold_nugget", "brick"),
        "fairy" to listOf("glow_berries", "pink_petals", "amethyst_shard"), "ghost" to listOf("bone", "soul_sand", "phantom_membrane"),
        "dark" to listOf("ink_sac", "coal", "obsidian"), "psychic" to listOf("lapis_lazuli", "amethyst_shard", "ender_pearl"),
        "dragon" to listOf("ender_pearl", "obsidian", "prismarine_crystals"), "poison" to listOf("spider_eye", "slime_ball", "fermented_spider_eye"),
        "steel" to listOf("iron_nugget", "iron_ingot", "chain"), "fighting" to listOf("leather", "iron_nugget", "bread"),
        "flying" to listOf("feather", "feather", "arrow"), "normal" to listOf("wheat", "egg", "bread"),
    )

    /** Now and then a resident you've helped leaves a gift in the basket. */
    private fun gift(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime) {
        val chance = if (Requests.level(s) >= Requests.GIFTS_LEVEL) 0.5 else 0.25
        if (level.random.nextDouble() >= chance) return
        val helpers = rt.roster.filter { (s.residents[it.pokemon.uuid]?.helped ?: 0) > 0 }
        val e = helpers.randomOrNull() ?: return
        val type = runCatching { TypeLogic.currentTypes(e.pokemon).firstOrNull() }.getOrNull() ?: "normal"
        val id = (GIFTS[type] ?: GIFTS.getValue("normal")).let { it[level.random.nextInt(it.size)] }
        // the Golden Heart: now and then something rare
        val rare = Requests.level(s) >= Requests.GOLDEN_LEVEL && level.random.nextInt(8) == 0
        val item = if (rare) BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("cobblemon", listOf("rare_candy", "exp_candy_l", "exp_candy_m")[level.random.nextInt(3)]))
            else BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id))
        if (item == Items.AIR) return
        val stack = ItemStack(item, 1 + level.random.nextInt(2))
        if (add(s, stack)) s.logEvent("${e.pokemon.getDisplayName(false).string} left a gift in the basket: ${stack.hoverName.string}.")
    }

    fun itemCount(s: Sanctuary): Int = (0 until s.basket.containerSize).sumOf { s.basket.getItem(it).count }

    /** The basket's slots: what the Valley Heart gives it, or more if something's already in the slots further on. */
    fun slots(s: Sanctuary): Int {
        val last = (s.basket.containerSize - 1 downTo 0).firstOrNull { !s.basket.getItem(it).isEmpty } ?: -1
        val need = maxOf(Requests.basketSlots(s), last + 1)
        return listOf(27, 54, 81, 108).first { it >= need.coerceAtMost(108) }
    }

    /** How the basket's slots are laid out: 9 across, or 12 across for the biggest (9 rows of 12). */
    fun layout(s: Sanctuary): Pair<Int, Int> = slots(s).let { n -> if (n > 81) (n / 12) to 12 else (n / 9) to 9 }

    private fun isFull(s: Sanctuary) = (0 until slots(s)).none { s.basket.getItem(it).isEmpty }

    /** How many of [item] are already in the basket. */
    private fun countOf(s: Sanctuary, item: Item) = (0 until s.basket.containerSize).sumOf { val st = s.basket.getItem(it); if (st.`is`(item)) st.count else 0 }

    /**
     * Puts a find in the basket, unless there's no basket, it's full, or there's already plenty of that item
     * (so the basket fills with a bit of everything instead of piles of the same thing). Returns true if it went in.
     */
    fun add(s: Sanctuary, stack: ItemStack): Boolean {
        if (stack.isEmpty || s.basketPos == null || !SanctuaryConfig.basketEnabled.get()) return false
        val room = (SanctuaryConfig.basketItemCap.get() * Requests.basketRoom(s)).toInt() - countOf(s, stack.item)
        if (room <= 0) return false
        var left = stack.count.coerceAtMost(room)
        val start = left
        val slots = slots(s)
        // onto stacks of the same thing first, then into empty slots (only the basket's open rows)
        for (i in 0 until slots) {
            if (left <= 0) break
            val cur = s.basket.getItem(i)
            if (!cur.isEmpty && ItemStack.isSameItemSameComponents(cur, stack) && cur.count < cur.maxStackSize) {
                val n = minOf(left, cur.maxStackSize - cur.count); cur.grow(n); left -= n
            }
        }
        for (i in 0 until slots) {
            if (left <= 0) break
            if (s.basket.getItem(i).isEmpty) { val n = minOf(left, stack.maxStackSize); s.basket.setItem(i, stack.copyWithCount(n)); left -= n }
        }
        if (left < start) s.basket.setChanged()
        return left < start
    }
}
