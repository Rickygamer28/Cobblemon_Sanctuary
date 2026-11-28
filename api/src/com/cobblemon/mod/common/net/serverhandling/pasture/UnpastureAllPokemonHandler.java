package com.cobblemon.mod.common.net.serverhandling.pasture

import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler
import com.cobblemon.mod.common.api.pasture.PastureLink
import com.cobblemon.mod.common.api.pasture.PastureLinkManager
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.net.messages.client.pasture.ClosePasturePacket
import com.cobblemon.mod.common.net.messages.client.pasture.PokemonUnpasturedPacket
import com.cobblemon.mod.common.net.messages.server.pasture.UnpastureAllPokemonPacket
import java.util.ArrayList
import java.util.UUID
import kotlin.jvm.internal.SourceDebugExtension
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.entity.BlockEntity

@SourceDebugExtension(["SMAP\nUnpastureAllPokemonHandler.kt\nKotlin\n*S Kotlin\n*F\n+ 1 UnpastureAllPokemonHandler.kt\ncom/cobblemon/mod/common/net/serverhandling/pasture/UnpastureAllPokemonHandler\n+ 2 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n*L\n1#1,27:1\n1563#2:28\n1634#2,3:29\n1869#2,2:32\n*S KotlinDebug\n*F\n+ 1 UnpastureAllPokemonHandler.kt\ncom/cobblemon/mod/common/net/serverhandling/pasture/UnpastureAllPokemonHandler\n*L\n25#1:28\n25#1:29,3\n25#1:32,2\n*E\n"])
object UnpastureAllPokemonHandler private constructor() : ServerNetworkPacketHandler<UnpastureAllPokemonPacket> {
   @JvmStatic
   public UnpastureAllPokemonHandler INSTANCE = new UnpastureAllPokemonHandler();


   public open fun handle(packet: UnpastureAllPokemonPacket, server: MinecraftServer, player: ServerPlayer) {
      var var10000: PastureLink = PastureLinkManager.getLinkByPlayer(player);
      if (var10000 == null) {
         CobblemonNetwork.INSTANCE.sendPacket(player, (new ClosePasturePacket()) as NetworkPacket);
      } else {
         var `$i$f$forEach`: BlockEntity = player.level().getBlockEntity(var10000.getPos());
         var var22: PokemonPastureBlockEntity = `$i$f$forEach` as? PokemonPastureBlockEntity;
         if ((`$i$f$forEach` as? PokemonPastureBlockEntity) != null) {
            var var10001: UUID = player.getUUID();
            var `$this$forEach$iv`: java.lang.Iterable = var22.releaseAllPokemon(var10001);
            var `element$iv`: java.util.Collection = new ArrayList(CollectionsKt.collectionSizeOrDefault(`$this$forEach$iv`, 10));

            for (Object item$iv$iv : $this$map$iv) {
               `element$iv`.add(new PokemonUnpasturedPacket(`item$iv$iv` as UUID));
            }

            for (Object element$ivx : $this$map$iv) {
               (`element$ivx` as PokemonUnpasturedPacket).sendToPlayer(player);
            }
         }
      }
   }
}
