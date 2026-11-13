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

    private class Biome(val zone: String, val name: String, val color: Int, val awake: Boolean, val count: Int, val pct: Int)

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: (Int) -> Unit)

    private var data: CompoundTag = initial
    val sanctuaryId: String = initial.getString("id")
    private var tab: String = initial.getString("page").let { if (it == "members" || it.isEmpty()) "home" else it }
    private var mons: List<Mon> = emptyList()
    private var biomes: List<Biome> = emptyList()
    private var eggs: List<Egg> = emptyList()
    private var confirmDiscard: UUID? = null
    private var detail: CompoundTag? = null
    private var detailId: UUID? = null
    private var detailTab = "info"
    private var filterZone: String? = null
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
            }.sortedWith(compareBy({ it.home }, { it.name }))
        }
        val bl = tag.getList("biomes", Tag.TAG_COMPOUND.toInt())
        biomes = (0 until bl.size).map { i ->
            val b = bl.getCompound(i)
            Biome(b.getString("zone"), b.getString("name"), b.getInt("color"), b.getBoolean("awake"), b.getInt("count"), b.getInt("pct"))
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
            "residents" -> send("view", page = "residents")
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
        search.setResponder { scroll = 0.0 }
        addWidget(search)
        portrait = null
        portraitKey = null
    }

    override fun tick() {
        ticks++
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
            tab == "nest" -> nestPage(g, cx, cy, cw, ch, mouseX, mouseY)
            tab == "members" -> membersPage(g, cx, cy, cw, ch, mouseX, mouseY)
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
        tooltip?.let { g.renderComponentTooltip(font, it, mouseX, mouseY) }
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
            Triple("biomes", "Biomes", itemOf("cobblemon:grass_gem", Items.OAK_SAPLING)),
            Triple("nest", "Nest", ItemStack(ModContent.POKEMON_EGG.get())),
            Triple("events", "Journal", ItemStack(ModContent.JOURNAL.get())),
            Triple("members", "Members", ItemStack(Items.PLAYER_HEAD)),
        )
        var ty = y + 8
        for ((key, label, icon) in tabs) {
            val active = tab == key || (key == "residents" && tab == "resident")
            val hover = over(mx, my, x + 5, ty, SIDE_W - 10, 22)
            if (active || hover) box(g, x + 5, ty, SIDE_W - 10, 22, if (active) 0xFF233544.toInt() else 0xFF1B2733.toInt())
            if (active) g.fill(x + 5, ty + 3, x + 7, ty + 19, ACCENT)
            item(g, icon, x + 11, ty + 3)
            text(g, label, x + 31, ty + 7, if (active) TEXT else SUB)
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
        text(g, if (prog >= 0) "Building $prog%" else "Valley ready", x + 10, by + 26, if (prog >= 0) GOLD else DIM)
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
        val cw = (w - gap * 2) / 3
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
            val icon = listOf(Items.OAK_SAPLING, Items.AZALEA, Items.FLOWERING_AZALEA)[i.coerceIn(0, 2)]
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

    private fun homePage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val owner = data.getBoolean("isOwner")
        // stat cards
        val gap = 6
        val sw = (w - gap * 3) / 4
        val cap = data.getList("sizes", Tag.TAG_COMPOUND.toInt()).let { if (data.getInt("size") < it.size) it.getCompound(data.getInt("size")).getInt("cap") else 0 }
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
        val bw = (w - gap * 2) / 3
        if (inside) button(g, x, ay, bw, 22, "Return home", PURPLE, mx, my) { send("return") }
        else button(g, x, ay, bw, 22, "Travel to the valley", PURPLE, mx, my) { send("travel") }
        button(g, x + bw + gap, ay, bw, 22, "Reshape the valley", GOLD, mx, my, enabled = owner, why = "Only the owner can reshape it", filled = false) { send("reshape", page = "home") }
        button(g, x + (bw + gap) * 2, ay, bw, 22, "New Journal", ACCENT, mx, my, filled = false) { send("journal", page = "home") }
        // recent events
        val ey = ay + 32
        text(g, "Recently", x, ey, SUB)
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

    private fun filtered(): List<Mon> {
        val q = search.value.trim().lowercase()
        return mons.filter { m ->
            (filterZone == null || m.home == filterZone) && (!mineOnly || m.mine) &&
                (q.isEmpty() || m.name.lowercase().contains(q) || m.species.path.contains(q) || m.types.any { it.contains(q) })
        }
    }

    private fun residentsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        // search field background
        box(g, x - 3, y - 2, 128, 18, TRACK)
        outline(g, x - 3, y - 2, 128, 18, if (search.isFocused) ACCENT else PANEL_EDGE)
        search.x = x + 2
        search.y = y + 3
        // filter
        val zones = mons.map { it.home }.distinct().sortedBy { z -> biomes.indexOfFirst { it.zone == z }.let { if (it < 0) 999 else it } }
        val fx = x + 132
        val fLabel = "Biome: " + (filterZone?.let { zoneName(it) } ?: "All")
        val fw = font.width(fLabel) + 14
        val fc = filterZone?.let { opaque(color(it)) } ?: SUB
        button(g, fx, y - 2, fw, 18, fLabel, fc, mx, my, filled = false) { b ->
            val all = listOf<String?>(null) + zones
            val i = all.indexOf(filterZone)
            filterZone = all[Math.floorMod(i + if (b == 1) -1 else 1, all.size)]
            scroll = 0.0
        }
        if (over(mx, my, fx, y - 2, fw, 18)) tooltip = listOf(Component.literal("Left click: next biome"), Component.literal("Right click: previous"))
        val mx2 = fx + fw + 6
        button(g, mx2, y - 2, 64, 18, if (mineOnly) "✔ Mine" else "Mine only", if (mineOnly) ACCENT else SUB, mx, my, filled = false) { mineOnly = !mineOnly; scroll = 0.0 }
        val list = filtered()
        val count = "${list.size} Pokémon"
        text(g, count, x + w - font.width(count), y + 3, DIM)

        // grid
        val gx = x
        val gy = y + 22
        val gh = h - 26
        val cardW = 62
        val cardH = 60
        val gap = 5
        val cols = max(1, (w + gap) / (cardW + gap))
        val rows = (list.size + cols - 1) / cols
        val used = cols * cardW + (cols - 1) * gap
        val ox = gx + (w - used) / 2
        maxScroll = max(0.0, rows * (cardH + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        if (mons.isEmpty()) {
            g.drawCenteredString(font, "Loading…", x + w / 2, gy + 40, DIM)
            return
        }
        if (list.isEmpty()) g.drawCenteredString(font, "No Pokémon match.", x + w / 2, gy + 40, DIM)
        g.enableScissor(gx - 2, gy, gx + w + 2, gy + gh)
        val first = (scroll / (cardH + gap)).toInt() * cols
        for (i in first until list.size) {
            val row = i / cols
            val col = i % cols
            val cx = ox + col * (cardW + gap)
            val cy = gy + row * (cardH + gap) - scroll.toInt()
            if (cy > gy + gh) break
            val m = list[i]
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
            else -> infoTab(g, d, rx + 8, by + 7, rw - 16, bottom - by - 10)
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
            for (z in homes.take(12)) {
                val label = zoneName(z)
                val bw = font.width(label) + 12
                if (hx + bw > x + w) break
                val c = opaque(color(z))
                if (z == current) {
                    box(g, hx, hy + 1, bw, 18, c)
                    g.drawCenteredString(font, label, hx + bw / 2, hy + 6, 0xFFFFFFFF.toInt())
                } else {
                    val hover = over(mx, my, hx, hy + 1, bw, 18)
                    box(g, hx, hy + 1, bw, 18, if (hover) CARD_HOVER else CARD)
                    outline(g, hx, hy + 1, bw, 18, c)
                    g.drawCenteredString(font, label, hx + bw / 2, hy + 6, c)
                    if (hover) tooltip = listOf(Component.literal("Move to the $label"))
                    hit(hx, hy + 1, bw, 18) { send("act", "home", id, z, page = "resident") }
                }
                hx += bw + 4
            }
        }
    }

    private fun infoTab(g: GuiGraphics, d: CompoundTag, x: Int, y: Int, w: Int, h: Int) {
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
        row("Nature", Component.translatable(d.getString("nature")).string)
        row("Ability", Component.translatable("cobblemon.ability.${d.getString("ability")}").string)
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

    private fun biomesPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        text(g, "Tap an awake biome to see who lives there.", x, y, SUB)
        val cols = 3
        val gap = 5
        val tw = (w - gap * (cols - 1)) / cols
        val th = 34
        val gy = y + 14
        val gh = h - 16
        val rows = (biomes.size + cols - 1) / cols
        maxScroll = max(0.0, rows * (th + gap) - gap - gh.toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        g.enableScissor(x - 2, gy, x + w + 2, gy + gh)
        biomes.forEachIndexed { i, b ->
            val bx = x + (i % cols) * (tw + gap)
            val by = gy + (i / cols) * (th + gap) - scroll.toInt()
            if (by > gy + gh || by + th < gy) return@forEachIndexed
            val c = opaque(b.color)
            val hover = b.awake && over(mx, my, bx, by, tw, th) && my in gy until gy + gh
            box(g, bx, by, tw, th, if (!b.awake) 0xFF1A222B.toInt() else if (hover) CARD_HOVER else CARD)
            g.fill(bx + 1, by + 1, bx + 4, by + th - 1, if (b.awake) c else mix(c, 0x1A222B, 0.7f))
            item(g, itemOf("cobblemon:${b.zone}_gem", Items.PAPER), bx + 8, by + 9)
            text(g, fit(b.name, tw - 34), bx + 28, by + 6, if (b.awake) TEXT else DIM)
            if (b.awake) {
                text(g, "${b.count} Pokémon", bx + 28, by + 17, SUB)
                bar(g, bx + 28, by + 27, tw - 36, 3, b.pct / 100.0, c)
                if (hover) {
                    tooltip = listOf(Component.literal(b.name).withColor(c), Component.literal("${b.count} Pokémon live here").withColor(SUB), Component.literal("${b.pct}% grown").withColor(SUB))
                    hit(bx, by, tw, th) { filterZone = b.zone; goTab("residents") }
                }
            } else {
                text(g, fit("Needs a ${typeLabel(b.zone)} type", tw - 34), bx + 28, by + 18, DIM)
            }
        }
        g.disableScissor()
        scrollbar(g, x + w + 4, gy, gh)
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

    private fun membersPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val owner = data.getBoolean("isOwner")
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
