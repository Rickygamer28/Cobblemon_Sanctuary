package com.rickygamer28.sanctuary

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.ui.Interactions
import com.rickygamer28.sanctuary.ui.Text
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.UuidArgument

/** /sanctuary … — mostly run by the clickable chat menus. */
object SanctuaryCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("sanctuary")
                .then(
                    Commands.literal("journal")
                        .executes { ctx -> journal(ctx, "home", 0) }
                        .then(
                            Commands.argument("section", StringArgumentType.word())
                                .executes { ctx -> journal(ctx, StringArgumentType.getString(ctx, "section"), 0) }
                                .then(
                                    Commands.argument("page", IntegerArgumentType.integer(0))
                                        .executes { ctx -> journal(ctx, StringArgumentType.getString(ctx, "section"), IntegerArgumentType.getInteger(ctx, "page")) },
                                ),
                        ),
                )
                .then(
                    Commands.literal("act").then(
                        Commands.argument("pokemon", UuidArgument.uuid()).then(
                            Commands.argument("action", StringArgumentType.word())
                                .executes { ctx -> act(ctx, null) }
                                .then(
                                    Commands.argument("arg", StringArgumentType.word())
                                        .executes { ctx -> act(ctx, StringArgumentType.getString(ctx, "arg")) },
                                ),
                        ),
                    ),
                )
                .then(
                    Commands.literal("core").then(
                        Commands.argument("id", UuidArgument.uuid()).then(
                            Commands.argument("action", StringArgumentType.word())
                                .executes { ctx -> core(ctx, null) }
                                .then(
                                    Commands.argument("arg", StringArgumentType.word())
                                        .executes { ctx -> core(ctx, StringArgumentType.getString(ctx, "arg")) },
                                ),
                        ),
                    ),
                )
                .then(
                    Commands.literal("invite").then(
                        Commands.argument("player", EntityArgument.player()).executes { ctx ->
                            val player = ctx.source.playerOrException
                            Sanctuaries.invite(player, EntityArgument.getPlayer(ctx, "player"))
                            1
                        },
                    ),
                )
                .then(Commands.literal("accept").executes { ctx -> Sanctuaries.accept(ctx.source.playerOrException); 1 })
                // for trying the valley out: every Pokémon (and its forms) in your PC, and out again (needs cheats / op)
                .then(
                    Commands.literal("testpc").requires { it.hasPermission(2) }
                        .then(Commands.literal("add").executes { ctx -> testAdd(ctx, true) })
                        .then(Commands.literal("clear").executes { ctx -> testClear(ctx) }),
                )
                // the Valley Heart, for trying its unlocks out (needs cheats / op)
                .then(
                    Commands.literal("heart").requires { it.hasPermission(2) }
                        .executes { ctx -> heart(ctx, null, 0) }
                        .then(Commands.literal("add").then(Commands.argument("points", IntegerArgumentType.integer(1)).executes { ctx -> heart(ctx, "add", IntegerArgumentType.getInteger(ctx, "points")) }))
                        .then(Commands.literal("set").then(Commands.argument("points", IntegerArgumentType.integer(0)).executes { ctx -> heart(ctx, "set", IntegerArgumentType.getInteger(ctx, "points")) }))
                        .then(Commands.literal("level").then(Commands.argument("level", IntegerArgumentType.integer(0, com.rickygamer28.sanctuary.life.Requests.LEVELS.size)).executes { ctx -> heart(ctx, "level", IntegerArgumentType.getInteger(ctx, "level")) })),
                ),
        )
    }

    /** /sanctuary heart [add <points> | set <points> | level <level>]: the Valley Heart of the sanctuary you own (or are in). */
    private fun heart(ctx: CommandContext<CommandSourceStack>, how: String?, n: Int): Int {
        val player = ctx.source.playerOrException
        val mine = Sanctuaries.forPlayer(player.server, player.uuid)
        val (level, s) = mine.firstOrNull { it.second.owner == player.uuid } ?: mine.firstOrNull()
            ?: run { player.sendSystemMessage(Text.error("You don't have a sanctuary yet.")); return 0 }
        val R = com.rickygamer28.sanctuary.life.Requests
        when (how) {
            "add" -> R.setHeart(level, s, s.heart + n)
            "set" -> R.setHeart(level, s, n)
            "level" -> R.setHeart(level, s, if (n == 0) 0 else R.LEVELS[n - 1].points)
        }
        val lv = R.level(s)
        val next = R.LEVELS.getOrNull(lv)
        player.sendSystemMessage(Text.good("Valley Heart: ${s.heart} points, level $lv" + (next?.let { " (next: ${it.title} at ${it.points})" } ?: " (everything unlocked)")))
        return 1
    }

    private const val TEST_TAG = "sanctuary_test"

    /** Forms that only exist in battle (or for a while): not added. */
    private fun battleForm(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("mega") || n.startsWith("primal") || n.startsWith("gmax") || n.contains("gigantamax") || n.contains("eternamax") || n.startsWith("ultra")
    }

    /** Every implemented Pokémon (with [forms], every form too) into the player's PC, marked so they can be taken out again. */
    private fun testAdd(ctx: CommandContext<CommandSourceStack>, forms: Boolean): Int {
        val player = ctx.source.playerOrException
        val pc = com.cobblemon.mod.common.Cobblemon.storage.getPC(player)
        val todo = ArrayList<com.cobblemon.mod.common.pokemon.Pokemon>()
        for (sp in com.cobblemon.mod.common.api.pokemon.PokemonSpecies.implemented) {
            // (Gravelmon's placeholder "billboard" Pokémon without a model are left out)
            if ("not_modeled" in sp.labels) continue
            // (and ones whose models crash the game)
            if (runCatching { SanctuaryConfig.hiddenSpecies.get().any { it.trim().lowercase().substringAfter(':') == sp.resourceIdentifier.path.lowercase() } }.getOrDefault(false)) continue
            val list = listOf(sp.standardForm) + if (forms) sp.forms.filter { it.name != sp.standardForm.name && !battleForm(it.name) && it.aspects.isNotEmpty() } else emptyList()
            for (f in list.distinctBy { it.name }) runCatching {
                val p = sp.create(30)
                if (f.name != sp.standardForm.name) {
                    p.forcedAspects = p.forcedAspects + f.aspects
                    p.updateAspects()
                    p.updateForm()
                }
                p.persistentData.putBoolean(TEST_TAG, true)
                todo += p
            }
        }
        // a bigger PC if they don't fit
        val used = pc.count()
        val boxes = (used + todo.size + 29) / 30 + 1
        if (boxes > pc.boxes.size) runCatching { pc.resize(boxes, false) { } }
        var added = 0
        for (p in todo) if (runCatching { pc.add(p) }.getOrDefault(false)) added++
        ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal("Added $added Pokémon to your PC (${pc.boxes.size} boxes). They're residents of your sanctuary now; /sanctuary testpc clear takes them out again.") }, false)
        return added
    }

    /** Takes the Pokémon [testAdd] put in the player's PC out again (others are left alone). */
    private fun testClear(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val pc = com.cobblemon.mod.common.Cobblemon.storage.getPC(player)
        val test = pc.filter { runCatching { it.persistentData.getBoolean(TEST_TAG) }.getOrDefault(false) }
        for (p in test) {
            runCatching { p.entity?.discard() }
            pc.remove(p)
        }
        ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal("Took ${test.size} test Pokémon out of your PC.") }, false)
        return test.size
    }

    private fun journal(ctx: CommandContext<CommandSourceStack>, section: String, page: Int): Int {
        com.rickygamer28.sanctuary.ui.SanctuaryGuis.openJournal(ctx.source.playerOrException, section)
        return 1
    }

    private fun act(ctx: CommandContext<CommandSourceStack>, arg: String?): Int {
        val player = ctx.source.playerOrException
        Interactions.act(player, UuidArgument.getUuid(ctx, "pokemon"), StringArgumentType.getString(ctx, "action"), arg)
        return 1
    }

    private fun core(ctx: CommandContext<CommandSourceStack>, arg: String?): Int {
        val player = ctx.source.playerOrException
        val (level, s) = Sanctuaries.find(player.server, UuidArgument.getUuid(ctx, "id"))
            ?: run { player.sendSystemMessage(Text.error("That sanctuary no longer exists.")); return 0 }
        when (StringArgumentType.getString(ctx, "action")) {
            "size" -> {
                val size = SanctuarySize.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) } ?: return 0
                Sanctuaries.setSize(s, player, size)
            }
            "confirm" -> Sanctuaries.confirm(s, player)
            "cancel" -> Sanctuaries.cancel(level, s, player)
            "expand" -> Sanctuaries.expand(s, player)
            "leave" -> Sanctuaries.leave(player, s)
            "rebuild" -> if (s.owner == player.uuid) {
                s.rebuild()
                Sanctuaries.data(level).setDirty()
                player.sendSystemMessage(Text.good("Rebuilding the biomes. Stay nearby so the area stays loaded."))
            }
            "journal" -> if (s.isMember(player.uuid)) Sanctuaries.giveJournal(player)
            "travel" -> Sanctuaries.travel(player, s)
            "home" -> com.rickygamer28.sanctuary.SanctuaryDimension.returnHome(player)
            "menu" -> com.rickygamer28.sanctuary.ui.SanctuaryGuis.openCore(player, level, s)
            else -> return 0
        }
        return 1
    }
}
