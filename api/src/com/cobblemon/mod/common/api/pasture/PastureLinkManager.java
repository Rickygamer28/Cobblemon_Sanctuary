package com.cobblemon.mod.common.api.pasture

import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.net.messages.client.pasture.ClosePasturePacket
import com.cobblemon.mod.common.util.MapExtensionsKt
import com.cobblemon.mod.common.util.PlayerExtensionsKt
import java.util.LinkedHashMap
import java.util.UUID
import java.util.Map.Entry
import net.minecraft.core.BlockPos
import net.minecraft.core.Position
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

object PastureLinkManager private constructor() {
   @JvmStatic
   public PastureLinkManager INSTANCE = new PastureLinkManager();

   public final val links: MutableMap<UUID, PastureLink> = (new LinkedHashMap()) as java.util.Map


   @JvmStatic
   public fun getLinkByPlayerId(playerId: UUID): PastureLink? {
      return links.get(playerId);
   }

   @JvmStatic
   public fun createLink(playerId: UUID, link: PastureLink) {
      links.put(playerId, link);
   }

   @JvmStatic
   public fun getLinkByPlayer(player: ServerPlayer): PastureLink? {
      var var10000: UUID = player.getUUID();
      var link: PastureLink = getLinkByPlayerId(var10000);
      if (link == null
         || player.level().dimensionTypeRegistration().is(link.getDimension()) && link.getPos().closerToCenterThan(player.position() as Position, 10.0)) {
         return link;
      } else {
         links.remove(player.getUUID());
         return null;
      }
   }

   @JvmStatic
   public fun removeAt(world: ServerLevel, pos: BlockPos) {
      MapExtensionsKt.removeIf(links, PastureLinkManager::removeAt$lambda$0);
   }

   @JvmStatic
   fun `removeAt$lambda$0`(`$world`: ServerLevel, `$pos`: BlockPos, var2: Entry): Boolean {
      var uuid: UUID = var2.getKey() as UUID;
      var pastureLink: PastureLink = var2.getValue() as PastureLink;
      var shouldRemove: Boolean = `$world`.dimensionTypeRegistration().is(pastureLink.getDimension()) && pastureLink.getPos() == `$pos`;
      var var10000: ServerPlayer = PlayerExtensionsKt.getPlayer(uuid);
      if (var10000 != null) {
         CobblemonNetwork.INSTANCE.sendPacket(var10000, (new ClosePasturePacket()) as NetworkPacket);
      }

      return shouldRemove;
   }
}
