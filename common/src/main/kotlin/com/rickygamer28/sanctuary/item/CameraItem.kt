package com.rickygamer28.sanctuary.item

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * The Sanctuary Camera (photo mode): hides the screen's overlay for a moment, takes a clean shot into the
 * screenshots folder, and in the sanctuary notes it in the Journal.
 */
class CameraItem(props: Properties) : Item(props) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.cooldowns.addCooldown(this, 30)
        level.playSound(player, player.blockPosition(), SoundEvents.SPYGLASS_USE, SoundSource.PLAYERS, 1f, 1.6f)
        if (level.isClientSide) com.rickygamer28.sanctuary.client.PhotoMode.snap()
        else if (level is ServerLevel && player is ServerPlayer) Sanctuaries.at(level, player.blockPosition())?.let { s ->
            val zone = ZoneLayout.locate(s, player.blockX, player.blockZ)?.zone
            s.logEvent("${player.gameProfile.name} took a photo in the ${Residents.zoneName(zone)}.")
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }
}
