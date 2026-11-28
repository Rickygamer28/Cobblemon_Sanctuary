package com.cobblemon.mod.common.net.serverhandling.pasture

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler
import com.cobblemon.mod.common.api.pasture.PastureLink
import com.cobblemon.mod.common.api.pasture.PastureLinkManager
import com.cobblemon.mod.common.api.storage.PokemonStoreManager
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.net.messages.client.pasture.ClosePasturePacket
import com.cobblemon.mod.common.net.messages.server.pasture.PasturePokemonPacket
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.properties.Property

object PasturePokemonHandler private constructor() : ServerNetworkPacketHandler<PasturePokemonPacket> {
   @JvmStatic
   public PasturePokemonHandler INSTANCE = new PasturePokemonHandler();


   public open fun handle(packet: PasturePokemonPacket, server: MinecraftServer, player: ServerPlayer) {
      var var10000: PastureLink = PastureLinkManager.getLinkByPlayer(player);
      if (var10000 != null) {
         if (!(var10000.getLinkId() == packet.getPastureId())) {
            CobblemonNetwork.INSTANCE.sendPacket(player, (new ClosePasturePacket()) as NetworkPacket);
         } else {
            var var12: PokemonStoreManager = Cobblemon.INSTANCE.getStorage();
            var var10001: UUID = var10000.getPcId();
            var var10002: RegistryAccess = player.registryAccess();
            var var13: Pokemon = var12.getPC(var10001, var10002).get(packet.getPokemonId());
            if (var13 != null) {
               var direction: BlockEntity = player.level().getBlockEntity(var10000.getPos());
               var var14: PokemonPastureBlockEntity = direction as? PokemonPastureBlockEntity;
               if ((direction as? PokemonPastureBlockEntity) != null) {
                  var var11: Direction = player.level().getBlockState(var10000.getPos()).getValue(HorizontalDirectionalBlock.FACING as Property) as Direction;
                  if (var13.getTetheringId() == null) {
                     if (var14.canAddPokemon(player, var13, var10000.getPermissions().getMaxPokemon())) {
                        var14.tether(player, var13, var11);
                     }
                  }
               }
            }
         }
      }
   }
}
