package com.rickygamer28.sanctuary

import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap

/** The Sanctuary dimension: every sanctuary is its own valley in here, far apart from the others. */
object SanctuaryDimension {
    val KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, "sanctuary"))

    /** Top of the flat ground the valleys are shaped from. */
    const val GROUND_Y = 63
    private const val SPACING = 2048
    private const val PER_ROW = 64

    fun level(server: MinecraftServer): ServerLevel? = server.getLevel(KEY)

    fun isSanctuary(level: Level) = level.dimension() == KEY

    fun centerForSlot(slot: Int): BlockPos = BlockPos((slot % PER_ROW) * SPACING + SPACING / 2, GROUND_Y + 1, (slot / PER_ROW) * SPACING + SPACING / 2)

    /** Takes a player into their sanctuary's hub, remembering where they came from. */
    fun travel(player: ServerPlayer, s: Sanctuary) {
        val level = level(player.server) ?: return player.sendSystemMessage(com.rickygamer28.sanctuary.ui.Text.error("The Sanctuary dimension isn't loaded. Is the mod installed on the server?"))
        if (!isSanctuary(player.level())) {
            val tag = player.persistentData
            tag.putString("sanctuary_return_dim", player.level().dimension().location().toString())
            tag.putDouble("sanctuary_return_x", player.x)
            tag.putDouble("sanctuary_return_y", player.y)
            tag.putDouble("sanctuary_return_z", player.z)
        }
        val x = s.center.x + 3
        val z = s.center.z + 3
        level.getChunk(x shr 4, z shr 4)
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        player.teleportTo(level, x + 0.5, y.toDouble(), z + 0.5, player.yRot, player.xRot)
    }

    /** Back to where the player came from (or the world spawn). */
    fun returnHome(player: ServerPlayer) {
        val server = player.server
        val tag = player.persistentData
        val dim = ResourceLocation.tryParse(tag.getString("sanctuary_return_dim"))
        val target = dim?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
        if (target != null && tag.contains("sanctuary_return_x")) {
            player.teleportTo(target, tag.getDouble("sanctuary_return_x"), tag.getDouble("sanctuary_return_y"), tag.getDouble("sanctuary_return_z"), player.yRot, player.xRot)
        } else {
            val ow = server.overworld()
            val spawn = ow.sharedSpawnPos
            player.teleportTo(ow, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, player.yRot, player.xRot)
        }
    }
}
