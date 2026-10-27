package de.baluserver.erzmarken;

import java.io.IOException;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Runtime bridge to a separately labelled Gradle test artifact and a fresh, explicitly prepared world. */
public final class Phase2TestAccess {
    private Phase2TestAccess() { }
    public static void requireLaunchIdentity() {
        if (testBuild() != "true".equals(System.getProperty("dulliehausen.phase2Test")))
            throw new IllegalStateException("Test-JAR und ausdruecklicher Teststart passen nicht zusammen");
    }
    public static boolean testBuild() {
        var mod = FabricLoader.getInstance().getModContainer("erzmarken");
        if (mod.isEmpty()) return false;
        var value = mod.get().getMetadata().getCustomValue("dulliehausen:phase2-test");
        return value != null && "true".equals(value.getAsString());
    }
    public static Phase2TestPolicy.Admission admission(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (var player : server.getPlayerList().getPlayers()) online.add(player.getUUID());
        return Phase2TestPolicy.inspect(testBuild(), "true".equals(System.getProperty("dulliehausen.phase2Test")),
            server.getWorldPath(LevelResource.ROOT), new Phase2TestPolicy.ServerScope(server.isDedicatedServer(),
                server.getLocalIp(), server.getPort(), server.usesAuthentication(), server.getPlayerList().isUsingWhitelist(),
                server.getPlayerList().getMaxPlayers(), online));
    }
    public static boolean allowed(MinecraftServer server, UUID player, Phase2TestPolicy.Feature feature) {
        return admission(server).permits(player, feature);
    }
    public static boolean testerMayJoin(MinecraftServer server, UUID player) {
        if (!testBuild()) return true;
        var result = admission(server);
        return result.allowed() && result.testers().contains(player);
    }
    public static void requireStartupScope(MinecraftServer server) throws IOException {
        boolean launched = "true".equals(System.getProperty("dulliehausen.phase2Test"));
        if (!testBuild() && !launched) return;
        var result = admission(server);
        if (!result.allowed()) throw new IOException("Teststart verweigert: " + result.reason());
        ErzmarkenMod.LOGGER.warn("PHASE-2-TESTBETRIEB: nur vorbereitete Testwelt und Testkonten. Keine Produktivfreigabe.");
    }
}
