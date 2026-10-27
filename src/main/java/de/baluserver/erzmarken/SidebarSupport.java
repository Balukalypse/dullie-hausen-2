package de.baluserver.erzmarken;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Privates Sidebar-Scoreboard: jeder Client erhaelt ausschliesslich seine eigenen Tokens. */
public final class SidebarSupport {
    private static final String ID = "erz_priv";
    private static final Map<UUID, List<String>> LAST = new HashMap<>();
    private static final Scoreboard DUMMY = new Scoreboard();
    private static final Objective OBJECTIVE = DUMMY.addObjective(ID, ObjectiveCriteria.DUMMY,
        Component.literal("DULLIE HAUSEN").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
        ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Locale GERMAN = Locale.GERMAN;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE", GERMAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", GERMAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN);
    private static final String[] KEYS = {"ezline11", "ezline10", "ezline09", "ezline08", "ezline07",
        "ezline06", "ezline05", "ezline04", "ezline03", "ezline02", "ezline01", "ezline00", "ezlinez", "ezliney", "ezlinex"};

    private SidebarSupport() { }
    public static void reset() { LAST.clear(); }
    public static void joined(ServerPlayer p) { LAST.remove(p.getUUID()); }
    public static void left(ServerPlayer p) { LAST.remove(p.getUUID()); }
    public static void refresh(ServerPlayer p) { /* Change detection updates lines without recreating the objective. */ }

    private static String center(String value) {
        // Vanilla-Scoreboards haben keine pixelgenaue Zentrierung; Leerzeichen naehern sie an.
        int pad = Math.max(0, (23 - value.length()) / 2);
        return " ".repeat(Math.min(pad, 10)) + value;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(center(text)).withStyle(color, ChatFormatting.BOLD);
    }

    public static void update(MinecraftServer server, TokenStore tokens, ShopState shop) {
        update(server, tokens, shop, false);
    }

    /** Resend current lines after a persisted shop change, even if the cache agrees.
     * Keep the existing objective, so a state change does not recreate the sidebar.
     */
    public static void updateShopStatusNow(MinecraftServer server, TokenStore tokens, ShopState shop) {
        if (tokens != null) update(server, tokens, shop, true);
    }

    private static void update(MinecraftServer server, TokenStore tokens, ShopState shop, boolean force) {
        String status = shop == null ? "Status unbekannt" : shop.hudStatus().replaceAll("§[0-9a-fk-or]", "");
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Set<UUID> active = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            active.add(id);
            boolean showName = StreamSupport.showName(id);
            boolean streaming = StreamSupport.isStreaming(id);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(" "));
            lines.add(Component.literal("     "));
            lines.add(Component.literal("      "));
            if (showName) lines.add(line(p.getGameProfile().name(), ChatFormatting.WHITE));
            lines.add(line("─────────────", ChatFormatting.GRAY));
            lines.add(Component.literal("  "));
            lines.add(line("Token: " + tokens.balance(id), ChatFormatting.YELLOW));
            ChatFormatting statusColor = status.equals("Offen") ? ChatFormatting.GREEN
                : status.equals("Zu") ? ChatFormatting.RED
                : status.equals("Status unbekannt") ? ChatFormatting.DARK_RED : ChatFormatting.GOLD;
            lines.add(line("Shop: " + status, statusColor));
            if (streaming) lines.add(line("STREAM ON", ChatFormatting.RED));
            lines.add(Component.literal("   "));
            lines.add(line("─────────────", ChatFormatting.GRAY));
            lines.add(Component.literal("    "));
            lines.add(line(now.format(DAY), ChatFormatting.WHITE));
            lines.add(line(now.format(DATE), ChatFormatting.WHITE));
            lines.add(line(now.format(TIME) + " Uhr", ChatFormatting.AQUA));
            // Maximal 15 Sidebar-Zeilen in Vanilla; bei eingeblendetem Namen und Stream-Modus genau 15.
            List<String> signature = lines.stream().map(Component::getString).toList();
            List<String> old = LAST.get(id);
            if (old == null) {
                p.connection.send(new ClientboundSetObjectivePacket(OBJECTIVE, 0));
                p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, OBJECTIVE));
            }
            if (force || !signature.equals(old)) {
                int n = Math.min(lines.size(), KEYS.length);
                // Nur geaenderte Zeilen aktualisieren, keine Objective-Neuanlage und kein globales Loeschen.
                for (int i = 0; i < n; i++) {
                    boolean changed = force || old == null || i >= old.size()
                        || !signature.get(i).equals(old.get(i)) || old.size() != n;
                    if (changed) p.connection.send(new ClientboundSetScorePacket(KEYS[i], ID, n - i,
                        Optional.of(lines.get(i)), Optional.of(BlankFormat.INSTANCE)));
                }
                if (old != null) for (int i = n; i < Math.min(old.size(), KEYS.length); i++) {
                    p.connection.send(new ClientboundResetScorePacket(KEYS[i], ID));
                }
                LAST.put(id, signature);
            }
        }
        LAST.keySet().removeIf(u -> !active.contains(u));
    }
}
