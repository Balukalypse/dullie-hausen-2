package de.baluserver.erzmarken.mixin;

import de.baluserver.erzmarken.ShopInventoryTransferGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ShopInventoryPickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void dulliehausen$pickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && !ShopInventoryTransferGuard.mayTransfer(serverPlayer))
            ci.cancel(); // Before inventory.add or any decrement of the ground stack.
    }
}
