package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.evolution.variants.LevelUpEvolution
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID

/**
 * Residents grow up: one that's fond of its trainer and meets its evolution's conditions (a level, friendship,
 * the time of day…) evolves on its own in the sanctuary, with a little celebration. Only evolutions that happen
 * by levelling up; stones, trades and held items are left to the trainer. An Everstone, or its trainer's
 * "Stay as it is", stops it.
 */
object Evolving {
    private val cursor = HashMap<UUID, Int>()

    /** Called every second for every sanctuary. Looks over a few residents every half minute; at most one evolves. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if (!SanctuaryConfig.evolveInSanctuary.get()) return
        if ((now / 20L + (s.id.hashCode() and 31)) % 30L != 0L) return
        val roster = rt.roster
        if (roster.isEmpty()) return
        var i = cursor[s.id] ?: 0
        repeat(minOf(60, roster.size)) {
            if (i >= roster.size) i = 0
            val pk = roster[i++].pokemon
            val evo = ready(s, pk)
            if (evo != null && evolve(level, s, rt, pk, evo)) { cursor[s.id] = i; return }
            // a friendship trade: very fond of its trainer, with a best friend here to swap with
            val trade = friendTrade(s, rt, pk)
            if (trade != null && evolve(level, s, rt, pk, trade.first, "with its best friend ${trade.second}'s help")) { cursor[s.id] = i; return }
        }
        cursor[s.id] = i
    }

    private fun everstone(pk: Pokemon) = runCatching { pk.heldItem().item.toString().endsWith("everstone") }.getOrDefault(false)

    /** The level-up evolutions this Pokémon has. */
    private fun levelUps(pk: Pokemon): List<Evolution> = runCatching { pk.evolutions.filterIsInstance<LevelUpEvolution>() }.getOrDefault(emptyList())

    /** The evolution that would happen now, if any. */
    fun ready(s: Sanctuary, pk: Pokemon): Evolution? {
        if (s.residents[pk.uuid]?.noEvolve == true || everstone(pk) || Residents.hidden(pk)) return null
        if (pk.friendship < SanctuaryConfig.evolveFriendship.get()) return null
        return levelUps(pk).firstOrNull { e -> runCatching { e.test(pk) }.getOrDefault(false) }
    }

    /** The level an evolution needs (read from its level requirement, wherever this Cobblemon keeps that class). */
    private fun minLevel(e: Evolution): Int? = runCatching {
        e.requirements.firstOrNull { it.javaClass.simpleName == "LevelRequirement" }?.let { req ->
            val f = req.javaClass.declaredFields.firstOrNull { it.name == "minLevel" } ?: return@let null
            f.isAccessible = true
            (f.get(req) as? Number)?.toInt()
        }
    }.getOrNull()

    private fun resultName(e: Evolution): String =
        runCatching { e.result.species }.getOrNull()?.substringAfter(':')?.replaceFirstChar { it.uppercase() } ?: "its next form"

    /** For the resident's page: what it will grow into, and when. */
    fun status(s: Sanctuary, pk: Pokemon): String? {
        val evos = levelUps(pk)
        if (evos.isEmpty()) return if (runCatching { pk.evolutions.any() }.getOrDefault(false)) "Evolves another way (a stone, a trade…)" else null
        if (s.residents[pk.uuid]?.noEvolve == true) return "Stays as it is (its trainer's choice)"
        if (everstone(pk)) return "Holding an Everstone: it won't evolve"
        if (!SanctuaryConfig.evolveInSanctuary.get()) return "Doesn't evolve in the sanctuary on this server"
        val e = evos.first()
        val name = resultName(e)
        val lv: Int? = minLevel(e)
        val need = SanctuaryConfig.evolveFriendship.get()
        return when {
            evos.any { runCatching { it.test(pk) }.getOrDefault(false) } && pk.friendship >= need -> "About to evolve into $name!"
            lv != null && pk.level < lv -> "Evolves into $name at Lv.$lv"
            pk.friendship < need -> "Will evolve into $name once fonder of its trainer"
            else -> "Evolves into $name when the time is right"
        }
    }

    // ------------------------------------------------------------------ items, trades and the picker

    private fun kind(e: Evolution) = e.javaClass.simpleName

    /** The item (or item tag) an item evolution wants, read from its condition whatever this Cobblemon calls it. */
    private fun condition(e: Evolution): Any? = runCatching { e.javaClass.getMethod("getRequiredContext").invoke(e) }.getOrNull()

