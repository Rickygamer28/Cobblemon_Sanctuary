package com.rickygamer28.sanctuary

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.ui.Interactions
import com.rickygamer28.sanctuary.ui.Menus
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
                .then(Commands.literal("accept").executes { ctx -> Sanctuaries.accept(ctx.source.playerOrException); 1 }),
        )
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
