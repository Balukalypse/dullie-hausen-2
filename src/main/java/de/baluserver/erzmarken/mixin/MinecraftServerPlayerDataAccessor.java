package de.baluserver.erzmarken.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Narrow access to vanilla's targeted player save; no world-wide save per switch. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerPlayerDataAccessor {
    @Accessor("playerDataStorage")
    PlayerDataStorage dulliehausen$getPlayerDataStorage();
}
