package com.rickygamer28.sanctuary.client

import com.rickygamer28.sanctuary.net.ActionPayload
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.neoforged.neoforge.network.PacketDistributor

/** Client side of the Sanctuary screen. Only ever loaded on the client. */
object ClientHooks {
    fun onView(tag: CompoundTag) {
        val mc = Minecraft.getInstance()
        val current = mc.screen as? SanctuaryScreen
        if (tag.getBoolean("close")) {
            if (current != null) mc.setScreen(null)
            return
        }
        when {
            current != null && current.sanctuaryId == tag.getString("id") -> current.update(tag)
            tag.getBoolean("open") -> mc.setScreen(SanctuaryScreen(tag))
        }
    }

    fun send(tag: CompoundTag) = PacketDistributor.sendToServer(ActionPayload(tag))
}
