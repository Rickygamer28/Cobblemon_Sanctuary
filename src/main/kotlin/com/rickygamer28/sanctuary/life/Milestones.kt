package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuarySize
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The Journal's goals: things the sanctuary grows into (residents, awake biomes, families, friendships, jobs…).
 * Each one reached has a small reward that any member can collect from the Goals page.
 */
object Milestones {
    /** A reward: an item id (or "#tag" for a random item of a tag) and how many. */
    class Reward(val id: String, val count: Int)

    class Goal(val id: String, val title: String, val desc: String, private val target: Int, val rewards: List<Reward>, val value: (Sanctuary, SanctuaryRuntime) -> Int) {
        /** How much is needed ("every biome" depends on how many types there are). */
        val need: Int get() = if (target < 0) com.rickygamer28.sanctuary.world.ZoneLayout.ring().size + 1 else target
    }

    private fun r(id: String, n: Int = 1) = Reward(id, n)

    val GOALS: List<Goal> = listOf(
        Goal("residents_10", "A Little Family", "Have 10 Pokémon living in the sanctuary.", 10, listOf(r("cobblemon:exp_candy_s", 3), r("#cobblemon:apricorns", 3))) { s, _ -> s.residentCount },
        Goal("residents_50", "A Busy Valley", "Have 50 Pokémon living in the sanctuary.", 50, listOf(r("cobblemon:exp_candy_m", 3), r("cobblemon:rare_candy", 1))) { s, _ -> s.residentCount },
        Goal("residents_150", "A Thriving Sanctuary", "Have 150 Pokémon living in the sanctuary.", 150, listOf(r("cobblemon:exp_candy_l", 3), r("cobblemon:rare_candy", 3))) { s, _ -> s.residentCount },
        Goal("biomes_5", "Waking Up", "Wake up 5 biomes.", 5, listOf(r("#cobblemon:mints", 2))) { s, _ -> s.unlocked.size },
        Goal("biomes_all", "Every Corner Alive", "Wake up every biome of the valley.", -1, listOf(r("#cobblemon:mints", 4), r("cobblemon:ability_capsule", 1))) { s, _ -> s.unlocked.size },
        Goal("grow_medium", "Room to Roam", "Grow the sanctuary to Medium.", 1, listOf(r("cobblemon:sitrus_berry", 4))) { s, _ -> if (s.size.ordinal >= SanctuarySize.MEDIUM.ordinal) 1 else 0 },
        Goal("grow_large", "Wide Open Spaces", "Grow the sanctuary to Large.", 1, listOf(r("cobblemon:soothe_bell", 1))) { s, _ -> if (s.size.ordinal >= SanctuarySize.LARGE.ordinal) 1 else 0 },
        Goal("friends_10", "Best of Friends", "Have 10 pairs of residents become friends.", 10, listOf(r("cobblemon:pecha_berry", 4), r("cobblemon:oran_berry", 4))) { s, _ -> s.relationships.values.count { it >= 40 } },
        Goal("friends_50", "One Big Family", "Have 50 pairs of residents become friends.", 50, listOf(r("cobblemon:soothe_bell", 1))) { s, _ -> s.relationships.values.count { it >= 40 } },
        Goal("egg_1", "First Hatchling", "Hatch your first egg from the sanctuary.", 1, listOf(r("cobblemon:oval_stone", 1), r("cobblemon:exp_candy_s", 2))) { s, _ -> s.families.size },
        Goal("egg_10", "A Full Nest", "Hatch 10 eggs from the sanctuary.", 10, listOf(r("cobblemon:ability_capsule", 1))) { s, _ -> s.families.size },
        Goal("fond_1", "Really Fond of You", "Have a resident grow really fond of its trainer (friendship 160).", 1, listOf(r("cobblemon:soothe_bell", 1))) { _, rt -> rt.roster.count { it.pokemon.friendship >= Growth.FOND } },
        Goal("fond_10", "Loved Trainer", "Have 10 residents grow really fond of their trainers.", 10, listOf(r("cobblemon:rare_candy", 2), r("#cobblemon:mints", 2))) { _, rt -> rt.roster.count { it.pokemon.friendship >= Growth.FOND } },
        Goal("greet_10", "Welcome Home", "Get greeted by your residents 10 times.", 10, listOf(r("minecraft:cake", 1), r("cobblemon:oran_berry", 6))) { s, _ -> s.stats["greetings"] ?: 0 },
        Goal("jobs_50", "Many Paws Make Light Work", "Residents finish 50 jobs around the valley.", 50, listOf(r("cobblemon:exp_candy_m", 4))) { s, _ -> s.stats["jobs"] ?: 0 },
        Goal("jobs_500", "Hard Workers", "Residents finish 500 jobs around the valley.", 500, listOf(r("cobblemon:ability_capsule", 1), r("cobblemon:exp_candy_l", 4))) { s, _ -> s.stats["jobs"] ?: 0 },
        Goal("visits_100", "Favourite Places", "Residents visit the valley's spots 100 times.", 100, listOf(r("#cobblemon:apricorns", 6))) { s, _ -> s.stats["visits"] ?: 0 },
        Goal("decor_3", "Home Sweet Home", "Place 3 decorations (beds, balls, troughs) in the valley.", 3, listOf(r("cobblemon:exp_candy_s", 4), r("minecraft:white_wool", 6))) { s, _ -> s.props.values.count { it.startsWith("player_") } },
        Goal("meals_25", "Well Fed", "Residents eat 25 meals from feeding troughs.", 25, listOf(r("cobblemon:leftovers", 1))) { s, _ -> s.stats["meals"] ?: 0 },
        Goal("mega_1", "Mega Moment", "See a resident Mega Evolve during a play-fight.", 1, listOf(r("cobblemon:exp_candy_l", 2))) { s, _ -> s.stats["megas"] ?: 0 },
        Goal("fights_100", "Sparring Partners", "Residents play-fight 100 times.", 100, listOf(r("cobblemon:muscle_band", 1), r("cobblemon:wise_glasses", 1))) { s, _ -> s.stats["playfights"] ?: 0 },
    )

