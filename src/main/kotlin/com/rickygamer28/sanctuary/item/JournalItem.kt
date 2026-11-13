package com.rickygamer28.sanctuary.item

import com.rickygamer28.sanctuary.ui.SanctuaryGuis
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/** Opens the sanctuary journal. Right-click a resident with it to interact with that Pokémon. */
class JournalItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (player is ServerPlayer) SanctuaryGuis.openJournal(player)
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide)
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += Component.translatable("item.cobblemon_sanctuary.sanctuary_journal.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY)
    }
}

/** Lets you call a specific resident to you from the Journal. */
class WhistleItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (player is ServerPlayer) SanctuaryGuis.openJournal(player, "residents")
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide)
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += Component.translatable("item.cobblemon_sanctuary.whistle.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY)
    }
}
