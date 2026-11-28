package com.cobblemon.mod.common.net.serverhandling.pasture

import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler
import com.cobblemon.mod.common.api.pasture.PastureLink
import com.cobblemon.mod.common.api.pasture.PastureLinkManager
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.net.messages.client.pasture.ClosePasturePacket
import com.cobblemon.mod.common.net.messages.client.pasture.PokemonUnpasturedPacket
import com.cobblemon.mod.common.net.messages.server.pasture.UnpasturePokemonPacket
import kotlin.jvm.internal.SourceDebugExtension
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.entity.BlockEntity

@SourceDebugExtension(["SMAP\nUnpasturePokemonHandler.kt\nKotlin\n*S Kotlin\n*F\n+ 1 UnpasturePokemonHandler.kt\ncom/cobblemon/mod/common/net/serverhandling/pasture/UnpasturePokemonHandler\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n*L\n1#1,34:1\n1#2:35\n*E\n"])
object UnpasturePokemonHandler private constructor() : ServerNetworkPacketHandler<UnpasturePokemonPacket> {
   @JvmStatic
   public UnpasturePokemonHandler INSTANCE = new UnpasturePokemonHandler();


   public open fun handle(packet: UnpasturePokemonPacket, server: MinecraftServer, player: ServerPlayer) {
      var var10000: PastureLink = PastureLinkManager.getLinkByPlayer(player);
      if (var10000 == null) {
         CobblemonNetwork.INSTANCE.sendPacket(player, (new ClosePasturePacket()) as NetworkPacket);
      } else {
         var var7: BlockEntity = player.level().getBlockEntity(var10000.getPos());
         var var13: PokemonPastureBlockEntity = var7 as? PokemonPastureBlockEntity;
         if ((var7 as? PokemonPastureBlockEntity) != null) {
            var var9: java.util.Iterator = var13.getTetheredPokemon().iterator();

            while (true) {
               if (var9.hasNext()) {
                  var var10: Any = var9.next();
                  if (!((var10 as PokemonPastureBlockEntity.Tethering).getPokemonId() == packet.getPokemonId())) {
                     continue;
                  }

                  var10000 = (PastureLink)var10;
                  break;
               }

               var10000 = null;
               break;
            }

            var tethered: PokemonPastureBlockEntity.Tethering = var10000 as PokemonPastureBlockEntity.Tethering;
            if (var10000 as PokemonPastureBlockEntity.Tethering != null && (var10000 as PokemonPastureBlockEntity.Tethering).getPlayerId() == player.getUUID()) {
               var13.releasePokemon(tethered.getPokemonId());
               CobblemonNetwork.INSTANCE.sendPacket(player, (new PokemonUnpasturedPacket(packet.getPokemonId())) as NetworkPacket);
            } else {
               CobblemonNetwork.INSTANCE.sendPacket(player, (new ClosePasturePacket()) as NetworkPacket);
            }
         }
      }
   }
}
