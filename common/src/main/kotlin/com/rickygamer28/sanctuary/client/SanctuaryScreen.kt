package com.rickygamer28.sanctuary.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.registry.ModContent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The Sanctuary screen: opened by the Core and the Journal. Everything is drawn here, no chest menus. */
class SanctuaryScreen(initial: CompoundTag) : Screen(Component.literal("Sanctuary")) {
    // ------------------------------------------------------------------ colours
    private companion object {
        const val BACKDROP = 0xB0080C12.toInt()
        const val PANEL = 0xF2182029.toInt()
        const val PANEL_EDGE = 0xFF2E3B4A.toInt()
        const val SIDEBAR = 0xF2121920.toInt()
        const val CARD = 0xFF212C38.toInt()
        const val CARD_HOVER = 0xFF2B394A.toInt()
        const val TRACK = 0xFF0D1319.toInt()
        const val TEXT = 0xFFEAF0F6.toInt()
        const val SUB = 0xFF9AAABB.toInt()
        const val DIM = 0xFF5C6878.toInt()
        const val ACCENT = 0xFF4FC3A1.toInt()
        const val GOOD = 0xFF62D184.toInt()
        const val BAD = 0xFFE36565.toInt()
        const val GOLD = 0xFFF2C94C.toInt()
        const val PURPLE = 0xFFA77BF3.toInt()
        const val BLUE = 0xFF5EA8F0.toInt()
        const val HEADER_H = 26
        const val SIDE_W = 98
        val STAT_NAMES = listOf("HP", "Attack", "Defense", "Sp. Atk", "Sp. Def", "Speed")
    }

    // ------------------------------------------------------------------ state

    private class Mon(
        val id: UUID, val species: ResourceLocation, val aspects: Set<String>, val name: String, val level: Int,
        val home: String, val out: Boolean, val mine: Boolean, val shiny: Boolean, val trainer: String, val types: List<String>,
    )

    private class Parent(val name: String, val species: ResourceLocation?, val aspects: Set<String>)

    private class Egg(
        val id: UUID, val mine: Boolean, val owner: String, val zone: String, val nest: Boolean, val age: Long, val steps: Int,
        val mom: Parent, val dad: Parent, val species: ResourceLocation?, val aspects: Set<String>, val speciesName: String,
        val gender: String, val shiny: Boolean, val nature: String, val ability: String, val types: List<String>,
        val ivs: IntArray, val moves: List<String>,
    )