    fun value(g: Goal, s: Sanctuary, rt: SanctuaryRuntime): Int = runCatching { g.value(s, rt) }.getOrDefault(0)

    /** Checks the goals now and then and announces the ones just reached. Called every second. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if ((now / 20L + (s.id.hashCode() and 31)) % 30L != 0L) return
        for (g in GOALS) {
            if (g.id in s.reached || value(g, s, rt) < g.need) continue
            s.reached += g.id
            s.logEvent("Goal reached: ${g.title}! Collect the reward on the Journal's Goals page.")
            Sanctuaries.data(level).setDirty()
            for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Sanctuary goal reached: ${g.title}").withStyle(net.minecraft.ChatFormatting.GOLD), true)
        }
    }

    fun stacks(level: ServerLevel, g: Goal): List<ItemStack> = g.rewards.mapNotNull { rw ->
        if (rw.id.startsWith("#")) {
            val tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(rw.id.removePrefix("#")))
            val items = BuiltInRegistries.ITEM.getTag(tag).map { it.toList() }.orElse(emptyList())
            if (items.isEmpty()) null else ItemStack(items[level.random.nextInt(items.size)].value(), rw.count)
        } else {
            val item: Item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rw.id))
            if (item == Items.AIR) null else ItemStack(item, rw.count)
        }
    }

    /** Hands a reached goal's reward to [player] (once per sanctuary). Returns a message for the player. */
    fun claim(level: ServerLevel, s: Sanctuary, player: ServerPlayer, id: String): String {
        val g = GOALS.firstOrNull { it.id == id } ?: return "That goal doesn't exist."
        if (id in s.claimed) return "That reward was already collected."
        if (id !in s.reached) return "That goal isn't reached yet."
        s.claimed += id
        // a tag reward picks a random item each time, so hand out one by one
        val given = ArrayList<String>()
        for (rw in g.rewards) {
            if (rw.id.startsWith("#")) repeat(rw.count) { stacks(level, Goal("", "", "", 0, listOf(Reward(rw.id, 1))) { _, _ -> 0 }).forEach { give(player, it, given) } }
            else stacks(level, Goal("", "", "", 0, listOf(rw)) { _, _ -> 0 }).forEach { give(player, it, given) }
        }
        Sanctuaries.data(level).setDirty()
        s.logEvent("${player.gameProfile.name} collected the reward for ${g.title}.")
        return if (given.isEmpty()) "Collected!" else "Collected: ${given.joinToString(", ")}"
    }

    private fun give(player: ServerPlayer, stack: ItemStack, given: MutableList<String>) {
        given += "${stack.count}× ${stack.hoverName.string}"
        if (!player.inventory.add(stack)) player.drop(stack, false)
    }
}
