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
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/** Server → client: what the Sanctuary screen should show. */
class ViewPayload(val tag: CompoundTag) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ViewPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ViewPayload>(ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, "view"))
        val CODEC: StreamCodec<ByteBuf, ViewPayload> = ByteBufCodecs.COMPOUND_TAG.map({ ViewPayload(it) }, { it.tag })
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
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(ViewPayload.TYPE, ViewPayload.CODEC) { payload, ctx ->
            ctx.enqueueWork { com.rickygamer28.sanctuary.client.ClientHooks.onView(payload.tag) }
        }
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.CODEC) { payload, ctx ->
            ctx.enqueueWork { (ctx.player() as? ServerPlayer)?.let { ScreenActions.handle(it, payload.tag) } }
        }
    }

    fun send(player: ServerPlayer, tag: CompoundTag) = PacketDistributor.sendToPlayer(player, ViewPayload(tag))
}
