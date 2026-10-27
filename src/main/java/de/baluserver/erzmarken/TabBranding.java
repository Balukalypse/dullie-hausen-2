package de.baluserver.erzmarken;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Serverseitiger TAB-Header. Keine Client-Mod erforderlich. */
public final class TabBranding {
    private static final Component HEADER = Component.literal("DULLIE HAUSEN")
        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
    private TabBranding() { }

    public static void send(ServerPlayer player) {
        player.connection.send(new ClientboundTabListPacket(HEADER, Component.empty()));
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }
}
