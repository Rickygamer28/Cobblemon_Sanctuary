package com.rickygamer28.sanctuary.client

import com.rickygamer28.sanctuary.CobblemonSanctuary
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderFrameEvent
import java.text.SimpleDateFormat
import java.util.Date

/** Photo mode: the overlay goes for a couple of frames, the shot is saved (screenshots/sanctuary_…png), then it comes back. */
@EventBusSubscriber(modid = CobblemonSanctuary.MOD_ID, value = [Dist.CLIENT])
object PhotoMode {
    private var frames = 0
    private var hadHidden = false

    fun snap() {
        if (frames > 0) return
        val mc = Minecraft.getInstance()
        hadHidden = mc.options.hideGui
        mc.options.hideGui = true
        frames = 3
    }

    @JvmStatic
    @SubscribeEvent
    fun onFrame(event: RenderFrameEvent.Post) {
        if (frames <= 0) return
        frames--
        if (frames > 0) return
        val mc = Minecraft.getInstance()
        val name = "sanctuary_" + SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(Date()) + ".png"
        Screenshot.grab(mc.gameDirectory, name, mc.mainRenderTarget) { msg -> mc.execute { mc.gui.chat.addMessage(msg) } }
        mc.options.hideGui = hadHidden
    }
}
