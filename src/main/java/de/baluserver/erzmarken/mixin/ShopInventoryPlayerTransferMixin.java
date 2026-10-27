package de.baluserver.erzmarken.mixin;

import de.baluserver.erzmarken.ShopInventoryTransferGuard;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ShopInventoryPlayerTransferMixin {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void dulliehausen$drop(boolean wholeStack, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer)(Object)this;
        if (!ShopInventoryTransferGuard.mayTransfer(player)) {
            player.containerMenu.broadcastFullState();
            ci.cancel(); // Before removeFromSelected, never cancel the lower-level drop after removal.
        }
    }
    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At("HEAD"), cancellable = true)
    private void dulliehausen$menu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (!ShopInventoryTransferGuard.mayOpenMenu((ServerPlayer)(Object)this, provider))
            cir.setReturnValue(OptionalInt.empty());
    }
    @Inject(method = {"openHorseInventory", "openNautilusInventory"}, at = @At("HEAD"), cancellable = true)
    private void dulliehausen$animalMenu(CallbackInfo ci) {
        if (!ShopInventoryTransferGuard.mayTransfer((ServerPlayer)(Object)this)) ci.cancel();
    }
}