    private fun fields(o: Any): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) { out += c.declaredFields; c = c.superclass }
        return out
    }

    /** Whether [stack] is what the item evolution [e] needs. */
    fun itemFits(e: Evolution, stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty) return false
        val cond = condition(e) ?: return false
        cond.javaClass.methods.firstOrNull { it.name == "fits" && it.parameterCount == 2 }?.let { m ->
            runCatching { return m.invoke(cond, stack.item, net.minecraft.core.registries.BuiltInRegistries.ITEM) as Boolean }
        }
        val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)
        for (f in fields(cond)) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val v = runCatching { f.isAccessible = true; f.get(cond) }.getOrNull()
            when (v) {
                is net.minecraft.resources.ResourceLocation -> if (v == id) return true
                is net.minecraft.tags.TagKey<*> -> if (runCatching { @Suppress("UNCHECKED_CAST") stack.`is`(v as net.minecraft.tags.TagKey<net.minecraft.world.item.Item>) }.getOrDefault(false)) return true
            }
        }
        return false
    }

    /** The item's name, for the page ("a Fire Stone"). */
    private fun itemName(e: Evolution): String {
        val cond = condition(e) ?: return "an item"
        for (f in fields(cond)) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val v = runCatching { f.isAccessible = true; f.get(cond) }.getOrNull()
            if (v is net.minecraft.resources.ResourceLocation) {
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(v)
                if (item != net.minecraft.world.item.Items.AIR) return "a " + net.minecraft.world.item.ItemStack(item).hoverName.string
            }
            if (v is net.minecraft.tags.TagKey<*>) return "a " + v.location.path.replace('_', ' ').split('/').last()
        }
        return "an item"
    }

    /** For a trade evolution: the Pokémon it must be traded for, if it needs a particular one (Karrablast for Shelmet). */
    private fun tradePartner(e: Evolution): String? {
        val props = condition(e) ?: return null
        val species = runCatching { props.javaClass.getMethod("getSpecies").invoke(props) as? String }.getOrNull()
        return species?.takeIf { it.isNotBlank() }?.substringAfter(':')?.replaceFirstChar { it.uppercase() }
    }

    /** Another member of the sanctuary near [player], to help with a trade. */
    private fun tradeHelper(level: ServerLevel, s: Sanctuary, player: ServerPlayer): ServerPlayer? =
        level.players().filterIsInstance<ServerPlayer>().firstOrNull { it.uuid != player.uuid && s.isMember(it.uuid) && it.distanceTo(player) < 32 }

    /** A friendship trade a resident could do now, and its best friend's name. */
    private fun friendTrade(s: Sanctuary, rt: SanctuaryRuntime, pk: Pokemon): Pair<Evolution, String>? {
        if (s.residents[pk.uuid]?.noEvolve == true || everstone(pk) || Residents.hidden(pk) || pk.friendship < 220) return null
        val e = runCatching { pk.evolutions.firstOrNull { kind(it).contains("Trade") && tradePartner(it) == null && runCatching { it.test(pk) }.getOrDefault(false) } }.getOrNull() ?: return null
        val me = pk.uuid.toString()
        val best = s.relationships.entries.filter { it.value >= 80 && it.key.contains(me) }.maxByOrNull { it.value } ?: return null
        val other = best.key.split('|').firstOrNull { it != me }?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val friend = (rt.live[other]?.pokemon ?: Residents.findRoster(rt, other)?.pokemon) ?: return null
        return e to friend.getDisplayName(false).string
    }

    /** Every way this resident can evolve, for the picker on its page. Each: name, how, and the button (action, label) if it can now. */
    fun options(level: ServerLevel, s: Sanctuary, pk: Pokemon, player: ServerPlayer, owner: Boolean): List<net.minecraft.nbt.CompoundTag> {
        val evos = runCatching { pk.evolutions.toList() }.getOrDefault(emptyList())
        val blocked = s.residents[pk.uuid]?.noEvolve == true || everstone(pk)
        return evos.mapIndexed { i, e ->
            val name = resultName(e)
            val k = kind(e)
            val t = net.minecraft.nbt.CompoundTag()
            t.putString("name", name)
            val ok = runCatching { e.test(pk) }.getOrDefault(false)
            when {
                k.contains("LevelUp") -> {
                    val lv = minLevel(e)
                    t.putString("how", if (lv != null && pk.level < lv) "Grows into it at Lv.$lv" else if (!ok) "When the time is right (friendship, time of day…)" else "Ready: it'll evolve on its own soon")
                }
                k.contains("ItemInteraction") -> {
                    val item = itemName(e)
                    val has = (0 until player.inventory.containerSize).any { itemFits(e, player.inventory.getItem(it)) }
                    t.putString("how", "Use $item" + if (!has) " (you don't have one)" else if (!ok) " (not yet: another condition)" else "")
                    if (owner && has && ok && !blocked) { t.putString("act", "evoitem"); t.putString("arg", "$i"); t.putString("btn", "Use") }
                }
                k.contains("Trade") -> {
                    val partner = tradePartner(e)
                    when {
                        partner != null -> t.putString("how", "Trade it for a $partner")
                        tradeHelper(level, s, player) != null && ok -> { t.putString("how", "Trade with a friend"); if (owner && !blocked) { t.putString("act", "evotrade"); t.putString("arg", "$i"); t.putString("btn", "Trade") } }
                        else -> t.putString("how", "Trade with a friend (another member nearby), or on its own with a best friend once very fond of you" + if (!ok) " (and another condition)" else "")
                    }
                }
                else -> t.putString("how", "Another way")
            }
            t
        }
    }

    /** The trainer used an item or traded from the resident's page. */
    fun byHand(level: ServerLevel, s: Sanctuary, pk: Pokemon, player: ServerPlayer, action: String, index: Int): String? {
        if (s.residents[pk.uuid]?.noEvolve == true) return "It's set to stay as it is."
        if (everstone(pk)) return "It's holding an Everstone."
        val e = runCatching { pk.evolutions.toList()[index] }.getOrNull() ?: return "It can't evolve that way."
        if (!runCatching { e.test(pk) }.getOrDefault(false)) return "It isn't ready to evolve that way yet."
        val rt = Residents.runtime(s)
        return when (action) {
            "evoitem" -> {
                val slot = (0 until player.inventory.containerSize).firstOrNull { itemFits(e, player.inventory.getItem(it)) } ?: return "You need ${itemName(e)}."
                val used = player.inventory.getItem(slot).hoverName.string
                if (!evolve(level, s, rt, pk, e, "with a $used")) return "It couldn't evolve."
                if (!player.isCreative) player.inventory.getItem(slot).shrink(1)
                null
            }
            "evotrade" -> {
                val friend = tradeHelper(level, s, player) ?: return "Another member of the sanctuary needs to be with you."
                if (!evolve(level, s, rt, pk, e, "thanks to a trade with ${friend.gameProfile.name}")) return "It couldn't evolve."
                friend.displayClientMessage(Component.literal("You helped ${player.gameProfile.name}'s Pokémon evolve!").withStyle(ChatFormatting.AQUA), false)
                null
            }
            else -> "It can't evolve that way."
        }
    }

    private fun evolve(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, pk: Pokemon, evo: Evolution, how: String? = null): Boolean {
        val before = pk.getDisplayName(false).string
        val beforeSpecies = pk.species.name
        val live = rt.live[pk.uuid]
        live?.entity?.let { e ->
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, e.x, e.y + e.bbHeight / 2, e.z, 40, 0.4, 0.6, 0.4, 0.4)
            level.playSound(null, e.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8f, 1.2f)
        }
        val ok = runCatching { evo.forceEvolve(pk); true }.getOrElse {
            com.rickygamer28.sanctuary.CobblemonSanctuary.LOGGER.warn("Couldn't evolve $beforeSpecies in the sanctuary", it); false
        }
        if (!ok || pk.species.name == beforeSpecies) return false
        val after = pk.species.translatedName.string
        val zone = runCatching { TypeLogic.homeZone(pk, s.residents[pk.uuid], ZoneLayout.ring(s), s) }.getOrNull()
        s.logEvent("$before evolved into $after in the ${Residents.zoneName(zone)}${how?.let { " $it" } ?: ""}!")
        s.stat("evolutions")
        Dex.evolved(s, pk)
        live?.entity?.let { e -> Effects.happy(e); Effects.hearts(e) }
        for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.displayClientMessage(
            Component.literal("✦ $before evolved into $after in your sanctuary!").withStyle(ChatFormatting.AQUA), false)
        rt.rosterDirty = true
        Sanctuaries.data(level).setDirty()
        return true
    }
}
