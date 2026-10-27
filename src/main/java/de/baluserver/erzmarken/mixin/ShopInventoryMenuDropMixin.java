package de.baluserver.erzmarken.mixin;

import de.baluserver.erzmarken.ShopInventoryTransferGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ShopInventoryMenuDropMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void dulliehausen$menuDrop(int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer
                && !ShopInventoryTransferGuard.mayClick(serverPlayer, (AbstractContainerMenu)(Object)this, slot, input)) {
            ((AbstractContainerMenu)(Object)this).broadcastFullState();
            ci.cancel(); // Before cursor/slot removal; no compensating item writes on rejection.
        }
    }
}
