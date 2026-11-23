package com.rickygamer28.sanctuary.ui

import com.rickygamer28.sanctuary.SanctuaryConfig as C
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.ModConfigSpec

/** The Settings tab: the server's main options, changeable in game by the host (single-player) or an op. */
object Settings {
    class Setting(val group: String, val label: String, val value: ModConfigSpec.ConfigValue<*>, val min: Double = 0.0, val max: Double = 1.0, val step: Double = 1.0)

    val ALL: List<Setting> by lazy { listOf(
        Setting("Residents", "Out at once (Small)", C.spawnCapSmall, 10.0, 600.0, 10.0),
        Setting("Residents", "Out at once (Medium)", C.spawnCapMedium, 10.0, 600.0, 10.0),
        Setting("Residents", "Out at once (Large)", C.spawnCapLarge, 10.0, 600.0, 10.0),
        Setting("Residents", "Out at once (Huge)", C.spawnCapHuge, 10.0, 600.0, 10.0),
        Setting("Residents", "Personalities", C.personalities),
        Setting("Residents", "Greetings", C.greetings),
        Setting("Residents", "Friendship speed", C.friendshipRate, 0.0, 5.0, 0.25),
        Setting("Residents", "Life while you're away", C.awayLife),
        Setting("Mega & forms", "Mega Evolution", C.megaEnabled),
        Setting("Mega & forms", "Other form changes", C.formsEnabled),
        Setting("Mega & forms", "Needs the stone held", C.megaNeedsStone),
        Setting("Mega & forms", "Chance in a play-fight", C.megaChance, 0.0, 1.0, 0.05),
        Setting("Families", "Eggs", C.eggsEnabled),
        Setting("Families", "Egg chance", C.eggChance, 0.0, 1.0, 0.05),
        Setting("Families", "Most eggs in the Nest", C.maxEggs, 0.0, 200.0, 5.0),
        Setting("Valley Heart", "Requests", C.requestsEnabled),
        Setting("Valley Heart", "Requests open at once", C.requestMax, 1.0, 10.0, 1.0),
        Setting("Valley Heart", "Minutes between requests", C.requestMinutes, 1.0, 120.0, 1.0),
        Setting("Evolution", "Evolve in the sanctuary", C.evolveInSanctuary),
        Setting("Evolution", "Friendship needed", C.evolveFriendship, 0.0, 255.0, 5.0),
        Setting("Visitors", "Wild visitors", C.visitorsEnabled),
        Setting("Visitors", "Visitors a day", C.visitorsPerDay, 0.05, 10.0, 0.25),
        Setting("Basket", "Gathering Basket", C.basketEnabled),
        Setting("Basket", "Minutes between finds", C.basketMinutes, 1.0, 60.0, 1.0),
        Setting("Basket", "Room for each item", C.basketItemCap, 8.0, 1024.0, 8.0),
        Setting("World", "Habitat atmosphere", C.atmosphere),
        Setting("World", "No wild Pokémon spawning", C.blockWildSpawns),
        Setting("World", "Play-fights give XP", C.playFightXp),
    ) }

    fun allowed(player: ServerPlayer) = player.hasPermissions(2) || player.server.isSingleplayerOwner(player.gameProfile)

    fun data(player: ServerPlayer, t: CompoundTag) {
        t.put("settings", ListTag().also { l ->
            for ((i, st) in ALL.withIndex()) l.add(CompoundTag().also { c ->
                c.putInt("i", i)
                c.putString("group", st.group)
                c.putString("label", st.label)
                when (val v = st.value.get()) {
                    is Boolean -> { c.putString("kind", "bool"); c.putBoolean("on", v) }
                    is Int -> { c.putString("kind", "int"); c.putDouble("v", v.toDouble()) }
                    is Double -> { c.putString("kind", "double"); c.putDouble("v", v) }
                    else -> c.putString("kind", "other")
                }
                c.putDouble("step", st.step)
            })
        })
    }

    /** Changes setting [index]: toggles it, or steps it up or down. */
    @Suppress("UNCHECKED_CAST")
    fun change(player: ServerPlayer, index: Int, up: Boolean) {
        if (!allowed(player)) return
        val st = ALL.getOrNull(index) ?: return
        when (val v = st.value.get()) {
            is Boolean -> (st.value as ModConfigSpec.ConfigValue<Boolean>).set(!v)
            is Int -> (st.value as ModConfigSpec.ConfigValue<Int>).set((v + if (up) st.step.toInt() else -st.step.toInt()).coerceIn(st.min.toInt(), st.max.toInt()))
            is Double -> (st.value as ModConfigSpec.ConfigValue<Double>).set(Math.round(((v + if (up) st.step else -st.step).coerceIn(st.min, st.max)) * 1000) / 1000.0)
        }
        st.value.save()
    }
}
