package com.cobblemon.mod.common.net.serverhandling.pasture

import com.cobblemon.mod.common.api.net.ServerNetworkPacketHandler
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.pasture.UpdatePastureConflictFlagPacket
import com.cobblemon.mod.common.net.messages.server.pasture.SetPastureConflictPacket
import kotlin.jvm.internal.SourceDebugExtension
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

@SourceDebugExtension(["SMAP\nSetPastureConflictHandler.kt\nKotlin\n*S Kotlin\n*F\n+ 1 SetPastureConflictHandler.kt\ncom/cobblemon/mod/common/net/serverhandling/pasture/SetPastureConflictHandler\n+ 2 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n*L\n1#1,32:1\n295#2,2:33\n*S KotlinDebug\n*F\n+ 1 SetPastureConflictHandler.kt\ncom/cobblemon/mod/common/net/serverhandling/pasture/SetPastureConflictHandler\n*L\n23#1:33,2\n*E\n"])
object SetPastureConflictHandler private constructor() : ServerNetworkPacketHandler<SetPastureConflictPacket> {
   @JvmStatic
   public SetPastureConflictHandler INSTANCE = new SetPastureConflictHandler();


   public open fun handle(packet: SetPastureConflictPacket, server: MinecraftServer, player: ServerPlayer) {
      var var10000: java.util.List = player.level().getEntitiesOfClass(PokemonEntity.class, player.getBoundingBox().inflate(64.0));
      var var8: java.util.Iterator = var10000.iterator();

      while (true) {
         if (var8.hasNext()) {
            var `element$iv`: Any = var8.next();
            if (!((`element$iv` as PokemonEntity).getPokemon().getUuid() == packet.getPokemonId())) {
               continue;
            }

            var10000 = (java.util.List)`element$iv`;
            break;
         }

         var10000 = null;
         break;
      }

      var var13: PokemonEntity = var10000 as PokemonEntity;
      if (var10000 as PokemonEntity != null) {
         var13.setBehaviourFlag(PokemonBehaviourFlag.PASTURE_CONFLICT, packet.getEnabled());
         var13.remakeBrain();
         new UpdatePastureConflictFlagPacket(packet.getPokemonId(), packet.getEnabled()).sendToPlayer(player);
      }
   }
}
