package com.rickygamer28.sanctuary.item

import com.rickygamer28.sanctuary.life.Families
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/** An egg picked up from a sanctuary nest. Walk with it or put it in an Incubator to hatch it. */
class PokemonEggItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val egg = Families.eggTag(stack) ?: return
        tooltip += Component.literal("Parents: ${egg.getString("Mother")} & ${egg.getString("Father")}").withStyle(ChatFormatting.LIGHT_PURPLE)
        tooltip += Component.literal("Trainer: ${egg.getString("OwnerName")}").withStyle(ChatFormatting.GRAY)
        val pct = (Families.progress(stack) * 100).toInt()
        val hint = when {
            pct >= 90 -> "It's moving! It will hatch soon."
            pct >= 60 -> "Sounds can be heard from inside."
            pct >= 25 -> "It moves a little now and then."
            else -> "It will take a while to hatch."
        }
        tooltip += Component.literal("$hint ($pct%)").withStyle(ChatFormatting.GOLD)
        tooltip += Component.literal("Walk with it, or keep it in an Incubator.").withStyle(ChatFormatting.DARK_GRAY)
    }
}
