package com.rickygamer28.sanctuary.fabric.mixin;

import com.rickygamer28.sanctuary.CobblemonSanctuary;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric has no "caught a fish" event: reeling in a catch (1) tells the sanctuary. */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
    @Inject(method = "retrieve", at = @At("RETURN"))
    private void sanctuary$fished(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() != 1) return;
        FishingHook self = (FishingHook) (Object) this;
        Player player = self.getPlayerOwner();
        if (player != null) CobblemonSanctuary.INSTANCE.fished(player, self);
    }
}
