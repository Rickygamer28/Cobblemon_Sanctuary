package com.rickygamer28.sanctuary.client

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.SanctuaryDimension
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent

/**
 * It never rains in the sanctuary. Its biomes have no precipitation, so no rain or snow falls there,
 * but the weather is shared with the overworld, so the sky would still darken and flash. On the
 * player's screen the sanctuary's rain and thunder are kept at zero.
 */
@EventBusSubscriber(modid = CobblemonSanctuary.MOD_ID, value = [Dist.CLIENT])
object NoRain {
    @JvmStatic
    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension() != SanctuaryDimension.KEY) return
        if (level.getRainLevel(1f) != 0f) level.setRainLevel(0f)
        if (level.getThunderLevel(1f) != 0f) level.setThunderLevel(0f)
    }
}
