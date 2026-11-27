package com.rickygamer28.sanctuary.fabric.mixin;

import com.rickygamer28.sanctuary.CobblemonSanctuary;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An entity joining a server world (spawned or loaded with its chunk): the sanctuary may turn it away. */
@Mixin(PersistentEntitySectionManager.class)
abstract class EntitySectionsMixin {
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void sanctuary$join(EntityAccess access, boolean worldGenSpawned, CallbackInfoReturnable<Boolean> cir) {
        if (access instanceof Entity entity && !CobblemonSanctuary.INSTANCE.allowJoin(entity, entity.level())) cir.setReturnValue(false);
    }
}
