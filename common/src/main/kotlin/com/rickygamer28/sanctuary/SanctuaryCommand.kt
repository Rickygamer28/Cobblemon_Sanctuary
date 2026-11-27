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
import net.minecraft.ChatFormatting
import net.minecraft.server.level.ServerPlayer

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
                // visiting friends: see who's open to visitors, visit one, leave a gift in their basket
                .then(
                    Commands.literal("visit")
                        .executes { ctx -> visit(ctx, null) }
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes { ctx -> visit(ctx, net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")) }),
                )
                .then(Commands.literal("gift").executes { ctx -> gift(ctx) })
                // the valley's guardian (Golden Heart): list your legendaries, or name one
                .then(
                    Commands.literal("guardian")
                        .executes { ctx -> guardian(ctx, null) }
                        .then(Commands.argument("pokemon", net.minecraft.commands.arguments.UuidArgument.uuid()).executes { ctx -> guardian(ctx, net.minecraft.commands.arguments.UuidArgument.getUuid(ctx, "pokemon")) }),
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

    /** /sanctuary visit [<player>]: lists the sanctuaries open to visitors, or goes to one. */
    private fun visit(ctx: CommandContext<CommandSourceStack>, target: ServerPlayer?): Int {
        val player = ctx.source.playerOrException
        val server = player.server
        if (target == null) {
            val open = Sanctuaries.all(server).map { it.second }.filter { it.openToVisitors && it.state == com.rickygamer28.sanctuary.data.SanctuaryState.ACTIVE && server.playerList.getPlayer(it.owner) != null }
            if (open.isEmpty()) { player.sendSystemMessage(Text.info("No sanctuary is open to visitors right now.")); return 1 }
            player.sendSystemMessage(Text.info("Sanctuaries open to visitors (click to visit):"))
            for (s in open) player.sendSystemMessage(Text.button("  ${s.ownerName}'s sanctuary", "/sanctuary visit ${s.ownerName}", "Visit", ChatFormatting.AQUA))
            return 1
        }
        val (_, s) = Sanctuaries.forPlayer(server, target.uuid).firstOrNull { it.second.owner == target.uuid }
            ?: run { player.sendSystemMessage(Text.error("${target.gameProfile.name} doesn't own a sanctuary.")); return 0 }
        if (!s.isMember(player.uuid) && !s.openToVisitors) { player.sendSystemMessage(Text.error("${target.gameProfile.name}'s sanctuary isn't open to visitors.")); return 0 }
        if (s.state != com.rickygamer28.sanctuary.data.SanctuaryState.ACTIVE) { player.sendSystemMessage(Text.error("That sanctuary isn't ready yet.")); return 0 }
        com.rickygamer28.sanctuary.SanctuaryDimension.travel(player, s)
        if (!s.isMember(player.uuid)) {
            player.sendSystemMessage(Text.good("Welcome to ${s.ownerName}'s sanctuary! Look around; /sanctuary gift leaves the item in your hand in their basket, and /sanctuary home takes you back."))
            target.sendSystemMessage(Text.info("${player.gameProfile.name} is visiting your sanctuary."))
            s.logEvent("${player.gameProfile.name} came to visit.")
        }
        return 1
    }

    /** /sanctuary gift: while visiting, leaves the item in your hand in the sanctuary's basket. */
    private fun gift(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.serverLevel()
        val s = Sanctuaries.at(level, player.blockPosition()) ?: run { player.sendSystemMessage(Text.error("Go to a friend's sanctuary first (/sanctuary visit).")); return 0 }
        val stack = player.mainHandItem
        if (stack.isEmpty) { player.sendSystemMessage(Text.error("Hold the item you want to give.")); return 0 }
        val given = com.rickygamer28.sanctuary.life.Gathering.give(s, stack.copy())
        if (given <= 0) { player.sendSystemMessage(Text.error("Their basket has no room for that.")); return 0 }
        val name = stack.hoverName.string
        stack.shrink(given)
        s.logEvent("${player.gameProfile.name} left a gift in the basket: $given × $name.")
        player.sendSystemMessage(Text.good("You left $given × $name in ${s.ownerName}'s basket."))
        for (m in s.members + s.owner) player.server.playerList.getPlayer(m)?.takeIf { it.uuid != player.uuid }?.sendSystemMessage(Text.info("${player.gameProfile.name} left you a gift: $given × $name (in the basket)."))
        Sanctuaries.data(player.server).setDirty()
        return 1
    }

    /** /sanctuary guardian [<pokemon>]: lists your legendaries to choose from, or names one the guardian. */
    private fun guardian(ctx: CommandContext<CommandSourceStack>, id: java.util.UUID?): Int {
        val player = ctx.source.playerOrException
        val (level, s) = Sanctuaries.forPlayer(player.server, player.uuid).firstOrNull { it.second.owner == player.uuid }
            ?: run { player.sendSystemMessage(Text.error("You don't own a sanctuary.")); return 0 }
        if (id == null) com.rickygamer28.sanctuary.life.Guardian.list(player, s) else com.rickygamer28.sanctuary.life.Guardian.choose(player, level, s, id)
        return 1
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
        // what each unlock is doing
        val W = com.rickygamer28.sanctuary.world.TownBuildings
        val built = !s.generationDirty && !com.rickygamer28.sanctuary.world.SanctuaryGenerator.isGrowing(s)
        fun line(t: String) = player.sendSystemMessage(net.minecraft.network.chat.Component.literal("  $t").withStyle(net.minecraft.ChatFormatting.GRAY))
        if (!built) line("The valley is still being built: decorations, lanterns and buildings go up once it's done.")
        if (lv >= 1) line("Basket: holds ${R.basketRoom(s)}× as much of each thing, +${R.basketExtraFinds(s)} find${if (R.basketExtraFinds(s) == 1) "" else "s"} a trip" + (if (R.basketFaster(s)) ", gathers more often" else ""))
        if (lv >= R.DECOR_LEVEL) com.rickygamer28.sanctuary.world.RouteDecor.progress(s).let { (done, total) ->
            line("Route decorations: ${s.decor.size} blocks placed; $done of $total spots done (the rest go up when you're near them)")
        }
        if (lv >= R.LIGHTS_LEVEL) {
            val marks = s.landmarks.filter { (k, v) -> v != com.rickygamer28.sanctuary.world.Landmarks.NONE && !W.isBuilding(k) }
            line("Landmark lanterns: ${marks.keys.count { it in s.litLandmarks }} of ${marks.size} landmarks (the rest when you're near them)")
        }
        if (lv >= R.LOOKS_LEVEL) line("New looks: unlocked (Region tab → a habitat → Look)")
        for ((kind, need) in R.BUILDINGS) line("Town ${kind}: " + when {
            lv < need -> "unlocks at level $need"
            W.built(s, kind) -> "built"
            s.staleLandmarks.containsKey(W.PREFIX + kind) -> "being rebuilt"
            else -> "looking for room in the town (stay near the town)"
        })
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
