package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/** Serverweiter Streaming-Hinweis; private Namens-Sichtbarkeit separat gespeichert. */
public final class StreamSupport {
    private static final String TEAM = "erz_streamt";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<UUID> LIVE = new HashSet<>();
    private static final Set<String> HIDDEN_NAMES = new HashSet<>();
    private static Path file;
    private static final class Saved { Set<String> hiddenNames = new HashSet<>(); }
    private StreamSupport() { }

    public static void load(Path path) {
        file = path;
        HIDDEN_NAMES.clear(); LIVE.clear();
        if (!Files.exists(path)) return;
        try (var r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Saved data = GSON.fromJson(r, Saved.class);
            if (data != null && data.hiddenNames != null) HIDDEN_NAMES.addAll(data.hiddenNames);
        } catch (Exception ex) {
            ErzmarkenMod.LOGGER.error("Stream-Einstellungen konnten nicht geladen werden", ex);
        }
    }
    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Saved state = new Saved(); state.hiddenNames.addAll(HIDDEN_NAMES);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { GSON.toJson(state, w); }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) { ErzmarkenMod.LOGGER.error("Stream-Einstellungen nicht gespeichert", ex); }
    }
    public static boolean showName(UUID id) { return !HIDDEN_NAMES.contains(id.toString()); }
    public static boolean isStreaming(UUID id) { return LIVE.contains(id); }
    private static void announce(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6[Dullie Hausen] §r" + message), false);
    }
    private static PlayerTeam team(MinecraftServer server) {
        Scoreboard board = server.getScoreboard();
        PlayerTeam team = board.getPlayerTeam(TEAM);
        if (team == null) team = board.addPlayerTeam(TEAM);
        team.setPlayerPrefix(Component.literal("[STREAMT] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        return team;
    }
    private static int stream(ServerPlayer player, boolean on) {
        MinecraftServer server = player.level().getServer();
        UUID id = player.getUUID();
        Scoreboard board = server.getScoreboard();
        String name = player.getGameProfile().name();
        if (on) {
            if (LIVE.contains(id)) { player.sendSystemMessage(Component.literal("§7Du bist bereits als Streamer markiert.")); return 1; }
            PlayerTeam current = board.getPlayersTeam(name);
            if (current != null && !TEAM.equals(current.getName())) {
                player.sendSystemMessage(Component.literal("§cStream-Markierung nicht aktiviert: Du bist bereits in einem anderen Scoreboard-Team. Bitte Serverteam-Konflikt beheben."));
                return 0;
            }
            board.addPlayerToTeam(name, team(server));
            LIVE.add(id);
            announce(server, "§c[STREAMT] §f" + name + " §7streamt jetzt!");
        } else {
            if (!LIVE.remove(id)) { player.sendSystemMessage(Component.literal("§7Dein Stream-Modus ist bereits aus.")); return 1; }
            if (board.getPlayersTeam(name) != null && TEAM.equals(board.getPlayersTeam(name).getName())) board.removePlayerFromTeam(name);
            announce(server, "§7" + name + " hat den Stream beendet.");
        }
        SidebarSupport.refresh(player);
        return 1;
    }
    public static void joined(ServerPlayer player) {
        // Beim Betreten bewusst AUS; verhindert eine veraltete Live-Kennzeichnung.
        LIVE.remove(player.getUUID());
        Scoreboard board = player.level().getServer().getScoreboard();
        PlayerTeam team = board.getPlayersTeam(player.getGameProfile().name());
        if (team != null && TEAM.equals(team.getName())) board.removePlayerFromTeam(player.getGameProfile().name());
    }
    public static void left(ServerPlayer player) {
        LIVE.remove(player.getUUID());
        Scoreboard board = player.level().getServer().getScoreboard();
        PlayerTeam team = board.getPlayersTeam(player.getGameProfile().name());
        if (team != null && TEAM.equals(team.getName())) board.removePlayerFromTeam(player.getGameProfile().name());
    }
    public static void stop(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) left(player);
        LIVE.clear();
        save();
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stream")
            .then(Commands.literal("an").executes(ctx -> stream(ctx.getSource().getPlayerOrException(), true)))
            .then(Commands.literal("aus").executes(ctx -> stream(ctx.getSource().getPlayerOrException(), false)))
            .executes(ctx -> { ServerPlayer p=ctx.getSource().getPlayerOrException();
                p.sendSystemMessage(Component.literal(isStreaming(p.getUUID()) ? "§cStream-Modus AN" : "§7Stream-Modus AUS")); return 1; }));
        for (String command : new String[] {"erzmarken", "ez", "dh"}) {
            dispatcher.register(Commands.literal(command)
                .then(Commands.literal("name")
                    .then(Commands.literal("an").executes(ctx -> name(ctx.getSource().getPlayerOrException(), true)))
                    .then(Commands.literal("aus").executes(ctx -> name(ctx.getSource().getPlayerOrException(), false)))));
        }
    }
    private static int name(ServerPlayer p, boolean show) {
        if (show) HIDDEN_NAMES.remove(p.getUUID().toString());
        else HIDDEN_NAMES.add(p.getUUID().toString());
        save(); SidebarSupport.refresh(p);
        p.sendSystemMessage(Component.literal(show ? "§aDein Name wird in deiner privaten Sidebar angezeigt." : "§eDein Name ist in deiner privaten Sidebar ausgeblendet."));
        return 1;
    }
}
