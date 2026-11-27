package com.rickygamer28.sanctuary.ui

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

object Text {
    fun plain(s: String): MutableComponent = Component.literal(s)
    fun info(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    fun good(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GREEN)
    fun error(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.RED)
    fun title(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    fun button(label: String, command: String, hover: String, color: ChatFormatting = ChatFormatting.AQUA): MutableComponent =
        Component.literal("[$label]").withStyle { st ->
            st.withColor(color)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))
        }

    fun suggest(label: String, command: String, hover: String, color: ChatFormatting = ChatFormatting.AQUA): MutableComponent =
        Component.literal("[$label]").withStyle { st ->
            st.withColor(color)
                .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))
        }

    fun disabled(label: String, hover: String): MutableComponent =
        Component.literal("[$label]").withStyle { st ->
            st.withColor(ChatFormatting.DARK_GRAY).withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))
        }

    fun row(vararg parts: Component): MutableComponent {
        val out = Component.empty()
        parts.forEachIndexed { i, p -> if (i > 0) out.append(" "); out.append(p) }
        return out
    }
}
