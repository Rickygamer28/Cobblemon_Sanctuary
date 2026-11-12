package com.rickygamer28.sanctuary

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import org.slf4j.Logger

@Mod(CobblemonSanctuary.MOD_ID)
class CobblemonSanctuary(modBus: IEventBus) {
    companion object {
        const val MOD_ID = "cobblemon_sanctuary"
        val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        LOGGER.info("Cobblemon: Sanctuary loading")
    }
}