    private class Biome(
        val zone: String, val name: String, val color: Int, val awake: Boolean, val count: Int, val pct: Int, val placed: Boolean, val types: String,
        /** Its look (which of [looks]), and the looks it can have: name to description. */
        val look: Int = 0, val looks: List<Pair<String, String>> = emptyList(),
        /** A custom type (another mod's): where its Pokémon live, the owner's choice, how many it needs for a habitat of its own. */
        val custom: Boolean = false, val home: String = "", val homeName: String = "", val choice: String = "own",
        val need: Int = 10, val typeCount: Int = 0, val noRoom: Boolean = false, val ownOn: Boolean = true,
        /** A custom type's pocket (inside [pocketIn]), and how many it takes for one. */
        val pocket: Boolean = false, val pocketIn: String = "", val pocketNeed: Int = 5,
        /** Gravelmon's "???": always lives in the town. */
        val townOnly: Boolean = false,
        /** Its pocket is a sub-biome (a district of the habitat), and how many it takes for one. */
        val sub: Boolean = false, val subNeed: Int = 10,
        /** Looks from this one on wait for the Valley Heart. */
        val lockFrom: Int = 99
    )

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: (Int) -> Unit)

    private var data: CompoundTag = initial
    val sanctuaryId: String = initial.getString("id")
    private var tab: String = initial.getString("page").let { if (it == "members" || it.isEmpty()) "home" else it }
    /** The stretch of the (filtered, sorted) residents list the server sent: from [monsFrom], of [monsTotal] matching. */
    private var mons: List<Mon> = emptyList()
    private var monsFrom = 0
    private var monsTotal = 0
    private var monsAll = 0
    private var monsLoaded = false
    private var monsZones: Map<String, Int> = emptyMap()
    private var monsTypes: Map<String, Int> = emptyMap()
    /** The last stretch asked for (so it's asked once), and when the search was last typed in. */
    private var monsAsked = ""
    private var searchTypedAt = -1
    private var biomes: List<Biome> = emptyList()
    private var eggs: List<Egg> = emptyList()
    private var confirmDiscard: UUID? = null
    private var detail: CompoundTag? = null
    private var detailId: UUID? = null
    private var detailTab = "info"
    private var filterZone: String? = null
    /** The type the residents are filtered by (null: all), and whether its picker is open. */
    private var filterType: String? = null
    private var typePicker = false
    private var mineOnly = false
    private var scroll = 0.0
    private var maxScroll = 0.0
    private var toast: String? = null
    private var toastColor = TEXT
    private var toastTicks = 0
    private var ticks = 0
    private val hits = ArrayList<Hit>()
    private val stacks = HashMap<String, ItemStack>()
    private var tooltip: List<Component>? = null
    /** The crowd setting waiting for the player to confirm the performance warning (-1 = none). */
    private var confirmCrowd = -1
    /** The habitat whose look is being picked (and the look picked so far), or null. */
    private var lookZone: String? = null
    private var lookPick = 0
    /** The custom type whose home is being picked (and the choice so far), or null. */
    private var homeType: String? = null
    /** A resident whose home is being chosen (its id, name, the habitats it can live in, and where it lives now). */
    private var homeFor: UUID? = null
    private var homeForName = ""
    private var homeForOptions: List<String> = emptyList()
    private var homeForNow = ""
    private var homePick = "own"

    /** A zone's number on the region map: the town 1, the habitats 2..12, the custom types' own habitats 13 on. */
    private val zoneList = listOf("water", "ice", "rock", "ground", "fire", "dragon", "ghost", "poison", "grass", "fairy", "electric")
    private fun mapKey(zone: String): Int {
        if (zone == "normal") return 1
        val k = zoneList.indexOf(zone)
        if (k >= 0) return k + 2
        val ci = biomes.filter { it.custom && (it.placed || it.pocket) }.indexOfFirst { it.zone == zone }
        return if (ci >= 0) 13 + ci else -1
    }
    private var portrait: ModelWidget? = null
    private var portraitKey: String? = null
    private lateinit var search: EditBox

    private var px = 0
    private var py = 0
    private var pw = 0
    private var ph = 0

    init { parse(initial) }

    // ------------------------------------------------------------------ data from the server

    fun update(tag: CompoundTag) {
        data = tag
        val page = tag.getString("page")
        tab = when {
            page == "create" -> "create"
            tab == "create" -> page
            tag.getBoolean("open") && page.isNotEmpty() && page != "members" -> page
            else -> tab
        }
        parse(tag)
    }

    private fun parse(tag: CompoundTag) {
        val registries = Minecraft.getInstance().connection?.registryAccess()
        fun text(json: String): String = runCatching { registries?.let { Component.Serializer.fromJson(json, it)?.string } }.getOrNull() ?: json
        fun strings(list: ListTag) = (0 until list.size).map { list.getString(it) }
        if (tag.contains("mons")) {
            val list = tag.getList("mons", Tag.TAG_COMPOUND.toInt())
            mons = (0 until list.size).mapNotNull { i ->
                val m = list.getCompound(i)
                val sp = ResourceLocation.tryParse(m.getString("sp")) ?: return@mapNotNull null
                Mon(m.getUUID("u"), sp, strings(m.getList("as", Tag.TAG_STRING.toInt())).toSet(), text(m.getString("n")), m.getInt("l"),
                    m.getString("h"), m.getBoolean("o"), m.getBoolean("m"), m.getBoolean("sh"), m.getString("tr"), strings(m.getList("t", Tag.TAG_STRING.toInt())))
            }
            monsFrom = tag.getInt("monsFrom")
            monsTotal = tag.getInt("monsTotal")
            monsAll = tag.getInt("monsAll")
            monsLoaded = true
            monsZones = tag.getCompound("monsZones").let { c -> c.allKeys.associateWith { c.getInt(it) } }
            monsTypes = tag.getCompound("monsTypes").let { c -> c.allKeys.associateWith { c.getInt(it) } }
        }
        val bl = tag.getList("biomes", Tag.TAG_COMPOUND.toInt())
        biomes = (0 until bl.size).map { i ->
            val b = bl.getCompound(i)
            val looks = strings(b.getList("looks", Tag.TAG_STRING.toInt())).map { it.substringBefore('|') to it.substringAfter('|') }
            Biome(b.getString("zone"), b.getString("name"), b.getInt("color"), b.getBoolean("awake"), b.getInt("count"), b.getInt("pct"), b.getBoolean("placed"), b.getString("types"),
                b.getInt("look"), looks, b.getBoolean("custom"), b.getString("home"), b.getString("homeName"), b.getString("choice").ifEmpty { "own" },
                b.getInt("need"), b.getInt("typeCount"), b.getBoolean("noRoom"), !b.contains("ownOn") || b.getBoolean("ownOn"),
                b.getBoolean("pocket"), b.getString("pocketIn"), if (b.contains("pocketNeed")) b.getInt("pocketNeed") else 5, b.getBoolean("townOnly"),
                b.getBoolean("sub"), if (b.contains("subNeed")) b.getInt("subNeed") else 10,
                lockFrom = if (b.contains("lockFrom")) b.getInt("lockFrom") else 99)
        }
        if (tag.contains("eggs")) {
            val el = tag.getList("eggs", Tag.TAG_COMPOUND.toInt())
            fun parent(c: CompoundTag) = Parent(c.getString("n"), ResourceLocation.tryParse(c.getString("sp")), strings(c.getList("as", Tag.TAG_STRING.toInt())).toSet())
            eggs = (0 until el.size).map { i ->
                val e = el.getCompound(i)
                Egg(e.getUUID("u"), e.getBoolean("mine"), e.getString("owner"), e.getString("zone"), e.getBoolean("nest"), e.getLong("age"), e.getInt("steps"),
                    parent(e.getCompound("mom")), parent(e.getCompound("dad")), ResourceLocation.tryParse(e.getString("sp")),
                    strings(e.getList("as", Tag.TAG_STRING.toInt())).toSet(), text(e.getString("species")), e.getString("gender"), e.getBoolean("sh"),
                    e.getString("nature"), e.getString("ability"), strings(e.getList("t", Tag.TAG_STRING.toInt())), e.getIntArray("ivs"),
                    strings(e.getList("moves", Tag.TAG_STRING.toInt())).map { text(it) })
            }
        }
        if (tag.contains("mon")) {
            val m = tag.getCompound("mon")
            // resolve the text once
            for (key in listOf("n", "species")) m.putString(key, text(m.getString(key)))
            for (key in listOf("parents", "children")) {
                val l = m.getList(key, Tag.TAG_STRING.toInt())
                for (i in 0 until l.size) l[i] = net.minecraft.nbt.StringTag.valueOf(text(l.getString(i)))
            }
            for (key in listOf("friends", "rivals")) {
                val l = m.getList(key, Tag.TAG_COMPOUND.toInt())
                for (i in 0 until l.size) l.getCompound(i).let { it.putString("n", text(it.getString("n"))) }
            }
            val mv = m.getList("moves", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until mv.size) mv.getCompound(i).let { it.putString("n", text(it.getString("n"))) }
            detail = m
            detailId = m.getUUID("u")
        } else if (tag.contains("gone")) {
            detail = null
        }
        if (tag.contains("toast")) {
            toast = tag.getString("toast")
            toastColor = 0xFF000000.toInt() or tag.getInt("toastColor")
            toastTicks = 70
        }
    }

    private fun send(action: String, value: String = "", pokemon: UUID? = null, extra: String = "", page: String = tab) {
        val t = CompoundTag()
        t.putString("a", action)
        t.putString("id", sanctuaryId)
        t.putString("page", page)
        t.putString("v", value)
        t.putString("w", extra)
        pokemon?.let { t.putUUID("p", it) }
        ClientHooks.send(t)
    }

    private fun click() = Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))

    private fun goTab(next: String) {
        if (next == tab) return
        tab = next
        scroll = 0.0
        when (next) {
            "residents" -> { monsAsked = ""; askList(0) }
            "resident" -> detailId?.let { send("view", pokemon = it, page = "resident") }
            else -> send("view", page = next)
        }
    }

    private fun openResident(id: UUID) {
        if (detailId != id) { detail = null; portrait = null; portraitKey = null }
        detailId = id
        detailTab = "info"
        tab = "resident"
        send("view", pokemon = id, page = "resident")
    }

    // ------------------------------------------------------------------ screen basics

    override fun isPauseScreen() = false

    override fun init() {
        pw = min(480, width - 12)
        ph = min(286, height - 12)
        px = (width - pw) / 2
        py = (height - ph) / 2
        val old = if (this::search.isInitialized) search.value else ""
        search = EditBox(font, px + SIDE_W + 12, py + HEADER_H + 9, 120, 14, Component.literal("Search"))
        search.setHint(Component.literal("Search…").withColor(DIM))
        search.setBordered(false)
        search.setTextColor(TEXT)
        search.value = old
        search.setResponder { scroll = 0.0; searchTypedAt = ticks }
        addWidget(search)
        portrait = null
        portraitKey = null
    }

    override fun tick() {
        ticks++
        // (the search is sent a moment after typing stops)
        if (searchTypedAt >= 0 && ticks - searchTypedAt >= 5) { searchTypedAt = -1; if (tab == "residents") askList(0) }
        if (toastTicks > 0) toastTicks--
        if (ticks % 100 == 0 && (tab == "home" || tab == "resident")) {
            if (tab == "resident") detailId?.let { send("view", pokemon = it, page = "resident") } else send("view", page = "home")
        }
    }

    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, BACKDROP)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        hits.clear()
        tooltip = null
        renderBackground(g, mouseX, mouseY, partialTick)
        // the window
        box(g, px - 1, py - 1, pw + 2, ph + 2, PANEL_EDGE)
        box(g, px, py, pw, ph, PANEL)
        header(g, mouseX, mouseY)
        val create = tab == "create" || data.getBoolean("preview")
        search.visible = tab == "residents" && !create
        val cx = if (create) px + 12 else px + SIDE_W + 12
        val cy = py + HEADER_H + 8
        val cw = if (create) pw - 24 else pw - SIDE_W - 24
        val ch = ph - HEADER_H - 14
        if (!create) sidebar(g, mouseX, mouseY)
        when {
            create -> createPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "home" -> homePage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "residents" -> residentsPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "resident" -> residentPage(g, cx, cy, cw, ch, mouseX, mouseY, partialTick)
            tab == "biomes" -> biomesPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "events" -> eventsPage(g, cx, cy, cw, ch)
            tab == "goals" -> goalsPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "nest" -> nestPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "members" -> membersPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "guardian" -> guardianPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "dex" -> dexPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "settings" -> settingsPage(g, cx, cy, cw, ch, mouseX, mouseY)
        }
        if (search.visible) search.render(g, mouseX, mouseY, partialTick)
        toast?.takeIf { toastTicks > 0 }?.let { msg ->
            val tw = min(font.width(msg) + 16, cw)
            val tx = cx + (cw - tw) / 2
            val ty = py + ph - 24
            box(g, tx, ty, tw, 16, 0xF0101820.toInt())
            g.fill(tx, ty + 15, tx + tw, ty + 16, toastColor)
            g.drawCenteredString(font, font.plainSubstrByWidth(msg, tw - 8), tx + tw / 2, ty + 4, toastColor)
        }
        if (confirmCrowd > 0) crowdWarning(g, mouseX, mouseY)
        if (lookZone != null) lookPicker(g, mouseX, mouseY)
        if (typePicker && tab == "residents") typeFilterPicker(g, mouseX, mouseY)
        if (homeType != null) homePicker(g, mouseX, mouseY)
        if (homeFor != null) residentHomePicker(g, mouseX, mouseY)
        tooltip?.let { g.renderComponentTooltip(font, it, mouseX, mouseY) }
    }

    /** Picking a habitat's look (the owner): the looks with a line each; Apply rebuilds only that habitat. */
    private fun lookPicker(g: GuiGraphics, mx: Int, my: Int) {
        val b = biomes.firstOrNull { it.zone == lookZone } ?: run { lookZone = null; return }
        tooltip = null
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 400f)
        g.fill(px, py, px + pw, py + ph, 0xC0000000.toInt())
        hit(0, 0, width, height) { } // clicks outside the box do nothing
        val c = opaque(b.color)
        val bw = min(320, pw - 40)
        val rowH = 30
        val bh = 44 + b.looks.size * (rowH + 4) + 40
        val bx = px + (pw - bw) / 2
        val by = py + (ph - bh) / 2
        box(g, bx - 1, by - 1, bw + 2, bh + 2, c)
        box(g, bx, by, bw, bh, PANEL)
        g.fill(bx, by, bx + bw, by + 3, c)
        text(g, "${b.name}: choose its look", bx + 10, by + 12, TEXT, true)
        text(g, fit("Only this habitat is rebuilt, in a few minutes.", bw - 20), bx + 10, by + 26, SUB)
        b.looks.forEachIndexed { i, (name, desc) ->
            val ry = by + 42 + i * (rowH + 4)
            val chosen = i == lookPick
            val hover = over(mx, my, bx + 10, ry, bw - 20, rowH)
            box(g, bx + 10, ry, bw - 20, rowH, if (chosen) mix(c, 0x1A222B, 0.55f) else if (hover) CARD_HOVER else CARD)
            if (chosen) outline(g, bx + 10, ry, bw - 20, rowH, c)
            val tag = if (i == b.look) "  (now)" else ""
            val locked = i >= b.lockFrom
            text(g, fit(name + tag, bw - 36), bx + 18, ry + 5, if (locked) DIM else if (chosen) 0xFFFFFFFF.toInt() else TEXT)
            text(g, fit(if (locked) "♥ Unlocks at Valley Heart level 7" else desc, bw - 36), bx + 18, ry + 17, if (locked) 0xFFF07CB4.toInt() else SUB)
            if (locked && hover) tooltip = listOf(Component.literal(desc))
            if (!locked) hit(bx + 10, ry, bw - 20, rowH) { lookPick = i }
        }
        val half = (bw - 30) / 2
        val zone = b.zone
        val pick = lookPick
        button(g, bx + 10, by + bh - 28, half, 20, "Apply", GOLD, mx, my, enabled = pick != b.look, why = "That's the look it has now") {
            send("look", "$zone:$pick", page = "biomes"); lookZone = null
        }
        button(g, bx + 20 + half, by + bh - 28, half, 20, "Cancel", ACCENT, mx, my, filled = false) { lookZone = null }
        g.pose().popPose()
    }

    /** Picking where a custom type's Pokémon live (the owner): its own habitat, the town, or one of the habitats. */
    private fun homePicker(g: GuiGraphics, mx: Int, my: Int) {
        val b = biomes.firstOrNull { it.zone == homeType } ?: run { homeType = null; return }
        tooltip = null
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 400f)
        g.fill(px, py, px + pw, py + ph, 0xC0000000.toInt())
        hit(0, 0, width, height) { }
        val c = opaque(b.color)
        val options = (if (b.ownOn) listOf("own") else emptyList()) + listOf("normal") + zoneList
        fun label(o: String) = when (o) {
            "own" -> "Automatic"
            "normal" -> "Town (nothing of its own)"
            else -> biomes.firstOrNull { it.zone == o }?.name ?: o
        }
        val bw = min(360, pw - 30)
        val rowH = 18
        val colW = (bw - 30) / 2
        val rows = (options.size + 1) / 2
        val bh = 44 + rows * (rowH + 3) + 40
        val bx = px + (pw - bw) / 2
        val by = py + (ph - bh) / 2
        box(g, bx - 1, by - 1, bw + 2, bh + 2, c)
        box(g, bx, by, bw, bh, PANEL)
        g.fill(bx, by, bx + bw, by + 3, c)
        text(g, "Where ${b.types} Pokémon live", bx + 10, by + 12, TEXT, true)
        text(g, fit(if (b.ownOn) "Their pocket and sub-biome go in the habitat you pick (now ${b.typeCount} of them; a sub-biome at ${b.subNeed})." else "Pick the habitat that suits them.", bw - 20), bx + 10, by + 26, SUB)
        options.forEachIndexed { i, o ->
            val rx = bx + 10 + (i % 2) * (colW + 10)
            val ry = by + 42 + (i / 2) * (rowH + 3)
            val chosen = o == homePick
            val hover = over(mx, my, rx, ry, colW, rowH)
            val oc = if (o == "own") c else biomes.firstOrNull { it.zone == o }?.let { opaque(it.color) } ?: ACCENT
            box(g, rx, ry, colW, rowH, if (chosen) mix(oc, 0x1A222B, 0.55f) else if (hover) CARD_HOVER else CARD)
            g.fill(rx + 1, ry + 1, rx + 4, ry + rowH - 1, oc)
            if (chosen) outline(g, rx, ry, colW, rowH, oc)
            text(g, fit(label(o) + if (o == b.choice) " (now)" else "", colW - 14), rx + 8, ry + 5, if (chosen) 0xFFFFFFFF.toInt() else TEXT)
            hit(rx, ry, colW, rowH) { homePick = o }
        }
        val half = (bw - 30) / 2
        val type = b.zone
        val pick = homePick
        button(g, bx + 10, by + bh - 28, half, 20, "Apply", GOLD, mx, my, enabled = pick != b.choice, why = "That's where they live now") {
            send("customHome", "$type:$pick", page = "biomes"); homeType = null
        }
        button(g, bx + 20 + half, by + bh - 28, half, 20, "Cancel", ACCENT, mx, my, filled = false) { homeType = null }
        g.pose().popPose()
    }

    /** Choosing where a resident lives: every habitat it can live in, in two columns; a click moves it there. */
    private fun residentHomePicker(g: GuiGraphics, mx: Int, my: Int) {
        val id = homeFor ?: return
        tooltip = null
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 400f)
        g.fill(px, py, px + pw, py + ph, 0xC0000000.toInt())
        hit(0, 0, width, height) { }
        val options = homeForOptions
        val bw = min(380, pw - 30)
        val rowH = 18
        val colW = (bw - 30) / 2
        val rows = (options.size + 1) / 2
        val bh = (48 + rows * (rowH + 3) + 36).coerceAtMost(ph - 20)
        val bx = px + (pw - bw) / 2
        val by = py + (ph - bh) / 2
        box(g, bx - 1, by - 1, bw + 2, bh + 2, ACCENT)
        box(g, bx, by, bw, bh, PANEL)
        g.fill(bx, by, bx + bw, by + 3, ACCENT)
        text(g, fit("Where should $homeForName live?", bw - 20), bx + 10, by + 12, TEXT, true)
        text(g, fit("Now: ${zoneName(homeForNow)}. Pick a habitat and it moves there.", bw - 20), bx + 10, by + 26, SUB)
        val maxRows = (bh - 48 - 36) / (rowH + 3)
        options.forEachIndexed { i, o ->
            if (i / 2 >= maxRows) return@forEachIndexed
            val rx = bx + 10 + (i % 2) * (colW + 10)
            val ry = by + 42 + (i / 2) * (rowH + 3)
            val now = o == homeForNow
            val hover = over(mx, my, rx, ry, colW, rowH)
            val oc = opaque(color(o))
            box(g, rx, ry, colW, rowH, if (now) mix(oc, 0x1A222B, 0.55f) else if (hover) CARD_HOVER else CARD)
            g.fill(rx + 1, ry + 1, rx + 4, ry + rowH - 1, oc)
            if (now) outline(g, rx, ry, colW, rowH, oc)
            text(g, fit(zoneName(o) + if (now) " (now)" else "", colW - 14), rx + 8, ry + 5, if (now) 0xFFFFFFFF.toInt() else TEXT)
            if (!now) hit(rx, ry, colW, rowH) { send("act", "home", id, o, page = "resident"); homeFor = null }
        }
        button(g, bx + (bw - 120) / 2, by + bh - 28, 120, 20, "Cancel", ACCENT, mx, my, filled = false) { homeFor = null }
        g.pose().popPose()
    }

    /** "Are you sure?" for the bigger crowd settings, for people on slower computers. */
    private fun crowdWarning(g: GuiGraphics, mx: Int, my: Int) {
        tooltip = null
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 400f)
        g.fill(px, py, px + pw, py + ph, 0xC0000000.toInt())
        hit(0, 0, width, height) { } // clicks outside the box do nothing
        val everyone = confirmCrowd == 2
        val caps = data.getIntArray("crowdCaps")
        val n = caps.getOrElse(confirmCrowd) { 0 }
        val bw = min(300, pw - 40)
        val lines = listOf(
            "Up to $n Pokémon will be out at once instead of ${caps.getOrElse(0) { 0 }}.",
            "That looks much livelier, but every Pokémon costs performance:",
            "on slower computers (or busy servers) this can mean low FPS,",
            "lag and long loading in the valley.",
            if (everyone) "'Everyone' is only meant for strong PCs." else "Try it, and switch back to Normal if the game gets slow.",
            "You can change this back at any time.",
        )
        val bh = 48 + lines.size * 11 + 28
        val bx = px + (pw - bw) / 2
        val by = py + (ph - bh) / 2
        box(g, bx - 1, by - 1, bw + 2, bh + 2, BAD)
        box(g, bx, by, bw, bh, PANEL)
        g.fill(bx, by, bx + bw, by + 3, BAD)
        text(g, "⚠ Performance warning", bx + 10, by + 12, BAD, true)
        text(g, if (everyone) "Show every resident?" else "Show more Pokémon?", bx + 10, by + 26, TEXT)
        var ly = by + 42
        for (l in lines) { text(g, fit(l, bw - 20), bx + 10, ly, SUB); ly += 11 }
        val half = (bw - 30) / 2
        val choice = confirmCrowd
        button(g, bx + 10, by + bh - 28, half, 20, "Yes, show them", if (everyone) BAD else GOLD, mx, my) { send("crowd", "$choice", page = "home"); confirmCrowd = -1 }
        button(g, bx + 20 + half, by + bh - 28, half, 20, "Cancel", ACCENT, mx, my, filled = false) { confirmCrowd = -1 }
        g.pose().popPose()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (search.visible && search.mouseClicked(mouseX, mouseY, button)) { focused = search; return true }
        search.isFocused = false
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        for (h in hits.asReversed()) {
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                click()
                h.action(button)
                return true
            }
        }
        portrait?.let { if (tab == "resident" && it.isMouseOver(mouseX, mouseY)) return it.mouseClicked(mouseX, mouseY, button) }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll - scrollY * 24).coerceIn(0.0, max(0.0, maxScroll))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (confirmCrowd > 0 && keyCode == 256) { confirmCrowd = -1; return true }
        if (lookZone != null && keyCode == 256) { lookZone = null; return true }
        if (typePicker && keyCode == 256) { typePicker = false; return true }
        if (homeType != null && keyCode == 256) { homeType = null; return true }
        if (search.isFocused && keyCode != 256) return search.keyPressed(keyCode, scanCode, modifiers)
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun box(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, c: Int) {
        g.fill(x + 1, y, x + w - 1, y + h, c)
        g.fill(x, y + 1, x + 1, y + h - 1, c)
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c)
    }

    private fun outline(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, c: Int) {
        g.fill(x + 1, y, x + w - 1, y + 1, c)
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, c)
        g.fill(x, y + 1, x + 1, y + h - 1, c)
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 255) * (1 - t) + ((b shr shift) and 255) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun opaque(rgb: Int) = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
    private fun over(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h
    private fun hit(x: Int, y: Int, w: Int, h: Int, action: (Int) -> Unit) { hits += Hit(x, y, w, h, action) }

    private fun text(g: GuiGraphics, s: String, x: Int, y: Int, c: Int = TEXT, shadow: Boolean = false) = g.drawString(font, s, x, y, c, shadow)

    private fun bigText(g: GuiGraphics, s: String, x: Int, y: Int, c: Int, scale: Float) {
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), y.toFloat(), 0f)
        g.pose().scale(scale, scale, 1f)
        g.drawString(font, s, 0, 0, c, true)
        g.pose().popPose()
    }

    private fun fit(s: String, w: Int) = if (font.width(s) <= w) s else font.plainSubstrByWidth(s, w - font.width("…")) + "…"

    private fun bar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, frac: Double, c: Int) {
        box(g, x, y, w, h, TRACK)
        val f = (w * frac.coerceIn(0.0, 1.0)).roundToInt()
        if (f > 1) box(g, x, y, f, h, c)
    }

    private fun item(g: GuiGraphics, stack: ItemStack, x: Int, y: Int, scale: Float = 1f) {
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), y.toFloat(), 0f)
        g.pose().scale(scale, scale, 1f)
        g.renderItem(stack, 0, 0)
        g.pose().popPose()
    }

    private fun itemOf(id: String, fallback: net.minecraft.world.item.Item = Items.PAPER): ItemStack = stacks.getOrPut(id) {
        val item = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.get(it) }
        ItemStack(if (item == null || item == Items.AIR) fallback else item)
    }

    private fun monStack(species: ResourceLocation, aspects: Set<String>): ItemStack = stacks.getOrPut("$species|${aspects.sorted()}") {
        val sp = PokemonSpecies.getByIdentifier(species) ?: return@getOrPut ItemStack(Items.PAPER)
        runCatching { PokemonItem.from(sp, aspects) }.getOrElse { ItemStack(Items.PAPER) }
    }

    /** A rounded button; [enabled] false draws it greyed out (with [why] as its tooltip). */
    private fun button(
        g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, label: String, color: Int, mx: Int, my: Int,
        enabled: Boolean = true, why: String? = null, filled: Boolean = true, action: (Int) -> Unit,
    ) {
        val hover = over(mx, my, x, y, w, h)
        if (!enabled) {
            box(g, x, y, w, h, 0xFF2A323D.toInt())
            g.drawCenteredString(font, fit(label, w - 6), x + w / 2, y + (h - 8) / 2, DIM)
            if (hover && why != null) tooltip = listOf(Component.literal(why))
            return
        }
        if (filled) {
            box(g, x, y, w, h, if (hover) mix(color, 0xFFFFFF, 0.18f) else color)
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, mix(color, 0x000000, 0.3f))
            g.drawCenteredString(font, fit(label, w - 6), x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF.toInt())
        } else {
            box(g, x, y, w, h, if (hover) CARD_HOVER else CARD)
            outline(g, x, y, w, h, color)
            g.drawCenteredString(font, fit(label, w - 6), x + w / 2, y + (h - 8) / 2, color)
        }
        hit(x, y, w, h, action)
    }

    private fun pill(g: GuiGraphics, label: String, rgb: Int, x: Int, y: Int): Int {
        val w = font.width(label) + 10
        val c = opaque(rgb)
        box(g, x, y, w, 12, mix(c, 0x000000, 0.15f))
        g.drawString(font, label, x + 5, y + 2, 0xFFFFFFFF.toInt(), true)
        return w
    }

    private fun color(zone: String): Int = biomes.firstOrNull { it.zone == zone }?.color ?: 0x9AB8D8
    private fun zoneName(zone: String): String = biomes.firstOrNull { it.zone == zone }?.name ?: if (zone.isEmpty()) "Skies" else zone.replaceFirstChar { it.uppercase() }
    private fun typeLabel(t: String) = t.replaceFirstChar { it.uppercase() }
    /** A type's colour and name as its mod gives them (Gravelmon's and Lost Lore's too). */
    private fun typeColor(t: String): Int = runCatching { com.cobblemon.mod.common.api.types.ElementalTypes.get(t)?.hue }.getOrNull() ?: 0xA8A878
    private fun typeName(t: String): String = runCatching { com.cobblemon.mod.common.api.types.ElementalTypes.get(t)?.displayName?.string }.getOrNull() ?: typeLabel(t)

    /** Picking a type to filter the residents by: every type that someone has, with how many. */
    private fun typeFilterPicker(g: GuiGraphics, mx: Int, my: Int) {
        tooltip = null
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 400f)
        g.fill(px, py, px + pw, py + ph, 0xC0000000.toInt())
        hit(0, 0, width, height) { typePicker = false }
        val standard = listOf("normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying",
            "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy")
        val counts = HashMap<String, Int>()
        counts.putAll(monsTypes)
        val types = counts.keys.sortedWith(compareBy({ standard.indexOf(it).let { i -> if (i < 0) 99 else i } }, { it }))
        val options = listOf<String?>(null) + types
        val cols = 4
        val bw = min(400, pw - 30)
        val colW = (bw - 20 - (cols - 1) * 6) / cols
        val rowH = 18
        val rows = (options.size + cols - 1) / cols
        val bh = 34 + rows * (rowH + 4) + 6
        val bx = px + (pw - bw) / 2
        val by = py + (ph - bh) / 2
        box(g, bx - 1, by - 1, bw + 2, bh + 2, ACCENT)
        box(g, bx, by, bw, bh, PANEL)
        hit(bx, by, bw, bh) { }
        text(g, "Show residents of the type…", bx + 10, by + 12, TEXT, true)
        options.forEachIndexed { i, t ->
            val rx = bx + 10 + (i % cols) * (colW + 6)
            val ry = by + 30 + (i / cols) * (rowH + 4)
            val c = t?.let { opaque(typeColor(it)) } ?: SUB
            val chosen = t == filterType
            val hover = over(mx, my, rx, ry, colW, rowH)
            box(g, rx, ry, colW, rowH, if (chosen) mix(c, 0x1A222B, 0.55f) else if (hover) CARD_HOVER else CARD)
            g.fill(rx + 1, ry + 1, rx + 4, ry + rowH - 1, c)
            if (chosen) outline(g, rx, ry, colW, rowH, c)
            val label = if (t == null) "All ($monsAll)" else "${typeName(t)} (${counts[t]})"
            text(g, fit(label, colW - 12), rx + 8, ry + 5, if (chosen) 0xFFFFFFFF.toInt() else TEXT)
            hit(rx, ry, colW, rowH) { filterType = t; typePicker = false; filtersChanged() }
        }
        g.pose().popPose()
    }

    // ------------------------------------------------------------------ frame

    private fun header(g: GuiGraphics, mx: Int, my: Int) {
        g.fillGradient(px, py, px + pw, py + HEADER_H, 0xFF22313F.toInt(), 0xFF1A2530.toInt())
        g.fill(px, py + HEADER_H - 1, px + pw, py + HEADER_H, mix(ACCENT, 0x000000, 0.35f))
        item(g, ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()), px + 6, py + 5)
        val title = if (data.getBoolean("preview")) "Create your Sanctuary" else "${data.getString("ownerName")}'s Sanctuary"
        text(g, title, px + 26, py + 9, TEXT, true)
        if (!data.getBoolean("preview")) {
            val bx = px + 30 + font.width(title)
            pill(g, data.getString("sizeLabel"), ACCENT, bx, py + 7)
        }
        // close
        val cx = px + pw - 20
        val hover = over(mx, my, cx, py + 5, 15, 15)
        box(g, cx, py + 5, 15, 15, if (hover) BAD else 0xFF2A3644.toInt())
        g.drawCenteredString(font, "✕", cx + 8, py + 9, TEXT)
        hit(cx, py + 5, 15, 15) { onClose() }
    }

    private fun sidebar(g: GuiGraphics, mx: Int, my: Int) {
        val x = px
        val y = py + HEADER_H
        g.fill(x + 1, y, x + SIDE_W, py + ph - 1, SIDEBAR)
        g.fill(x + SIDE_W, y, x + SIDE_W + 1, py + ph - 1, PANEL_EDGE)
        val tabs = listOf(
            Triple("home", "Overview", ItemStack(ModContent.SANCTUARY_CORE_ITEM.get())),
            Triple("residents", "Residents", itemOf("cobblemon:poke_ball", Items.ENDER_PEARL)),
            Triple("biomes", "Region", itemOf("minecraft:filled_map", Items.MAP)),
            Triple("nest", "Nest", ItemStack(ModContent.POKEMON_EGG.get())),
            Triple("events", "Journal", ItemStack(ModContent.JOURNAL.get())),
            Triple("goals", "Goals", itemOf("cobblemon:rare_candy", Items.NETHER_STAR)),
            Triple("members", "Members", ItemStack(Items.PLAYER_HEAD)),
        ) + if (data.getBoolean("canSettings")) listOf(Triple("settings", "Settings", ItemStack(Items.COMPARATOR))) else emptyList()
        var ty = y + 8
        for ((key, label, icon) in tabs) {
            val active = tab == key || (key == "residents" && tab == "resident")
            val hover = over(mx, my, x + 5, ty, SIDE_W - 10, 22)
            if (active || hover) box(g, x + 5, ty, SIDE_W - 10, 22, if (active) 0xFF233544.toInt() else 0xFF1B2733.toInt())
            if (active) g.fill(x + 5, ty + 3, x + 7, ty + 19, ACCENT)
            item(g, icon, x + 11, ty + 3)
            text(g, label, x + 31, ty + 7, if (active) TEXT else SUB)
            val ready = data.getList("goals", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).count { l.getCompound(it).getBoolean("reached") && !l.getCompound(it).getBoolean("claimed") } }
            if (key == "goals" && ready > 0) {
                val badge = "$ready"
                val bw = font.width(badge) + 6
                box(g, x + SIDE_W - 10 - bw - 2, ty + 6, bw, 11, 0xFFE0A030.toInt())
                g.drawCenteredString(font, badge, x + SIDE_W - 12 - bw / 2, ty + 8, 0xFFFFFFFF.toInt())
            }
            if (key == "nest" && eggs.isNotEmpty()) {
                val badge = "${eggs.size}"
                val bw = font.width(badge) + 6
                box(g, x + SIDE_W - 10 - bw - 2, ty + 6, bw, 11, 0xFFD05C8A.toInt())
                g.drawCenteredString(font, badge, x + SIDE_W - 12 - bw / 2, ty + 8, 0xFFFFFFFF.toInt())
            }
            hit(x + 5, ty, SIDE_W - 10, 22) { goTab(key) }
            ty += 24
        }
        // little summary at the bottom
        val by = py + ph - 46
        g.fill(x + 8, by - 6, x + SIDE_W - 8, by - 5, PANEL_EDGE)
        text(g, "${data.getInt("residents")} residents", x + 10, by, SUB)
        val dot = if (data.getInt("live") > 0) GOOD else DIM
        g.fill(x + 10, by + 15, x + 14, by + 19, dot)
        text(g, "${data.getInt("live")} out now", x + 18, by + 13, SUB)
        val prog = data.getInt("progress")
        val left = data.getInt("minutesLeft")
        val label = when {
            prog < 0 -> "Valley ready"
            left > 0 -> "Building $prog% · ~$left min"
            else -> "Building $prog%"
        }
        text(g, fit(label, SIDE_W - 20), x + 10, by + 26, if (prog >= 0) GOLD else DIM)
        if (prog >= 0 && over(mx, my, x + 10, by + 24, SIDE_W - 20, 12)) tooltip = listOf(
            Component.literal("Building the valley").withColor(GOLD),
            Component.literal(if (left > 0) "About $left minute${if (left == 1) "" else "s"} left at the current speed" else "Working out how long it will take…").withColor(SUB),
            Component.literal("Stay inside the valley so it keeps going").withColor(SUB))
    }

    // ------------------------------------------------------------------ create

    private fun createPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val mid = x + w / 2
        bigText(g, "A valley for your Pokémon", mid - (font.width("A valley for your Pokémon") * 1.4f / 2).toInt(), y + 4, GOLD, 1.4f)
        val lines = listOf(
            "This Core opens a path to your own valley in the Sanctuary dimension.",
            "Every Pokémon in your PC will live there, in the biome of its type.",
            "Your world isn't changed.",
        )
        lines.forEachIndexed { i, l -> g.drawCenteredString(font, fit(l, w), mid, y + 22 + i * 11, SUB) }
        g.drawCenteredString(font, "Pokémon in your PC: ${data.getInt("residents")}", mid, y + 58, TEXT)

        val sizes = data.getList("sizes", Tag.TAG_COMPOUND.toInt())
        val gap = 8
        val count = max(1, sizes.size)
        val cw = (w - gap * (count - 1)) / count
        val cy = y + 74
        val chh = 84
        for (i in 0 until sizes.size) {
            val sz = sizes.getCompound(i)
            val cx = x + i * (cw + gap)
            val chosen = data.getInt("size") == i
            val allowed = sz.getBoolean("allowed")
            val unlocked = sz.getBoolean("unlocked")
            val hover = over(mx, my, cx, cy, cw, chh) && unlocked
            box(g, cx, cy, cw, chh, if (hover) CARD_HOVER else CARD)
            if (chosen) outline(g, cx, cy, cw, chh, ACCENT)
            val icon = listOf(Items.OAK_SAPLING, Items.AZALEA, Items.FLOWERING_AZALEA, Items.CHERRY_SAPLING)[i.coerceIn(0, 3)]
            item(g, ItemStack(icon), cx + cw / 2 - 12, cy + 6, 1.5f)
            g.drawCenteredString(font, sz.getString("label"), cx + cw / 2, cy + 32, if (unlocked) TEXT else DIM)
            g.drawCenteredString(font, "Radius ${sz.getInt("radius")} blocks", cx + cw / 2, cy + 44, SUB)
            g.drawCenteredString(font, "Up to ${sz.getInt("cap")} out at once", cx + cw / 2, cy + 55, SUB)
            val status = when {
                !allowed -> "Off on this server" to DIM
                !unlocked -> "Needs ${sz.getInt("needed")} Pokémon" to BAD
                chosen -> "✔ Chosen" to ACCENT
                else -> "Click to choose" to GOLD
            }
            g.drawCenteredString(font, status.first, cx + cw / 2, cy + 69, status.second)
            if (unlocked && !chosen) hit(cx, cy, cw, chh) { send("size", SanctuarySize.entries[i].name, page = "create") }
        }
        val by = cy + chh + 16
        val owner = data.getBoolean("isOwner")
        button(g, mid - 112, by, 106, 20, "Create", 0xFF3E9E62.toInt(), mx, my, owner, "Only the owner can create it") { send("create", page = "create") }
        button(g, mid + 6, by, 106, 20, "Cancel", 0xFFB24A4A.toInt(), mx, my, owner, "Only the owner can cancel it") { send("cancel", page = "create") }
        g.drawCenteredString(font, "You can grow the valley later, for free, as more Pokémon move in.", mid, by + 28, DIM)
    }

    // ------------------------------------------------------------------ overview

    /** The Sanctuary Dex: every species that has lived here, in Pokédex order; born here and evolved here are marked. */
    private fun dexPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val back = "← Goals"
        val bw = font.width(back) + 10
        box(g, x, y, bw, 14, if (over(mx, my, x, y, bw, 14)) CARD_HOVER else CARD)
        text(g, back, x + 5, y + 3, SUB)
        hit(x, y, bw, 14) { goTab("goals") }
        val list = data.getList("dex", Tag.TAG_STRING.toInt())
        text(g, "Sanctuary Dex: ${list.size} of ${data.getInt("dexTotal")} kinds", x + bw + 10, y + 3, TEXT, true)
        text(g, "Every kind of Pokémon that has lived here.  H = born here,  E = evolved here.", x, y + 20, SUB)
        val gy = y + 34
        val gh = h - 34
        val cell = 34
        val cols = maxOf(1, w / cell)
        val rows = (list.size + cols - 1) / cols
        maxScroll = max(0.0, rows * cell - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        for (i in 0 until list.size) {
            val parts = list.getString(i).split('|')
            val cx = x + (i % cols) * cell
            val cy = gy + (i / cols) * cell - scroll.toInt()
            if (cy + cell < gy || cy > gy + gh) continue
            val hover = over(mx, my, cx, cy, cell - 2, cell - 2) && my in gy until gy + gh
            box(g, cx, cy, cell - 2, cell - 2, if (hover) CARD_HOVER else CARD)
            ResourceLocation.tryParse(parts[0])?.let { item(g, monStack(it, emptySet()), cx + 7, cy + 5) }
            val flags = parts.getOrElse(3) { "" }
            if (flags.isNotEmpty()) text(g, flags, cx + cell - 4 - font.width(flags), cy + 22, GOLD)
            if (hover) tooltip = listOfNotNull(
                Component.literal("#${parts.getOrElse(2) { "?" }} ${parts.getOrElse(1) { "" }}"),
                if ('H' in flags) Component.literal("Born in the sanctuary").withStyle(net.minecraft.ChatFormatting.GOLD) else null,
                if ('E' in flags) Component.literal("Evolved in the sanctuary").withStyle(net.minecraft.ChatFormatting.AQUA) else null)
        }
        g.disableScissor()
        if (list.size == 0) text(g, "Nobody yet: residents are noted here a minute after they move in.", x, gy + 4, DIM)
    }

    /** Choosing the valley's guardian: the owner's legendaries as cards, the guardian marked; click one to name it. */
    private fun guardianPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val back = "← Goals"
        val bw = font.width(back) + 10
        val bh = over(mx, my, x, y, bw, 14)
        box(g, x, y, bw, 14, if (bh) CARD_HOVER else CARD)
        text(g, back, x + 5, y + 3, SUB)
        hit(x, y, bw, 14) { goTab("goals") }
        text(g, "★ The Valley's Guardian", x + bw + 10, y + 3, GOLD, true)
        text(g, fit("It watches over the valley from the Guardian's Rise at dawn and dusk. Residents of its types grow fond of their trainers faster, and its habitat glows softly.", w), x, y + 20, SUB)
        val top = y + 36
        if (!data.getBoolean("gUnlocked")) {
            text(g, "A guardian can be chosen at Valley Heart level ${data.getInt("gLevel")} (the Golden Heart).", x, top + 4, DIM)
            return
        }
        if (!data.getBoolean("isOwner")) { text(g, "Only the sanctuary's owner can choose its guardian.", x, top + 4, DIM); return }
        if (data.contains("gWait")) text(g, "The guardian can change again in ${data.getLong("gWait")} minutes.", x, top, DIM)
        val list = data.getList("legends", Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) {
            text(g, "You have no legendary or mythical Pokémon in your PC yet. The Guardian's Rise is waiting for one.", x, top + 14, DIM)
            return
        }
        val current = if (data.hasUUID("gId")) data.getUUID("gId") else null
        val cols = maxOf(1, w / 150)
        val gap = 6
        val cw = (w - gap * (cols - 1)) / cols
        val chh = 52
        val gy = top + 14
        val gh = h - (gy - y)
        val rows = (list.size + cols - 1) / cols
        maxScroll = max(0.0, rows * (chh + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        for (i in 0 until list.size) {
            val c = list.getCompound(i)
            val cx = x + (i % cols) * (cw + gap)
            val cy = gy + (i / cols) * (chh + gap) - scroll.toInt()
            if (cy + chh < gy || cy > gy + gh) continue
            val id = c.getUUID("u")
            val chosen = id == current
            val hover = over(mx, my, cx, cy, cw, chh) && my in gy until gy + gh
            box(g, cx, cy, cw, chh, if (chosen) 0xFF3A2F18.toInt() else if (hover) CARD_HOVER else CARD)
            if (chosen) { g.fill(cx, cy, cx + cw, cy + 1, GOLD); g.fill(cx, cy + chh - 1, cx + cw, cy + chh, GOLD); g.fill(cx, cy, cx + 1, cy + chh, GOLD); g.fill(cx + cw - 1, cy, cx + cw, cy + chh, GOLD) }
            ResourceLocation.tryParse(c.getString("sp"))?.let { sp ->
                val aspects = c.getList("as", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) }.toSet() }
                item(g, monStack(sp, aspects), cx + 6, cy + 10, 2f)
            }
            text(g, fit(c.getString("name"), cw - 50), cx + 44, cy + 7, if (chosen) GOLD else TEXT, true)
            text(g, "Lv.${c.getInt("lv")}" + if (c.getBoolean("mythical")) " · Mythical" else " · Legendary", cx + 44, cy + 20, SUB)
            var tx = cx + 44
            for (type in c.getString("types").split(',').filter { it.isNotBlank() }) {
                val label = type.replaceFirstChar { it.uppercase() }
                val tw = font.width(label) + 6
                box(g, tx, cy + 32, tw, 11, opaque(color(type)))
                text(g, label, tx + 3, cy + 34, 0xFFFFFFFF.toInt())
                tx += tw + 3
            }
            if (chosen) text(g, "★ Guardian", cx + cw - 6 - font.width("★ Guardian"), cy + 34, GOLD)
            else if (hover) {
                tooltip = listOf(Component.literal("Make ${c.getString("name")} the valley's guardian"))
                if (cy >= gy && cy + chh <= gy + gh) hit(cx, cy, cw, chh) { send("guardianset", pokemon = id, page = "guardian") }
            }
        }
        g.disableScissor()
    }

    private fun homePage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val owner = data.getBoolean("isOwner")
        // stat cards
        val gap = 6
        val sw = (w - gap * 3) / 4
        val cap = data.getInt("capNow")
        val prog = data.getInt("progress")
        val stats = listOf(
            Triple("Residents", "${data.getInt("residents")}", ACCENT),
            Triple("Out now", "${data.getInt("live")}/$cap", GOOD),
            Triple("Biomes awake", "${data.getInt("awake")}/${data.getInt("zones")}", GOLD),
            Triple("Valley", if (prog >= 0) "$prog%" else "Ready", BLUE),
        )
        stats.forEachIndexed { i, (label, value, c) ->
            val sx = x + i * (sw + gap)
            box(g, sx, y, sw, 42, CARD)
            g.fill(sx + 1, y, sx + sw - 1, y + 2, c)
            bigText(g, value, sx + 8, y + 9, TEXT, 1.6f)
            text(g, label, sx + 8, y + 29, SUB)
            if (i == 3 && prog >= 0) bar(g, sx + 8, y + 38, sw - 16, 2, prog / 100.0, c)
        }
        // size and growth
        val sy = y + 50
        box(g, x, sy, w, 52, CARD)
        text(g, "${data.getString("sizeLabel")} Sanctuary", x + 10, sy + 8, TEXT, true)
        text(g, "Radius ${data.getInt("radius")} blocks", x + 10 + font.width("${data.getString("sizeLabel")} Sanctuary") + 8, sy + 8, SUB)
        // its rank, with what makes it up
        if (data.contains("rank")) {
            val label = "★ ${data.getString("rank")}"
            val rx = x + w - 10 - font.width(label)
            text(g, label, rx, sy + 8, GOLD, true)
            if (over(mx, my, rx - 2, sy + 4, font.width(label) + 4, 14)) tooltip = buildList {
                add(Component.literal("${data.getString("rank")}: ${data.getInt("rankScore")} points").withStyle(net.minecraft.ChatFormatting.GOLD))
                val parts = data.getList("rankParts", Tag.TAG_STRING.toInt())
                for (i in 0 until parts.size) add(Component.literal(parts.getString(i)).withStyle(net.minecraft.ChatFormatting.GRAY))
                if (data.contains("rankNext")) add(Component.literal("Next: ${data.getString("rankNext")} at ${data.getInt("rankNextAt")}").withStyle(net.minecraft.ChatFormatting.YELLOW))
            }
        }
        if (data.contains("nextLabel")) {
            val needed = data.getInt("nextNeeded")
            val have = data.getInt("residents")
            val ready = have >= needed && data.getBoolean("nextAllowed")
            text(g, "Next: ${data.getString("nextLabel")} (radius ${data.getInt("nextRadius")})", x + 10, sy + 22, SUB)
            bar(g, x + 10, sy + 35, w - 128, 8, have.toDouble() / needed.coerceAtLeast(1), if (ready) GOOD else ACCENT)
            val countText = "$have/$needed Pokémon"
            text(g, countText, x + w - 118 - font.width(countText), sy + 22, if (ready) GOOD else DIM)
            button(g, x + w - 110, sy + 20, 100, 22, "Expand (free)", 0xFF3E9E62.toInt(), mx, my,
                enabled = owner && ready,
                why = when { !owner -> "Only the owner can expand"; !data.getBoolean("nextAllowed") -> "Disabled on this server"; else -> "Needs $needed Pokémon living here" }) { send("expand", page = "home") }
        } else {
            text(g, "Your valley is as big as it gets.", x + 10, sy + 24, GOOD)
        }
        // actions
        val ay = sy + 60
        val inside = data.getBoolean("inside")
        val bw = (w - gap * 4) / 5
        if (inside) button(g, x, ay, bw, 22, "Return home", PURPLE, mx, my) { send("return") }
        else button(g, x, ay, bw, 22, "Go to valley", PURPLE, mx, my) { send("travel") }
        button(g, x + bw + gap, ay, bw, 22, "Reshape", GOLD, mx, my, enabled = owner, why = "Only the owner can reshape it", filled = false) { send("reshape", page = "home") }
        button(g, x + (bw + gap) * 2, ay, bw, 22, "Repair here", GOOD, mx, my, enabled = owner && inside,
            why = if (!owner) "Only the owner can repair the land" else "Stand in the valley where you want the land repaired", filled = false) { send("repair", page = "home") }
        if (owner && inside && over(mx, my, x + (bw + gap) * 2, ay, bw, 22)) tooltip = listOf(
            Component.literal("Repair the land around you").withColor(GOOD),
            Component.literal("Rebuilds about 40 blocks around where you stand:").withColor(SUB),
            Component.literal("cut trees, leftovers, odd ground. Your own builds aren't touched.").withColor(SUB))
        button(g, x + (bw + gap) * 3, ay, bw, 22, "Respawn all", BLUE, mx, my, enabled = owner, why = "Only the owner can do this", filled = false) { send("respawn", page = "home") }
        if (owner && over(mx, my, x + (bw + gap) * 3, ay, bw, 22)) tooltip = listOf(
            Component.literal("Respawn all Pokémon").withColor(BLUE),
            Component.literal("Everyone out goes home and comes back out on proper ground.").withColor(SUB),
            Component.literal("Frees Pokémon stuck underground or in walls after a reshape.").withColor(SUB))
        button(g, x + (bw + gap) * 4, ay, bw, 22, "New Journal", ACCENT, mx, my, filled = false) { send("journal", page = "home") }
        // how many Pokémon are out at once
        val cy = ay + 28
        val caps = data.getIntArray("crowdCaps")
        val crowd = data.getInt("crowd")
        val allowed = data.getBoolean("crowdAllowed")
        text(g, "Pokémon out at once:", x, cy + 5, SUB)
        val labels = listOf("Normal", "More", "Everyone")
        val colors = listOf(GOOD, GOLD, BAD)
        var cx = x + font.width("Pokémon out at once:") + 8
        val cw = (x + w - cx - gap * 2) / 3
        for (i in 0..2) {
            val label = "${labels[i]} (${caps.getOrElse(i) { 0 }})"
            val ok = owner && (i == 0 || allowed)
            if (i == crowd) {
                box(g, cx, cy, cw, 18, colors[i])
                g.drawCenteredString(font, fit(label, cw - 6), cx + cw / 2, cy + 5, 0xFFFFFFFF.toInt())
            } else {
                button(g, cx, cy, cw, 18, label, colors[i], mx, my, enabled = ok, filled = false,
                    why = if (!owner) "Only the owner can change this" else "Turned off on this server") {
                    if (i == 0) send("crowd", "0", page = "home") else confirmCrowd = i
                }
            }
            if (i > 0 && over(mx, my, cx, cy, cw, 18)) tooltip = listOf(
                Component.literal("⚠ Heavier on your computer").withStyle(net.minecraft.ChatFormatting.GOLD),
                Component.literal("Up to ${caps.getOrElse(i) { 0 }} Pokémon out at once.").withStyle(net.minecraft.ChatFormatting.GRAY),
            )
            cx += cw + gap
        }
        // residents away on expeditions
        var ey = cy + 26
        if (data.contains("away")) {
            val away = data.getList("away", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it).split('|') } }
            fun left(m: String) = m.toLongOrNull()?.takeIf { it >= 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
            val line = "✈ On expeditions: " + away.joinToString(", ") { p -> p[0] + " (" + p.getOrElse(1) { "?" } + (left(p.getOrElse(2) { "" })?.let { ", $it" } ?: "") + ")" }
            text(g, fit(line, w), x, ey, 0xFF7CC8F0.toInt())
            if (over(mx, my, x, ey - 2, w, 12)) tooltip = buildList {
                add(Component.literal("Away on expeditions (${away.size})").withColor(0xFF7CC8F0.toInt()))
                away.forEach { p -> add(Component.literal("${p[0]}: ${p.getOrElse(1) { "?" }}" + (left(p.getOrElse(2) { "" })?.let { ", back in $it" } ?: "")).withColor(SUB)) }
                add(Component.literal("Friends see them off and welcome them home at the Core.").withColor(DIM))
            }
            ey += 14
        }
        // recent events
        text(g, "Recently", x, ey, SUB)
        val basket = if (data.getBoolean("hasBasket")) "Basket: ${data.getInt("basketItems")} items" else "The Gathering Post is being built in the town"
        var bx2 = x + w
        if (data.getBoolean("hasBasket")) {
            // what residents bring: click to switch a kind of find on or off
            val off = data.getList("basketOff", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) }.toSet() }
            for ((key, label) in listOf("jobs" to "Jobs", "apricorns" to "Apricorns", "berries" to "Berries", "drops" to "Drops").reversed()) {
                val on = key !in off
                val pw2 = font.width(label) + 8
                bx2 -= pw2
                val hover = over(mx, my, bx2, ey - 2, pw2, 12)
                box(g, bx2, ey - 2, pw2, 12, if (on) mix(GOLD, 0x182029, if (hover) 0.45f else 0.6f) else if (hover) CARD_HOVER else CARD)
                g.drawString(font, label, bx2 + 4, ey, if (on) TEXT else DIM, false)
                if (hover) tooltip = listOf(Component.literal(if (on) "Residents bring $label (click to stop)" else "Residents don't bring $label (click to allow)"),
                    Component.literal("Each item stops at ${data.getInt("basketCap")} in the basket (the Valley Heart makes room for more).").withStyle(net.minecraft.ChatFormatting.GRAY))
                if (owner) hit(bx2, ey - 2, pw2, 12) { send("basket", key, page = "home") }
                bx2 -= 3
            }
        }
        text(g, basket, bx2 - font.width(basket) - 4, ey, if (data.getBoolean("hasBasket")) GOLD else DIM)
        val events = data.getList("events", Tag.TAG_STRING.toInt())
        if (events.isEmpty()) text(g, "Nothing has happened yet.", x + 10, ey + 14, DIM)
        var ly = ey + 14
        for (i in 0 until events.size) {
            if (ly + 10 > y + h - 22) break
            g.fill(x + 3, ly + 3, x + 6, ly + 6, ACCENT)
            text(g, fit(events.getString(i), w - 14), x + 12, ly, if (i == 0) TEXT else SUB)
            ly += 12
        }
    }

    // ------------------------------------------------------------------ residents

    /** Asks the server for the residents matching the filters and search, from [offset] in the list. */
    private fun askList(offset: Int) {
        val q = if (this::search.isInitialized) search.value.replace('\t', ' ') else ""
        val v = "$offset\t${filterZone ?: ""}\t${filterType ?: ""}\t${if (mineOnly) 1 else 0}\t$q"
        if (v == monsAsked) return
        monsAsked = v
        send("list", v, page = "residents")
    }

    /** The filters changed: back to the top of the list. */
    private fun filtersChanged() { scroll = 0.0; askList(0) }

    private fun residentsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        // search field background
        box(g, x - 3, y - 2, 128, 18, TRACK)
        outline(g, x - 3, y - 2, 128, 18, if (search.isFocused) ACCENT else PANEL_EDGE)
        search.x = x + 2
        search.y = y + 3
        // filter
        val zones = monsZones.keys.sortedBy { z -> biomes.indexOfFirst { it.zone == z }.let { if (it < 0) 999 else it } }
        val fx = x + 132
        val fLabel = "Biome: " + (filterZone?.let { zoneName(it) } ?: "All")
        val fw = font.width(fLabel) + 14
        val fc = filterZone?.let { opaque(color(it)) } ?: SUB
        button(g, fx, y - 2, fw, 18, fLabel, fc, mx, my, filled = false) { b ->
            val all = listOf<String?>(null) + zones
            val i = all.indexOf(filterZone)
            filterZone = all[Math.floorMod(i + if (b == 1) -1 else 1, all.size)]
            filtersChanged()
        }
        if (over(mx, my, fx, y - 2, fw, 18)) tooltip = listOf(Component.literal("Left click: next biome"), Component.literal("Right click: previous"))
        val mx2 = fx + fw + 6
        button(g, mx2, y - 2, 64, 18, if (mineOnly) "✔ Mine" else "Mine only", if (mineOnly) ACCENT else SUB, mx, my, filled = false) { mineOnly = !mineOnly; filtersChanged() }
        // the type filter: click to pick a type, right click to show all again
        val tx = mx2 + 70
        val tLabel = "Type: " + (filterType?.let { typeName(it) } ?: "All")
        val tw = font.width(tLabel) + 14
        button(g, tx, y - 2, tw, 18, tLabel, filterType?.let { opaque(typeColor(it)) } ?: SUB, mx, my, filled = false) { b ->
            if (b == 1) { filterType = null; filtersChanged() } else typePicker = true
        }
        if (over(mx, my, tx, y - 2, tw, 18)) tooltip = listOf(Component.literal("Left click: pick a type"), Component.literal("Right click: all types"))
        val count = "$monsTotal Pokémon"
        text(g, count, x + w - font.width(count), y + 3, DIM)

        // grid
        val gx = x
        val gy = y + 22
        val gh = h - 26
        val cardW = 62
        val cardH = 60
        val gap = 5
        val cols = max(1, (w + gap) / (cardW + gap))
        val rows = (monsTotal + cols - 1) / cols
        val used = cols * cardW + (cols - 1) * gap
        val ox = gx + (w - used) / 2
        maxScroll = max(0.0, rows * (cardH + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        if (!monsLoaded) {
            g.drawCenteredString(font, "Loading…", x + w / 2, gy + 40, DIM)
            return
        }
        if (monsTotal == 0) g.drawCenteredString(font, "No Pokémon match.", x + w / 2, gy + 40, DIM)
        g.enableScissor(gx - 2, gy, gx + w + 2, gy + gh)
        val first = (scroll / (cardH + gap)).toInt() * cols
        var missing = false
        for (i in first until monsTotal) {
            val row = i / cols
            val col = i % cols
            val cx = ox + col * (cardW + gap)
            val cy = gy + row * (cardH + gap) - scroll.toInt()
            if (cy > gy + gh) break
            // (a stretch not here yet: an empty card while it's asked for)
            val m = mons.getOrNull(i - monsFrom)?.takeIf { i >= monsFrom }
            if (m == null) {
                box(g, cx, cy, cardW, cardH, CARD)
                missing = true
                continue
            }
            val hover = over(mx, my, cx, cy, cardW, cardH) && my in gy until gy + gh
            box(g, cx, cy, cardW, cardH, if (hover) CARD_HOVER else CARD)
            g.fill(cx + 1, cy, cx + cardW - 1, cy + 2, opaque(color(m.home)))
            item(g, monStack(m.species, m.aspects), cx + cardW / 2 - 16, cy + 5, 2f)
            g.drawCenteredString(font, fit(m.name, cardW - 4), cx + cardW / 2, cy + 39, if (m.out) TEXT else SUB)
            g.drawCenteredString(font, "Lv.${m.level}", cx + cardW / 2, cy + 49, DIM)
            if (m.out) box(g, cx + cardW - 8, cy + 5, 4, 4, GOOD)
            if (m.shiny) text(g, "★", cx + 4, cy + 4, GOLD)
            if (hover) {
                tooltip = listOf(
                    Component.literal(m.name).withColor(TEXT),
                    Component.literal("Lv.${m.level} · " + m.types.joinToString("/") { typeLabel(it) }).withColor(SUB),
                    Component.literal("Home: ${zoneName(m.home)}").withColor(opaque(color(m.home))),
                    Component.literal("Trainer: ${m.trainer}").withColor(SUB),
                    Component.literal(if (m.out) "Out in the valley" else "Resting somewhere").withColor(if (m.out) GOOD else DIM),
                )
                val id = m.id
                hit(cx, cy, cardW, cardH) { openResident(id) }
            }
        }
        g.disableScissor()
        scrollbar(g, gx + w + 4, gy, gh)
        // scrolled past what's here: ask for the stretch around where the list is now
        if (missing) askList(maxOf(0, first - cols * 3))
    }

    private fun scrollbar(g: GuiGraphics, x: Int, y: Int, h: Int) {
        if (maxScroll <= 0) return
        g.fill(x, y, x + 3, y + h, TRACK)
        val thumb = max(16, (h * h / (h + maxScroll)).toInt())
        val ty = y + ((h - thumb) * (scroll / maxScroll)).toInt()
        g.fill(x, ty, x + 3, ty + thumb, mix(ACCENT, 0x000000, 0.2f))
    }

    // ------------------------------------------------------------------ one resident

    private fun residentPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int, partialTick: Float) {
        button(g, x, y - 2, 74, 16, "‹ Residents", SUB, mx, my, filled = false) { goTab("residents") }
        val d = detail
        if (d == null) {
            val gone = data.getString("gone")
            g.drawCenteredString(font, if (gone.isNotEmpty() && data.getString("page") == "resident") gone else "Loading…", x + w / 2, y + 60, DIM)
            return
        }
        val id = d.getUUID("u")
        val types = d.getList("t", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) } }
        val main = opaque(color(types.firstOrNull() ?: "normal"))
        val mine = d.getBoolean("m")

        // portrait
        val pX = x
        val pY = y + 20
        val pS = 112
        g.fillGradient(pX, pY, pX + pS, pY + pS, mix(main, 0x182029, 0.55f), mix(main, 0x182029, 0.85f))
        outline(g, pX, pY, pS, pS, mix(main, 0x000000, 0.2f))
        val species = ResourceLocation.tryParse(d.getString("sp"))
        val aspects = d.getList("as", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) }.toSet() }
        val key = "${d.getString("sp")}|${aspects.sorted()}|$pX|$pY"
        if (portraitKey != key) {
            portraitKey = key
            portrait = species?.let { PokemonSpecies.getByIdentifier(it) }?.let { sp ->
                runCatching { ModelWidget(pX, pY, pS, pS, RenderablePokemon(sp, aspects, ItemStack.EMPTY), 3.4f, 325f, -17.0, true, true, 15) }.getOrNull()
            }
        }
        portrait?.render(g, mx, my, partialTick) ?: species?.let { item(g, monStack(it, aspects), pX + pS / 2 - 24, pY + pS / 2 - 24, 3f) }
        if (d.getBoolean("sh")) text(g, "★ Shiny", pX + 5, pY + 4, GOLD, true)
        if (d.getBoolean("o")) { box(g, pX + pS - 46, pY + 4, 42, 11, 0xCC1E5A36.toInt()); text(g, "Out now", pX + pS - 43, pY + 6, GOOD) }

        // name
        val gender = when (d.getString("gender")) { "MALE" -> " ♂" to BLUE; "FEMALE" -> " ♀" to 0xFFF07CB4.toInt(); else -> "" to SUB }
        val name = fit(d.getString("n"), pS - 12)
        text(g, name, pX, pY + pS + 6, TEXT, true)
        text(g, gender.first, pX + font.width(name), pY + pS + 6, gender.second, true)
        text(g, fit("#${d.getInt("dex")} ${d.getString("species")} · Lv.${d.getInt("l")}", pS + 4), pX, pY + pS + 17, SUB)

        // right column
        val rx = x + pS + 12
        val rw = w - pS - 12
        var tx = rx
        for (t in types) tx += pill(g, typeLabel(t), color(t), tx, y) + 4
        // sub tabs
        val subTabs = listOf("info" to "Info", "stats" to "Stats", "moves" to "Moves")
        var sx = rx
        val sy = y + 18
        for ((k, label) in subTabs) {
            val bw = font.width(label) + 14
            val active = detailTab == k
            val hover = over(mx, my, sx, sy, bw, 15)
            box(g, sx, sy, bw, 15, if (active) 0xFF2B4150.toInt() else if (hover) CARD_HOVER else CARD)
            if (active) g.fill(sx + 2, sy + 13, sx + bw - 2, sy + 15, ACCENT)
            g.drawCenteredString(font, label, sx + bw / 2, sy + 4, if (active) TEXT else SUB)
            hit(sx, sy, bw, 15) { detailTab = k }
            sx += bw + 4
        }
        val by = sy + 22
        val bottom = y + h - (if (mine) 52 else 28)
        box(g, rx, by, rw, bottom - by, CARD)
        when (detailTab) {
            "stats" -> statsTab(g, d, rx + 8, by + 7, rw - 16)
            "moves" -> movesTab(g, d, rx + 8, by + 7, rw - 16)
            else -> infoTab(g, d, rx + 8, by + 7, rw - 16, bottom - by - 10, mx, my)
        }

        // actions
        val ay = y + h - (if (mine) 48 else 24)
        val actions = listOf("pet" to ("Pet" to 0xFFD05C8A.toInt()), "play" to ("Play" to 0xFFD9A23B.toInt()), "feed" to ("Feed" to 0xFF4FA35E.toInt()), "call" to ("Call" to 0xFF3F8FCF.toInt()))
        val whistle = data.getBoolean("hasWhistle")
        val count = actions.size + if (mine) 1 else 0
        val aw = (rw - (count - 1) * 4) / count
        actions.forEachIndexed { i, (act, lc) ->
            val ok = act != "call" || whistle
            button(g, rx + i * (aw + 4), ay, aw, 20, lc.first, lc.second, mx, my, enabled = ok, why = "You need a Whistle in your inventory") { send("act", act, id, page = "resident") }
        }
        if (mine) {
            button(g, rx + actions.size * (aw + 4), ay, aw, 20, "Take", 0xFF8A6AD8.toInt(), mx, my) { send("act", "take", id, page = "resident") }
            // home chooser
            val homes = d.getList("homes", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) } }
            val hy = ay + 25
            text(g, "Home:", x, hy + 6, SUB)
            var hx = x + font.width("Home:") + 6
            val current = d.getString("h")
            if (homes.size <= 1) text(g, zoneName(current) + " (its only home)", hx, hy + 6, DIM)
            else {
                // where it lives now, and a button that opens the list of every habitat it could live in
                val label = zoneName(current)
                val cw2 = font.width(label) + 12
                val c = opaque(color(current))
                box(g, hx, hy + 1, cw2, 18, c)
                g.drawCenteredString(font, label, hx + cw2 / 2, hy + 6, 0xFFFFFFFF.toInt())
                hx += cw2 + 6
                val pick = "Choose home…  (${homes.size} options)"
                button(g, hx, hy + 1, min(font.width(pick) + 16, x + w - hx), 18, pick, ACCENT, mx, my, filled = false) {
                    homeFor = id
                    homeForName = d.getString("n").ifEmpty { "this Pokémon" }
                    homeForOptions = homes
                    homeForNow = current
                }
            }
        }
    }

    private fun infoTab(g: GuiGraphics, d: CompoundTag, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        var ly = y
        fun row(label: String, value: String, c: Int = TEXT) {
            text(g, label, x, ly, SUB)
            text(g, fit(value, w - 70), x + 70, ly, c)
            ly += 12
        }
        val home = d.getString("h")
        row("Home", zoneName(home), opaque(color(home)))
        row("Trainer", d.getString("tr"))
        row("Status", if (d.getBoolean("o")) "Out in the valley" else "Resting somewhere", if (d.getBoolean("o")) GOOD else DIM)
        val pers = d.getString("pers")
        row("Nature", Component.translatable(d.getString("nature")).string + if (pers.isNotEmpty()) " · $pers" else "")
        if (pers.isNotEmpty() && over(mx, my, x, ly - 12, w, 12)) tooltip = listOf(Component.literal(pers), Component.literal(d.getString("persB")).withStyle(net.minecraft.ChatFormatting.GRAY))
        row("Ability", Component.translatable("cobblemon.ability.${d.getString("ability")}").string)
        row("Favourite", if (d.contains("fav")) d.getString("fav") else "hasn't picked a spot yet", if (d.contains("fav")) 0xFFE8C46A.toInt() else DIM)
        if (d.contains("title")) row("Title", d.getString("title"), GOLD)
        if (d.contains("trips")) d.getInt("trips").let { n -> row("Expeditions", "$n trip${if (n == 1) "" else "s"}" + if (n >= 5) "  ·  ★ Explorer" else "  (Explorer at 5)", if (n >= 5) GOLD else 0xFF7CC8F0.toInt()) }
        if (d.contains("ribbons")) row("Ribbons", "★".repeat(d.getInt("ribbons").coerceAtMost(10)) + "  (${d.getInt("ribbons")} contest${if (d.getInt("ribbons") == 1) "" else "s"} won)", GOLD)
        if (d.contains("req")) {
            row("Wants", d.getString("req"), 0xFFF07CB4.toInt())
            if (over(mx, my, x, ly - 12, w, 12)) tooltip = listOf(Component.literal(d.getString("req")), Component.literal(d.getString("reqHow")).withStyle(net.minecraft.ChatFormatting.GRAY))
        }
        if (d.contains("evo")) {
            val stay = d.getBoolean("stay")
            text(g, "Evolution", x, ly, SUB)
            val label = if (d.getBoolean("m")) (if (stay) "Let it evolve" else "Stay as it is") else ""
            val bw = if (label.isEmpty()) 0 else font.width(label) + 10
            text(g, fit(d.getString("evo"), w - 74 - bw), x + 70, ly, if (d.getString("evo").startsWith("About")) GOOD else TEXT)
            if (label.isNotEmpty()) {
                val bx = x + w - bw
                val hover = over(mx, my, bx, ly - 2, bw, 12)
                box(g, bx, ly - 2, bw, 12, if (hover) CARD_HOVER else 0xFF2B4150.toInt())
                g.drawCenteredString(font, label, bx + bw / 2, ly, if (stay) GOOD else SUB)
                if (hover) tooltip = listOf(Component.literal(if (stay) "It can evolve on its own in the sanctuary again" else "Keep it from evolving on its own in the sanctuary"))
                hit(bx, ly - 2, bw, 12) { send("act", "stay", d.getUUID("u"), page = "resident") }
            }
            ly += 12
            // every way it can evolve, with a button for the ones it can do now (a stone, a trade)
            val evos = d.getList("evos", Tag.TAG_COMPOUND.toInt())
            for (k in 0 until minOf(evos.size, 4)) {
                val e = evos.getCompound(k)
                val btn = e.getString("btn")
                val bw2 = if (btn.isEmpty()) 0 else font.width(btn) + 10
                text(g, fit("→ ${e.getString("name")}: ${e.getString("how")}", w - 74 - bw2), x + 70, ly, if (btn.isNotEmpty()) GOOD else SUB)
                if (over(mx, my, x + 70, ly - 2, w - 70 - bw2, 12)) tooltip = listOf(Component.literal(e.getString("name")), Component.literal(e.getString("how")).withStyle(net.minecraft.ChatFormatting.GRAY))
                if (btn.isNotEmpty()) {
                    val bx2 = x + w - bw2
                    val hover = over(mx, my, bx2, ly - 2, bw2, 12)
                    box(g, bx2, ly - 2, bw2, 12, if (hover) CARD_HOVER else 0xFF2B4150.toInt())
                    g.drawCenteredString(font, btn, bx2 + bw2 / 2, ly, GOOD)
                    val act = e.getString("act")
                    val arg = e.getString("arg")
                    hit(bx2, ly - 2, bw2, 12) { send("act", act, d.getUUID("u"), extra = arg, page = "resident") }
                }
                ly += 12
            }
        }
        val f = d.getInt("friendship")
        text(g, "Friendship", x, ly, SUB)
        bar(g, x + 70, ly + 1, w - 110, 6, f / 255.0, 0xFFF07CB4.toInt())
        text(g, "$f", x + w - 34, ly, TEXT)
        ly += 14
        fun people(key: String, label: String, c: Int) {
            val l = d.getList(key, Tag.TAG_COMPOUND.toInt())
            val names = (0 until l.size).map { l.getCompound(it).getString("n") }
            text(g, label, x, ly, SUB)
            val lines = font.split(FormattedText.of(if (names.isEmpty()) "none yet" else names.joinToString(", ")), w - 70)
            for (line in lines.take(2)) { g.drawString(font, line, x + 70, ly, if (names.isEmpty()) DIM else c, false); ly += 10 }
            ly += 2
        }
        fun family(key: String, label: String) {
            val l = d.getList(key, Tag.TAG_STRING.toInt())
            if (l.isEmpty()) return
            text(g, label, x, ly, SUB)
            text(g, fit((0 until l.size).joinToString(" & ".takeIf { key == "parents" } ?: ", ") { l.getString(it) }, w - 70), x + 70, ly, GOLD)
            ly += 12
        }
        family("parents", "Parents")
        family("children", "Children")
        if (ly < y + h - 20) people("friends", "Friends", 0xFFF07CB4.toInt())
        if (ly < y + h - 10) people("rivals", "Rivals", BAD)
    }

    private fun statsTab(g: GuiGraphics, d: CompoundTag, x: Int, y: Int, w: Int) {
        val stats = d.getIntArray("stats")
        val ivs = d.getIntArray("ivs")
        val evs = d.getIntArray("evs")
        val top = max(100, stats.maxOrNull() ?: 100)
        val colors = listOf(0xFF5DC96B, 0xFFF0A23C, 0xFFE8D43F, 0xFF5EA8F0, 0xFF8C6BE0, 0xFFF0659A).map { it.toInt() }
        text(g, "IV", x + w - 44, y, DIM)
        text(g, "EV", x + w - 20, y, DIM)
        for (i in 0 until min(6, stats.size)) {
            val ly = y + 12 + i * 13
            text(g, STAT_NAMES[i], x, ly, SUB)
            text(g, "${stats[i]}", x + 50, ly, TEXT)
            bar(g, x + 74, ly + 1, w - 126, 6, stats[i].toDouble() / top, colors[i])
            text(g, "${ivs.getOrElse(i) { 0 }}", x + w - 44, ly, if (ivs.getOrElse(i) { 0 } == 31) GOLD else TEXT)
            text(g, "${evs.getOrElse(i) { 0 }}", x + w - 20, ly, TEXT)
        }
        val total = stats.sum()
        text(g, "Total $total · HP ${d.getInt("hpNow")}/${stats.firstOrNull() ?: 0}", x, y + 12 + 6 * 13 + 2, DIM)
    }

    private fun movesTab(g: GuiGraphics, d: CompoundTag, x: Int, y: Int, w: Int) {
        val moves = d.getList("moves", Tag.TAG_COMPOUND.toInt())
        if (moves.isEmpty()) { text(g, "No moves.", x, y, DIM); return }
        val mw = (w - 6) / 2
        for (i in 0 until min(4, moves.size)) {
            val m = moves.getCompound(i)
            val mx = x + (i % 2) * (mw + 6)
            val my = y + (i / 2) * 36
            val c = opaque(color(m.getString("t")))
            box(g, mx, my, mw, 32, mix(c, 0x182029, 0.55f))
            g.fill(mx + 1, my, mx + 3, my + 32, c)
            text(g, fit(m.getString("n"), mw - 12), mx + 8, my + 5, TEXT, true)
            text(g, typeLabel(m.getString("t")), mx + 8, my + 18, mix(c, 0xFFFFFF, 0.45f))
            val pp = "PP ${m.getInt("pp")}/${m.getInt("max")}"
            text(g, pp, mx + mw - font.width(pp) - 6, my + 18, SUB)
        }
    }

    // ------------------------------------------------------------------ biomes

    /** The region: a little map of the valley and its habitats, and who lives in each. */
    private fun biomesPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        // the map: areas in their colours, hills shaded, water, routes, pockets, caves and landmarks
        val map = data.getByteArray("map")
        val heights = data.getByteArray("mapH")
        val flags = data.getByteArray("mapF")
        val n = kotlin.math.sqrt(map.size.toDouble()).toInt().coerceAtLeast(1)
        val size = min(h - 6, w / 2 - 6).coerceAtLeast(96)
        val cell = size.toDouble() / n
        val mx0 = x
        val my0 = y + 2
        box(g, mx0 - 2, my0 - 2, size + 4, size + 4, 0xFF5E5A50.toInt())
        val colorOf = HashMap<Int, Int>()
        biomes.forEach { b -> val k = mapKey(b.zone); if (k > 0) colorOf[k] = opaque(b.color) }
        fun heightAt(i: Int, j: Int): Int = if (heights.size == n * n) (heights[j.coerceIn(0, n - 1) * n + i.coerceIn(0, n - 1)].toInt() and 0xFF) - 40 else 0
        fun flagAt(i: Int, j: Int): Int = if (flags.size == n * n) flags[j * n + i].toInt() and 0xFF else 0
        var hovered = -1
        var hoveredFlags = 0
        if (map.size == n * n) for (j in 0 until n) for (i in 0 until n) {
            val v = map[j * n + i].toInt()
            if (v == 0) continue
            val f = flagAt(i, j)
            val hh = heightAt(i, j)
            var c = when {
                f and 8 != 0 -> mix(0xFF3F86C8.toInt(), 0x123A6A, (((-hh) - 1) / 16f).coerceIn(0f, 0.7f)) // water, darker where deep
                v == 1 -> 0xFF8DB86A.toInt()
                else -> mix(colorOf[v] ?: 0xFFAAAAAA.toInt(), 0x6E9A4E, 0.35f)
            }
            if (f and 8 == 0) {
                // snow on the high tops
                if (hh > 30 && v != 6) c = mix(c, 0xF4F6FA, ((hh - 30) / 14f).coerceIn(0f, 0.75f))
                // light from the top left: slopes facing it are brighter, the others darker
                val slope = (heightAt(i - 1, j - 1) - hh).coerceIn(-8, 8)
                c = if (slope < 0) mix(c, 0xFFFFFF, (-slope * 0.07f).coerceAtMost(0.35f)) else mix(c, 0x10140C, (slope * 0.09f).coerceAtMost(0.5f))
                if (f and 1 != 0) c = mix(c, 0xD8B878, 0.75f) // a route
                if (f and 4 != 0 && (i + j) % 2 == 0) c = mix(c, 0x1A1410, 0.35f) // a cave underneath
                if (f and 16 != 0) c = 0xFF2A221C.toInt() // a cave mouth
            }
            val x0 = mx0 + (i * cell).toInt()
            val y0 = my0 + (j * cell).toInt()
            val x1 = mx0 + ((i + 1) * cell).toInt()
            val y1 = my0 + ((j + 1) * cell).toInt()
            g.fill(x0, y0, x1, y1, c or 0xFF000000.toInt())
            if (mx in x0 until x1 && my in y0 until y1) { hovered = v; hoveredFlags = f }
        }
        // the town
        val cx = mx0 + size / 2
        val cy = my0 + size / 2
        box(g, cx - 4, cy - 4, 8, 8, 0xFF5E5A50.toInt())
        box(g, cx - 3, cy - 3, 6, 6, 0xFFF4EEDC.toInt())
        if (over(mx, my, cx - 4, cy - 4, 8, 8)) tooltip = listOf(Component.literal("The town").withColor(TEXT), Component.literal("Where the routes start").withColor(SUB))
        // markers
        val marks = data.getList("mapMarks", 10)
        for (k in 0 until marks.size) {
            val m = marks.getCompound(k)
            val px = cx + (m.getFloat("x") * size / 2).toInt()
            val pz = cy + (m.getFloat("z") * size / 2).toInt()
            val zc = colorOf[mapKey(m.getString("zone"))] ?: 0xFFFFFFFF.toInt()
            when (m.getString("k")) {
                "landmark" -> { box(g, px - 3, pz - 3, 7, 7, 0xFF1A1A1A.toInt()); box(g, px - 2, pz - 2, 5, 5, 0xFFFFFFFF.toInt()); box(g, px - 1, pz - 1, 3, 3, zc) }
                "guardian" -> { // a gold diamond with the habitat's colour inside
                    for (i in 0..4) g.fill(px - i, pz - 4 + i, px + i + 1, pz - 3 + i, 0xFF1A1A1A.toInt())
                    for (i in 0..4) g.fill(px - i, pz + 4 - i, px + i + 1, pz + 5 - i, 0xFF1A1A1A.toInt())
                    for (i in 0..3) g.fill(px - i, pz - 3 + i, px + i + 1, pz - 2 + i, GOLD)
                    for (i in 0..3) g.fill(px - i, pz + 3 - i, px + i + 1, pz + 4 - i, GOLD)
                    box(g, px - 1, pz - 1, 3, 3, zc)
                }
                "peak" -> { // a little mountain
                    g.fill(px, pz - 3, px + 1, pz - 2, 0xFFFFFFFF.toInt())
                    g.fill(px - 1, pz - 2, px + 2, pz - 1, 0xFFFFFFFF.toInt())
                    g.fill(px - 2, pz - 1, px + 3, pz, 0xFF1A1A1A.toInt())
                    g.fill(px - 3, pz, px + 4, pz + 1, 0xFF1A1A1A.toInt())
                }
                "cave" -> { box(g, px - 2, pz - 2, 5, 5, 0xFFB8A890.toInt()); box(g, px - 1, pz - 1, 3, 3, 0xFF0E0A08.toInt()) }
                "hall" -> { g.fill(px - 3, pz, px + 4, pz + 1, 0xFF0E0A08.toInt()); g.fill(px, pz - 3, px + 1, pz + 4, 0xFF0E0A08.toInt()) }
            }
            if (tooltip == null && over(mx, my, px - 4, pz - 4, 9, 9)) tooltip = listOf(Component.literal(m.getString("n")).withColor(zc))
        }
        // where you are, and which way you're looking
        if (data.contains("mapX")) {
            val ux = cx + (data.getFloat("mapX") * size / 2).toInt()
            val uz = cy + (data.getFloat("mapZ") * size / 2).toInt()
            val yaw = Math.toRadians(data.getFloat("mapYaw").toDouble())
            val fx = -kotlin.math.sin(yaw)
            val fz = kotlin.math.cos(yaw)
            for (step in 2..6) {
                val ax = ux + (fx * step).toInt()
                val az = uz + (fz * step).toInt()
                g.fill(ax - 1, az - 1, ax + 1, az + 1, 0xFFFFFFFF.toInt())
            }
            box(g, ux - 3, uz - 3, 7, 7, 0xFFFFFFFF.toInt())
            box(g, ux - 2, uz - 2, 5, 5, 0xFFE24B4A.toInt())
            if (over(mx, my, ux - 4, uz - 4, 9, 9)) tooltip = listOf(Component.literal("You are here"))
        }
        if (hovered >= 1 && tooltip == null) {
            val b = biomes.firstOrNull { mapKey(it.zone) == hovered }
            if (b != null) {
                val lines = mutableListOf<Component>(
                    Component.literal(if (hoveredFlags and 2 != 0) "${b.name} (a little pocket)" else b.name).withColor(opaque(b.color)),
                    Component.literal("${b.count} Pokémon").withColor(SUB),
                )
                if (hoveredFlags and 1 != 0) lines += Component.literal("A route").withColor(SUB)
                if (hoveredFlags and 4 != 0) lines += Component.literal("Caves underneath").withColor(SUB)
                if (hoveredFlags and 16 != 0) lines += Component.literal("A cave entrance").withColor(SUB)
                tooltip = lines
            }
        }
        // the habitats
        val lx = mx0 + size + 12
        val lw = x + w - lx
        text(g, "Habitats", lx, y, SUB)
        val th = 30
        val gap = 4
        val gy = y + 14
        val gh = h - 16
        maxScroll = max(0.0, biomes.size * (th + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(lx - 2, gy, lx + lw + 2, gy + gh)
        biomes.forEachIndexed { i, b ->
            val by = gy + i * (th + gap) - scroll.toInt()
            if (by > gy + gh || by + th < gy) return@forEachIndexed
            val c = opaque(b.color)
            // a custom type without a habitat of its own: its Pokémon live elsewhere (shown, not asleep)
            val homeless = b.custom && !b.placed
            val shown = b.awake || (homeless && b.count > 0)
            val live = b.awake && b.placed
            val hover = shown && over(mx, my, lx, by, lw, th) && my in gy until gy + gh
            box(g, lx, by, lw, th, if (!shown) 0xFF1A222B.toInt() else if (hover) CARD_HOVER else CARD)
            g.fill(lx + 1, by + 1, lx + 4, by + th - 1, if (shown) c else mix(c, 0x1A222B, 0.7f))
            text(g, fit(b.name, lw - 70), lx + 9, by + 5, if (shown) TEXT else DIM)
            val right = when {
                !shown -> ""
                else -> "${b.count} Pokémon"
            }
            if (right.isNotEmpty()) text(g, right, lx + lw - 6 - font.width(right), by + 5, SUB)
            val sub = when {
                homeless && b.pocket -> (if (b.sub) "Part of the ${b.pocketIn}" else "A pocket in the ${b.pocketIn}") + (b.looks.getOrNull(b.look)?.let { " · ${it.first}" } ?: "") +
                    if (b.noRoom) " · own habitat on the next Reshape" else " · own habitat at ${b.need}"
                homeless && b.townOnly -> "Live in the ${b.homeName}, where anyone fits"
                homeless -> "Live in the ${b.homeName}" + when {
                    b.choice != "own" || !b.ownOn -> ""
                    b.noRoom -> " · own habitat on the next Reshape"
                    b.typeCount < b.pocketNeed -> " · a pocket at ${b.pocketNeed}"
                    b.typeCount < b.subNeed -> " · a sub-biome at ${b.subNeed}"
                    else -> " · own habitat at ${b.need}"
                }
                !b.awake -> "Asleep: needs a ${b.types.ifEmpty { "Normal" }} type"
                !live -> "No room yet: Reshape to add it to the map"
                else -> b.types.ifEmpty { "Normal" } + (b.looks.getOrNull(b.look)?.let { " · ${it.first}" } ?: "")
            }
            val owner = data.getBoolean("isOwner")
            val lookRoom = (if (owner && b.looks.size > 1) font.width("Look") + 20 else 0) + (if (owner && b.custom && !b.townOnly) font.width("Home") + 18 else 0)
            text(g, fit(sub, lw - 14 - lookRoom), lx + 9, by + 17, if (homeless) SUB else if (!live && b.awake) GOLD else DIM)
            if (hover) {
                tooltip = listOf(Component.literal(b.name).withColor(c), Component.literal("${b.count} Pokémon live here").withColor(SUB), Component.literal("Click to see them").withColor(SUB))
                hit(lx, by, lw, th) { filterZone = b.zone; if (tab == "residents") filtersChanged() else { monsAsked = ""; goTab("residents") } }
            }
            // the owner can pick the habitat's look
            if (data.getBoolean("isOwner") && b.looks.size > 1 && my in gy until gy + gh) {
                val label = "Look"
                val bw = font.width(label) + 12
                val bx = lx + lw - 6 - bw
                if (over(mx, my, bx, by + 15, bw, 12)) tooltip = listOf(Component.literal("Look: ${b.looks.getOrNull(b.look)?.first ?: "?"}").withColor(c), Component.literal("Click to choose another").withColor(SUB))
                button(g, bx, by + 15, bw, 12, label, ACCENT, mx, my, filled = false) { lookZone = b.zone; lookPick = b.look }
            }
            // and where a custom type's Pokémon live
            if (owner && b.custom && !b.townOnly && my in gy until gy + gh) {
                val label = "Home"
                val bw = font.width(label) + 12
                val bx = lx + lw - 6 - bw - (if (b.looks.size > 1) font.width("Look") + 18 else 0)
                if (over(mx, my, bx, by + 15, bw, 12)) tooltip = listOf(Component.literal("${b.types} Pokémon live in the ${b.homeName}").withColor(c), Component.literal("Click to choose where").withColor(SUB))
                button(g, bx, by + 15, bw, 12, label, ACCENT, mx, my, filled = false) { homeType = b.zone; homePick = b.choice }
            }
        }
        g.disableScissor()
        scrollbar(g, lx + lw + 4, gy, gh)
    }

    // ------------------------------------------------------------------ journal (events)

    private fun eventsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        text(g, "What's been happening in the valley", x, y, SUB)
        val events = data.getList("events", Tag.TAG_STRING.toInt())
        val gy = y + 16
        val gh = h - 18
        if (events.isEmpty()) { g.drawCenteredString(font, "Nothing has happened yet. Give it time!", x + w / 2, gy + 40, DIM); return }
        val lines = (0 until events.size).map { font.split(FormattedText.of(events.getString(it)), w - 22) }
        val total = lines.sumOf { it.size * 10 + 8 }
        maxScroll = max(0.0, total - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        var ly = gy - scroll.toInt()
        g.fill(x + 4, gy, x + 5, gy + gh, PANEL_EDGE)
        lines.forEachIndexed { i, ls ->
            box(g, x + 2, ly + 1, 5, 5, if (i == 0) ACCENT else mix(ACCENT, 0x182029, 0.5f))
            for (line in ls) { g.drawString(font, line, x + 14, ly, if (i == 0) TEXT else SUB, false); ly += 10 }
            ly += 8
        }
        g.disableScissor()
        scrollbar(g, x + w + 4, gy, gh)
    }

    // ------------------------------------------------------------------ goals

    private fun rewardIcon(id: String): ItemStack = when (id) {
        "#cobblemon:mints" -> itemOf("cobblemon:adamant_mint", Items.GREEN_DYE)
        "#cobblemon:apricorns" -> itemOf("cobblemon:red_apricorn", Items.APPLE)
        else -> itemOf(id.removePrefix("#"), Items.PAPER)
    }

    private fun goalsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val goals = data.getList("goals", Tag.TAG_COMPOUND.toInt())
        val list = (0 until goals.size).map { goals.getCompound(it) }
            // ready to collect first, then the ones still going, then the finished ones
            .sortedBy { if (it.getBoolean("reached") && !it.getBoolean("claimed")) 0 else if (!it.getBoolean("reached")) 1 else 2 }
        val done = list.count { it.getBoolean("reached") }
        text(g, "Goals for your sanctuary", x, y, SUB)
        val count = "$done/${list.size} reached"
        text(g, count, x + w - font.width(count), y, GOLD)
        val gy = y + 16
        val gh = h - 18
        val cardH = 40
        val gap = 5
        val heart = data.getCompound("heart")
        val reqs = data.getList("requests", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }
        val heartH = 62
        val reqH = 34
        val visitorsN = data.getList("visitorsBoard", Tag.TAG_COMPOUND.toInt()).size
        val top = heartH + gap + 14 + (if (reqs.isEmpty()) 16 else reqs.size * (reqH + 4)) + 18 + (if (visitorsN > 0) 14 + visitorsN * (reqH + 4) else 0)
        maxScroll = max(0.0, top + list.size * (cardH + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        var cy = gy - scroll.toInt()
        // the Valley Heart
        run {
            val pink = 0xFFF07CB4.toInt()
            val lv = heart.getInt("level")
            val pts = heart.getInt("points")
            val prev = heart.getInt("prev")
            val next = heart.getInt("next")
            val levels = heart.getList("levels", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it).split('|', limit = 3) } }
            box(g, x, cy, w, heartH, mix(pink, 0x182029, 0.86f))
            g.fill(x, cy, x + 2, cy + heartH, pink)
            text(g, "♥ Valley Heart", x + 8, cy + 5, pink, true)
            text(g, "Level $lv", x + 8 + font.width("♥ Valley Heart") + 8, cy + 5, TEXT, true)
            // the Golden Heart: name the valley's guardian
            if (heart.contains("guardian")) {
                val gname = heart.getString("guardian")
                val label = if (gname.isEmpty()) "Choose a guardian" else "Guardian: $gname"
                val bw = font.width(label) + 12
                val bx = x + w - bw - 6
                val hover = over(mx, my, bx, cy + 3, bw, 12) && my in gy until gy + gh
                box(g, bx, cy + 3, bw, 12, if (hover) CARD_HOVER else 0xFF3A2F18.toInt())
                g.drawCenteredString(font, label, bx + bw / 2, cy + 5, GOLD)
                if (hover) tooltip = listOf(Component.literal("Name one of your legendaries the valley's guardian"), Component.literal("It watches from the Guardian's Rise at dawn and dusk").withStyle(net.minecraft.ChatFormatting.GRAY))
                if (cy >= gy) hit(bx, cy + 3, bw, 12) { goTab("guardian") }
            }
            val frac = if (next < 0) 1.0 else (pts - prev).toDouble() / (next - prev).coerceAtLeast(1)
            bar(g, x + 8, cy + 18, w - 90, 5, frac, pink)
            text(g, if (next < 0) "$pts points" else "$pts/$next", x + w - 76, cy + 16, if (next < 0) GOLD else SUB)
            val upcoming = levels.getOrNull(lv)
            text(g, fit(if (upcoming != null) "Next: ${upcoming[1]} — ${upcoming.getOrElse(2) { "" }}" else "Everything is unlocked. Thank you for looking after them!", w - 16), x + 8, cy + 29, SUB)
            // one pip per level: what it unlocks
            var px = x + 8
            for ((i, l) in levels.withIndex()) {
                val got = i < lv
                val label = "${i + 1}"
                val bw = font.width(label) + if (levels.size > 12) 6 else 10
                box(g, px, cy + 42, bw, 14, if (got) pink else CARD)
                g.drawCenteredString(font, label, px + bw / 2, cy + 45, if (got) 0xFFFFFFFF.toInt() else DIM)
                if (over(mx, my, px, cy + 42, bw, 14) && my in gy until gy + gh) tooltip = listOf(
                    Component.literal("Level ${i + 1}: ${l.getOrElse(1) { "" }}").withStyle(if (got) net.minecraft.ChatFormatting.LIGHT_PURPLE else net.minecraft.ChatFormatting.WHITE),
                    Component.literal(l.getOrElse(2) { "" }).withStyle(net.minecraft.ChatFormatting.GRAY),
                    Component.literal(if (got) "Unlocked" else "At ${l[0]} points").withStyle(if (got) net.minecraft.ChatFormatting.GREEN else net.minecraft.ChatFormatting.DARK_GRAY))
                px += bw + 4
            }
            if (px + 6 + font.width("Do residents' requests to grow it.") < x + w) text(g, "Do residents' requests to grow it.", px + 6, cy + 45, DIM)
            cy += heartH + gap
        }
        // the residents' requests
        text(g, "Requests", x, cy + 2, SUB)
        cy += 14
        if (reqs.isEmpty()) {
            text(g, "Nobody is asking for anything right now. Check back in a while!", x + 8, cy + 2, DIM)
            cy += 16
        }
        for (r in reqs) {
            if (cy + reqH >= gy && cy <= gy + gh) {
                val hover = over(mx, my, x, cy, w, reqH) && my in gy until gy + gh
                box(g, x, cy, w, reqH, if (hover) CARD_HOVER else CARD)
                g.fill(x, cy, x + 2, cy + reqH, opaque(color(r.getString("zone"))))
                ResourceLocation.tryParse(r.getString("sp"))?.let { sp ->
                    val aspects = r.getList("as", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) }.toSet() }
                    item(g, monStack(sp, aspects), x + 7, cy + 9)
                }
                text(g, fit(r.getString("text"), w - 110), x + 30, cy + 6, TEXT)
                text(g, fit(r.getString("how"), w - 110), x + 30, cy + 19, SUB)
                val pts = "+${r.getInt("pts")} ♥"
                text(g, pts, x + w - 8 - font.width(pts), cy + 6, 0xFFF07CB4.toInt())
                val mins = r.getLong("left")
                val left = if (mins >= 60) "${mins / 60}h left" else "${mins}m left"
                text(g, left, x + w - 8 - font.width(left), cy + 19, DIM)
                if (hover) tooltip = listOf(Component.literal("Open its page"))
                if (cy >= gy && cy + reqH <= gy + gh) hit(x, cy, w, reqH) { openResident(r.getUUID("u")) }
            }
            cy += reqH + 4
        }
        // the visitor board
        val board = data.getList("visitorsBoard", Tag.TAG_COMPOUND.toInt())
        if (board.size > 0) {
            text(g, "Visitors", x, cy + 2, SUB)
            cy += 14
            for (k in 0 until board.size) {
                val v = board.getCompound(k)
                if (cy + reqH >= gy && cy <= gy + gh) {
                    box(g, x, cy, w, reqH, CARD)
                    g.fill(x, cy, x + 2, cy + reqH, opaque(color(v.getString("zone"))))
                    ResourceLocation.tryParse(v.getString("sp"))?.let { sp ->
                        val aspects = v.getList("as", Tag.TAG_STRING.toInt()).let { l -> (0 until l.size).map { l.getString(it) }.toSet() }
                        item(g, monStack(sp, aspects), x + 7, cy + 9)
                    }
                    val need = when (v.getString("need")) { "hungry" -> "  · Hungry: feed it a berry"; "tired" -> "  · Tired: put a bed in its habitat"; else -> "" }
                    text(g, fit("A wild ${v.getString("name")} in the ${biomes.firstOrNull { it.zone == v.getString("zone") }?.name ?: v.getString("zone")}$need", w - 110), x + 30, cy + 6, TEXT)
                    val trust = v.getInt("trust")
                    text(g, if (trust >= 60) "Trusts you: use the Journal on it to ask it to stay!" else "Trust $trust/60: pet it with the Journal, feed it berries", x + 30, cy + 19, if (trust >= 60) GOOD else SUB)
                    val mins = v.getLong("left")
                    val left = if (mins >= 60) "${mins / 60}h left" else "${mins}m left"
                    text(g, left, x + w - 8 - font.width(left), cy + 6, DIM)
                }
                cy += reqH + 4
            }
        }
        text(g, "Goals", x, cy + 4, SUB)
        // the Sanctuary Dex
        run {
            val label = "Sanctuary Dex (${data.getInt("dexCount")})"
            val bw = font.width(label) + 12
            val bx = x + w - bw
            val hover = over(mx, my, bx, cy + 1, bw, 13) && my in gy until gy + gh
            box(g, bx, cy + 1, bw, 13, if (hover) CARD_HOVER else 0xFF2B4150.toInt())
            g.drawCenteredString(font, label, bx + bw / 2, cy + 4, ACCENT)
            if (cy + 1 >= gy && cy + 14 <= gy + gh) hit(bx, cy + 1, bw, 13) { goTab("dex") }
        }
        cy += 18
        for (goal in list) {
            if (cy + cardH >= gy && cy <= gy + gh) {
                val reached = goal.getBoolean("reached")
                val claimed = goal.getBoolean("claimed")
                val ready = reached && !claimed
                box(g, x, cy, w, cardH, if (ready) mix(GOLD, 0x182029, 0.82f) else CARD)
                g.fill(x, cy, x + 2, cy + cardH, if (claimed) DIM else if (ready) GOLD else ACCENT)
                text(g, fit(goal.getString("title"), w - 150), x + 8, cy + 5, if (claimed) SUB else TEXT, true)
                text(g, fit(goal.getString("desc"), w - 150), x + 8, cy + 17, SUB)
                val have = goal.getInt("have")
                val need = goal.getInt("need").coerceAtLeast(1)
                bar(g, x + 8, cy + 30, w - 160, 4, have.toDouble() / need, if (reached) GOOD else ACCENT)
                text(g, "$have/$need", x + w - 148, cy + 28, if (reached) GOOD else DIM)
                // rewards
                val rewards = goal.getList("rewards", Tag.TAG_STRING.toInt())
                var rx = x + w - 112
                for (i in 0 until rewards.size) {
                    val (n, id) = rewards.getString(i).split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    val stack = rewardIcon(id)
                    item(g, stack, rx, cy + 4)
                    if (n != "1") text(g, n, rx + 12, cy + 13, TEXT, true)
                    if (over(mx, my, rx, cy + 4, 16, 16) && my in gy until gy + gh) tooltip = listOf(
                        Component.literal("$n× ").append(if (id.startsWith("#")) Component.literal("random ${id.substringAfterLast(':').trimEnd('s')}") else stack.hoverName))
                    rx += 22
                }
                val bx = x + w - 112
                when {
                    claimed -> text(g, "Collected", bx, cy + 26, DIM)
                    ready -> if (cy + 23 >= gy && cy + 37 <= gy + gh) button(g, bx, cy + 22, 104, 15, "Collect reward", GOLD, mx, my) { send("claim", goal.getString("id"), page = "goals") }
                    else -> text(g, "In progress", bx, cy + 26, DIM)
                }
            }
            cy += cardH + gap
        }
        g.disableScissor()
        scrollbar(g, x + w + 4, gy, gh)
    }

    // ------------------------------------------------------------------ nest registry

    private fun nestPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        text(g, if (data.getBoolean("eggsOn")) "Eggs laid by close friends. They wait in their nests until you decide." else "Eggs are turned off on this server.", x, y, SUB)
        val gy = y + 15
        val gh = h - 17
        if (eggs.isEmpty()) {
            g.drawCenteredString(font, "No eggs yet.", x + w / 2, gy + 34, DIM)
            g.drawCenteredString(font, "Residents who become very close friends sometimes start a family.", x + w / 2, gy + 48, DIM)
            return
        }
        val cardH = 74
        val gap = 5
        maxScroll = max(0.0, eggs.size * (cardH + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        eggs.forEachIndexed { i, e ->
            val cy = gy + i * (cardH + gap) - scroll.toInt()
            if (cy > gy + gh || cy + cardH < gy) return@forEachIndexed
            val zc = opaque(color(e.zone))
            box(g, x, cy, w, cardH, CARD)
            g.fill(x + 1, cy + 1, x + 3, cy + cardH - 1, zc)
            // egg (and what's inside)
            box(g, x + 8, cy + 8, 40, 40, mix(zc, 0x182029, 0.7f))
            item(g, ItemStack(ModContent.POKEMON_EGG.get()), x + 12, cy + 12, 2f)
            e.species?.let { item(g, monStack(it, e.aspects), x + 34, cy + 34, 1f) }
            text(g, if (e.age < 1) "Just laid" else "${e.age} min ago", x + 8, cy + 54, DIM)
            if (!e.nest) text(g, "no nest", x + 8, cy + 63, DIM)
            // title
            val tx = x + 56
            val gender = when (e.gender) { "MALE" -> " ♂" to BLUE; "FEMALE" -> " ♀" to 0xFFF07CB4.toInt(); else -> "" to SUB }
            text(g, e.speciesName, tx, cy + 7, TEXT, true)
            var px2 = tx + font.width(e.speciesName)
            text(g, gender.first, px2, cy + 7, gender.second, true)
            px2 += font.width(gender.first) + 4
            if (e.shiny) { text(g, "★ Shiny", px2, cy + 7, GOLD, true); px2 += font.width("★ Shiny") + 4 }
            for (t in e.types) px2 += pill(g, typeLabel(t), color(t), px2, cy + 5) + 3
            // parents
            val py2 = cy + 20
            e.mom.species?.let { item(g, monStack(it, e.mom.aspects), tx, py2 - 3) }
            text(g, e.mom.name, tx + 18, py2 + 1, SUB)
            val heartX = tx + 22 + font.width(e.mom.name)
            text(g, "♥", heartX, py2 + 1, 0xFFF07CB4.toInt())
            e.dad.species?.let { item(g, monStack(it, e.dad.aspects), heartX + 10, py2 - 3) }
            text(g, e.dad.name, heartX + 28, py2 + 1, SUB)
            // biome, nature, ability
            val iy = cy + 36
            val pw2 = pill(g, zoneName(e.zone), color(e.zone), tx, iy - 2)
            val natureText = "${Component.translatable(e.nature).string} · ${Component.translatable("cobblemon.ability.${e.ability}").string}"
            text(g, fit(natureText, w - (tx - x) - pw2 - 92), tx + pw2 + 6, iy, TEXT)
            // IVs
            val iv = e.ivs
            var ix = tx
            val names = listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")
            for (k in 0 until min(6, iv.size)) {
                val label = "${names[k]} ${iv[k]}"
                val bw = font.width(label) + 6
                if (ix + bw > x + w - 84) break
                box(g, ix, cy + 50, bw, 11, if (iv[k] == 31) 0xFF5A4A1A.toInt() else TRACK)
                text(g, label, ix + 3, cy + 52, if (iv[k] == 31) GOLD else SUB)
                ix += bw + 3
            }
            text(g, "${e.steps} steps to hatch by walking", tx, cy + 63, DIM)
            if (over(mx, my, x, cy, w - 84, cardH) && my in gy until gy + gh) {
                tooltip = listOf(Component.literal(e.speciesName).withColor(TEXT)) +
                    e.moves.map { Component.literal("• $it").withColor(SUB) } +
                    listOf(Component.literal("Trainer: ${e.owner}").withColor(DIM))
            }
            // buttons
            val bx = x + w - 80
            if (e.mine) {
                val id = e.id
                button(g, bx, cy + 7, 72, 18, "Hatch here", 0xFF3E9E62.toInt(), mx, my) { confirmDiscard = null; send("egg", "hatch", id, page = "nest") }
                button(g, bx, cy + 28, 72, 18, "Pick up", 0xFF3F8FCF.toInt(), mx, my) { confirmDiscard = null; send("egg", "pickup", id, page = "nest") }
                val sure = confirmDiscard == id
                button(g, bx, cy + 49, 72, 18, if (sure) "Sure?" else "Discard", BAD, mx, my, filled = sure) {
                    if (sure) { confirmDiscard = null; send("egg", "discard", id, page = "nest") } else confirmDiscard = id
                }
            } else {
                g.drawCenteredString(font, "${e.owner}'s egg", bx + 36, cy + 32, DIM)
            }
        }
        g.disableScissor()
        scrollbar(g, x + w + 4, gy, gh)
    }

    // ------------------------------------------------------------------ members

    /** The server's main options (for the host or an op): switches and − / + steppers, grouped. */
    private fun settingsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        text(g, "The server's sanctuary options. Changes apply right away and are saved.", x, y, SUB)
        val list = data.getList("settings", Tag.TAG_COMPOUND.toInt())
        val gy = y + 16
        val gh = h - 16
        val rowH = 16
        var groups = 0
        var last = ""
        for (i in 0 until list.size) { val gname = list.getCompound(i).getString("group"); if (gname != last) { groups++; last = gname } }
        maxScroll = max(0.0, list.size * rowH + groups * 14 - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        var ly = gy - scroll.toInt()
        last = ""
        for (i in 0 until list.size) {
            val c = list.getCompound(i)
            val gname = c.getString("group")
            if (gname != last) { text(g, gname, x, ly + 3, ACCENT); ly += 14; last = gname }
            val visible = ly >= gy && ly + rowH <= gy + gh
            text(g, c.getString("label"), x + 8, ly + 4, TEXT)
            val idx = c.getInt("i")
            when (c.getString("kind")) {
                "bool" -> {
                    val on = c.getBoolean("on")
                    val label = if (on) "On" else "Off"
                    val bx = x + w - 50
                    val hover = over(mx, my, bx, ly + 1, 44, 13) && visible
                    box(g, bx, ly + 1, 44, 13, if (on) (if (hover) 0xFF3E7A5C.toInt() else 0xFF2F6A4C.toInt()) else (if (hover) CARD_HOVER else CARD))
                    g.drawCenteredString(font, label, bx + 22, ly + 4, if (on) 0xFFFFFFFF.toInt() else SUB)
                    if (visible) hit(bx, ly + 1, 44, 13) { send("setting", "$idx:toggle", page = "settings") }
                }
                "int", "double" -> {
                    val v = c.getDouble("v")
                    val shown = if (c.getString("kind") == "int") "${v.toInt()}" else String.format("%.2f", v)
                    val bx = x + w - 90
                    for ((dx, sign, up) in listOf(Triple(0, "−", false), Triple(70, "+", true))) {
                        val hover = over(mx, my, bx + dx, ly + 1, 16, 13) && visible
                        box(g, bx + dx, ly + 1, 16, 13, if (hover) CARD_HOVER else CARD)
                        g.drawCenteredString(font, sign, bx + dx + 8, ly + 4, TEXT)
                        if (visible) hit(bx + dx, ly + 1, 16, 13) { send("setting", "$idx:${if (up) "up" else "down"}", page = "settings") }
                    }
                    g.drawCenteredString(font, shown, bx + 43, ly + 4, GOLD)
                }
            }
            ly += rowH
        }
        g.disableScissor()
    }

    private fun membersPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val owner = data.getBoolean("isOwner")
        // friends may visit (without living here) when the owner opens the sanctuary
        if (owner) {
            val open = data.getBoolean("openToVisitors")
            val label = if (open) "Open to visitors: On" else "Open to visitors: Off"
            val bw = font.width(label) + 12
            val bx = x + w - bw
            val hover = over(mx, my, bx, y - 3, bw, 13)
            box(g, bx, y - 3, bw, 13, if (open) 0xFF2F6A4C.toInt() else if (hover) CARD_HOVER else CARD)
            g.drawCenteredString(font, label, bx + bw / 2, y, if (open) 0xFFFFFFFF.toInt() else SUB)
            if (hover) tooltip = listOf(Component.literal("Let other players visit with /sanctuary visit <your name>"), Component.literal("Visitors can look around and leave gifts in the basket (/sanctuary gift)").withStyle(net.minecraft.ChatFormatting.GRAY))
            hit(bx, y - 3, bw, 13) { send("visitors", page = "members") }
        }
        text(g, "People sharing this sanctuary. Their PC Pokémon live here too.", x, y, SUB)
        val members = data.getList("members", Tag.TAG_COMPOUND.toInt())
        var ly = y + 16
        val avatarColors = listOf(0xFF4FC3A1, 0xFF5EA8F0, 0xFFF2C94C, 0xFFE36565, 0xFFA77BF3, 0xFFF07CB4).map { it.toInt() }
        for (i in 0 until members.size) {
            if (ly + 24 > y + h - 60) break
            val m = members.getCompound(i)
            val name = m.getString("name")
            box(g, x, ly, w, 24, CARD)
            val ac = avatarColors[Math.floorMod(name.hashCode(), avatarColors.size)]
            box(g, x + 5, ly + 4, 16, 16, ac)
            g.drawCenteredString(font, name.take(1).uppercase(), x + 13, ly + 8, 0xFFFFFFFF.toInt())
            text(g, name, x + 28, ly + 8, TEXT)
            if (m.getBoolean("owner")) pill(g, "Owner", GOLD, x + 32 + font.width(name), ly + 6)
            else if (owner) {
                val u = m.getUUID("u")
                button(g, x + w - 64, ly + 4, 58, 16, "Remove", BAD, mx, my, filled = false) { send("kick", pokemon = u, page = "members") }
            }
            ly += 28
        }
        ly += 6
        if (owner) {
            text(g, "Invite someone who's online", x, ly, SUB)
            ly += 13
            val online = data.getList("online", Tag.TAG_STRING.toInt())
            if (online.isEmpty()) text(g, "Nobody else is online right now.", x, ly + 4, DIM)
            var cx = x
            for (i in 0 until online.size) {
                val name = online.getString(i)
                val bw = font.width(name) + 30
                if (cx + bw > x + w) { cx = x; ly += 22 }
                if (ly + 18 > y + h) break
                button(g, cx, ly, bw, 18, "+ $name", ACCENT, mx, my, filled = false) { send("invite", name, page = "members") }
                cx += bw + 5
            }
            text(g, "They get a message in chat to accept.", x, y + h - 10, DIM)
        } else {
            button(g, x, ly, 140, 20, "Leave this sanctuary", 0xFFB24A4A.toInt(), mx, my) { send("leave", page = "members") }
            text(g, "Your Pokémon go back to living in your PC.", x + 148, ly + 6, DIM)
        }
    }
}
