package com.cobblemon.mod.common.api.pasture

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.pc.PCStore
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceLocation

class PastureLink(linkId: UUID, pcId: UUID, dimension: ResourceLocation, pos: BlockPos, permissions: PasturePermissions) {
   public final val linkId: UUID
   public final val pcId: UUID
   public final val dimension: ResourceLocation
   public final val pos: BlockPos
   public final val permissions: PasturePermissions

   init {
      this.linkId = linkId;
      this.pcId = pcId;
      this.dimension = dimension;
      this.pos = pos;
      this.permissions = permissions;
   }

   public fun getPC(registryAccess: RegistryAccess): PCStore {
      return Cobblemon.INSTANCE.getStorage().getPC(this.pcId, registryAccess);
   }
}
