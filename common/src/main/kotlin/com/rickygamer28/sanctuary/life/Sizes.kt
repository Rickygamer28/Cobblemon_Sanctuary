package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.SanctuarySize

/** Which sizes the server allows, and how many residents each one needs. */
object Sizes {
    fun allowed(size: SanctuarySize): Boolean = when (size) {
        SanctuarySize.SMALL -> SanctuaryConfig.allowSmall.get()
        SanctuarySize.MEDIUM -> SanctuaryConfig.allowMedium.get()
        SanctuarySize.LARGE -> SanctuaryConfig.allowLarge.get()
        SanctuarySize.HUGE -> SanctuaryConfig.allowHuge.get()
    }

    fun residentsNeeded(size: SanctuarySize): Int = when (size) {
        SanctuarySize.SMALL -> 0
        SanctuarySize.MEDIUM -> SanctuaryConfig.mediumResidents.get()
        SanctuarySize.LARGE -> SanctuaryConfig.largeResidents.get()
        SanctuarySize.HUGE -> SanctuaryConfig.hugeResidents.get()
    }

    fun unlocked(size: SanctuarySize, residents: Int) = allowed(size) && residents >= residentsNeeded(size)

    fun smallestAllowed(): SanctuarySize = SanctuarySize.entries.firstOrNull { allowed(it) } ?: SanctuarySize.SMALL

    fun nextSize(current: SanctuarySize): SanctuarySize? =
        SanctuarySize.entries.firstOrNull { it.ordinal > current.ordinal && allowed(it) }
}
