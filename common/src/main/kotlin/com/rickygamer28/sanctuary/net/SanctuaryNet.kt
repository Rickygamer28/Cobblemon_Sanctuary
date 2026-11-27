package com.rickygamer28.sanctuary.net

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.ui.ScreenActions
import io.netty.buffer.ByteBuf
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/** Server → client: what the Sanctuary screen should show. */
class ViewPayload(val tag: CompoundTag) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ViewPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ViewPayload>(ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, "view"))
        /**
         * Sent compressed, and read without the usual 2 MB limit: with a couple of thousand residents the screen's
         * data is bigger than that (and compressed it's a fraction of the size).
         */
        val CODEC: StreamCodec<ByteBuf, ViewPayload> = ByteBufCodecs.byteArray(64 * 1024 * 1024).map(
            { bytes -> ViewPayload(net.minecraft.nbt.NbtIo.readCompressed(java.io.ByteArrayInputStream(bytes), net.minecraft.nbt.NbtAccounter.unlimitedHeap())) },
            { payload -> java.io.ByteArrayOutputStream().also { net.minecraft.nbt.NbtIo.writeCompressed(payload.tag, it) }.toByteArray() },
        )
    }
}

/** Client → server: a button was pressed on the Sanctuary screen. */
class ActionPayload(val tag: CompoundTag) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ActionPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ActionPayload>(ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, "action"))
        val CODEC: StreamCodec<ByteBuf, ActionPayload> = ByteBufCodecs.COMPOUND_TAG.map({ ActionPayload(it) }, { it.tag })
    }
}

object SanctuaryNet {
    /** A button pressed on the Sanctuary screen (on the server thread). */
    fun onAction(player: ServerPlayer, tag: CompoundTag) = ScreenActions.handle(player, tag)

    fun send(player: ServerPlayer, tag: CompoundTag) = com.rickygamer28.sanctuary.Platform.current.sendToPlayer(player, ViewPayload(tag))
}
