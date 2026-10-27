package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Persistent bug reports, in-game non-OP staff commands, queued notifications. */
final class BugReports {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.of("Europe/Berlin"));
    private static final String[] VALID = {"offen", "in_bearbeitung", "behoben"};
    static final class Options {
        boolean enabled = true;
        List<String> staff = new ArrayList<>(List.of("Balukalypse", "BRYANTADVENTURE"));
        int cooldownSeconds = 180;
        int maxMessageLength = 500;
        int maxReports = 5000;
    }
    static final class Report {
        int id;
        String name;
        String uuid;
        String timestamp;
        String message;
        String status = "offen";
        String answer = "";
        Set<String> notifiedStaff = new HashSet<>();
        boolean playerNotified;
    }
    static final class Database {
        int nextId = 1;
        List<Report> reports = new ArrayList<>();
    }
    private static Path dbPath;
    private static Path optionsPath;
    private static Database db;
    private static Path exportDir;
    private static Path aiOutbox;
    private static Options options;
    private static final Map<UUID, Long> lastReports = new HashMap<>();

    static void start(MinecraftServer server) throws IOException {
        // Keep user-editable data together at server-root/dulliehausen/.
        CentralDataPaths locations = CentralDataPaths.prepare(Path.of("."));
        Path root = locations.root();
        optionsPath = locations.config("bugs.json");
        dbPath = locations.data("bugs.json");
        exportDir = locations.export("bug-000001.json").getParent();
        aiOutbox = locations.aiOutbox("bug-000001.json").getParent();
        // Copy legacy data rather than deleting it. Never silently overwrite the new location.
        Path legacyOptions = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("dulliehausen/bugs.json");
        Path legacyDb = server.getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-bugs.json");
        if (Files.notExists(optionsPath) && Files.exists(legacyOptions)) Files.copy(legacyOptions, optionsPath);
        if (Files.notExists(dbPath) && Files.exists(legacyDb)) Files.copy(legacyDb, dbPath);
        Path instructions = root.resolve("README-DATEIEN.txt");
        if (Files.notExists(instructions)) Files.writeString(instructions,
            "Dullie Hausen 2.0\nconfig/bugs.json - Bugreport-Administratoren und Limits\n" +
            "data/bugs.json - Originalmeldungen, Antworten, Status (NICHT LOESCHEN)\n" +
            "bugreports/exports/bug-000001.json - strukturierte Exportbelege fuer Entwicklung\n" +
            "bugreports/ai-inbox/ - optionaler externer KI-Dienst\n" +
            "bugreports/ai-outbox/ - dessen Vorschlaege, NICHT automatisch ausfuehren\n" +
            "Andere Altdaten liegen noch in Weltordner/data oder config; nicht verschieben, bevor Migration getestet ist.\n",
            StandardCharsets.UTF_8);
        if (Files.notExists(optionsPath)) save(optionsPath, new Options());
        try (Reader reader = Files.newBufferedReader(optionsPath, StandardCharsets.UTF_8)) {
            options = GSON.fromJson(reader, Options.class);
        } catch (RuntimeException ex) { throw new IOException("Ungueltige Bugreport-Konfiguration", ex); }
        if (options == null || options.staff == null || options.staff.isEmpty() || options.cooldownSeconds < 0 ||
            options.maxMessageLength < 10 || options.maxMessageLength > 4000 || options.maxReports < 1) {
            throw new IOException("Ungueltige Werte in config/dulliehausen/bugs.json");
        }
        if (Files.exists(dbPath)) {
            try (Reader reader = Files.newBufferedReader(dbPath, StandardCharsets.UTF_8)) {
                db = GSON.fromJson(reader, Database.class);
            } catch (RuntimeException ex) { throw new IOException("Ungueltige Bugreport-Datenbank", ex); }
            if (db == null || db.reports == null || db.nextId < 1) throw new IOException("Ungueltige Bugreport-Datenbank");
        } else {
            db = new Database(); saveDb();
        }
        // Backfill exports for existing reports without mutating the original database.
        for (Report report : db.reports) {
            if (report == null || report.id <= 0 || report.message == null || report.message.isBlank()) continue;
            Path exported = exportDir.resolve(String.format(Locale.ROOT, "bug-%06d.json", report.id));
            if (Files.notExists(exported)) {
                try { BugReportExport.write(exportDir, report.id, report.uuid, report.name, report.timestamp, report.message); }
                catch (IOException ex) { ErzmarkenMod.LOGGER.error("Alten Bugreport #{} nicht exportiert", report.id, ex); }
            }
        }
        lastReports.clear();
    }

    private static void save(Path path, Object object) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { GSON.toJson(object, writer); }
        try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void saveDb() throws IOException { if (db != null) save(dbPath, db); }
    static void stop() { try { saveDb(); } catch (IOException ex) { ErzmarkenMod.LOGGER.error("Bugreport-Speicherung fehlgeschlagen", ex); } db = null; lastReports.clear(); }
    static void joined(ServerPlayer player) {
        if (db == null || options == null) return;
        String name = player.getName().getString();
        boolean changed = false;
        if (staff(name)) {
            int unread = 0;
            for (Report r : db.reports) {
                if (r.notifiedStaff == null) r.notifiedStaff = new HashSet<>();
                if (!r.notifiedStaff.contains(name.toLowerCase(Locale.ROOT))) {
                    unread++;
                    r.notifiedStaff.add(name.toLowerCase(Locale.ROOT));
                    changed = true;
                }
            }
            if (unread > 0) player.sendSystemMessage(Component.literal("§6[Dullie Hausen] §e" + unread + " neue Bugmeldungen. §7/bugs list"));
        }
        for (Report r : db.reports) {
            if (r.uuid.equals(player.getUUID().toString()) && "behoben".equals(r.status) && !r.playerNotified) {
                player.sendSystemMessage(Component.literal("§6[Dullie Hausen] §aBug #" + r.id + " ist behoben. " + (r.answer == null ? "" : r.answer)));
                r.playerNotified = true;
                changed = true;
            }
        }
        if (changed) persist();
    }
    private static boolean staff(String name) {
        return options != null && options.staff.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }
    private static boolean authorized(CommandSourceStack source) {
        // Actual physical console or one of the configured names. No OP required.
        return TokenCommands.consoleOnly(source) || source.getEntity() instanceof ServerPlayer p && staff(p.getName().getString());
    }
    private static void message(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("§6[Dullie Hausen] §r" + message), false);
    }
    private static void error(CommandSourceStack source, String message) { source.sendFailure(Component.literal("[Dullie Hausen] " + message)); }
    private static void persist() { try { saveDb(); } catch (IOException ex) { ErzmarkenMod.LOGGER.error("Konnte Bugreports nicht speichern", ex); } }
    private static Report get(int id) { if (db == null) return null; for (Report r : db.reports) if (r.id == id) return r; return null; }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bug")
            .then(Commands.argument("beschreibung", StringArgumentType.greedyString())
                .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "beschreibung")))));
        dispatcher.register(Commands.literal("bugs").requires(BugReports::authorized)
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("ki").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(ctx -> aiReview(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
            .then(Commands.literal("show").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(ctx -> show(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
            .then(Commands.literal("status").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .then(Commands.argument("status", StringArgumentType.word())
                    .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(VALID, builder))
                    .executes(ctx -> update(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "status"))))))
            .then(Commands.literal("antwort").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> answer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "text")))))));
    }
    private static int create(CommandSourceStack source, String raw) {
        if (!(source.getEntity() instanceof ServerPlayer player)) { error(source, "Nur Spieler koennen /bug nutzen."); return 0; }
        if (db == null || options == null || !options.enabled) { error(source, "Bugmeldungen sind deaktiviert."); return 0; }
        String description = raw.strip();
        if (description.length() < 10 || description.length() > options.maxMessageLength) {
            error(source, "Bitte 10 bis " + options.maxMessageLength + " Zeichen eingeben."); return 0;
        }
        long now = System.currentTimeMillis();
        Long previous = lastReports.get(player.getUUID());
        if (previous != null && now - previous < options.cooldownSeconds * 1000L) {
            error(source, "Bitte warte vor der naechsten Meldung."); return 0;
        }
        if (db.reports.size() >= options.maxReports) { error(source, "Bugreport-Speicher voll. Bitte Admin informieren."); return 0; }
        Report r = new Report();
        r.id = db.nextId++;
        r.name = player.getName().getString(); r.uuid = player.getUUID().toString();
        r.timestamp = DATE.format(Instant.ofEpochMilli(now)); r.message = description;
        db.reports.add(r);
        try { saveDb(); } catch (IOException ex) {
            db.reports.remove(r); db.nextId--; error(source, "Meldung konnte nicht gespeichert werden.");
            ErzmarkenMod.LOGGER.error("Bugreport #" + r.id + " konnte nicht gespeichert werden", ex); return 0;
        }
        try { BugReportExport.write(exportDir, r.id, r.uuid, r.name, r.timestamp, r.message); }
        catch (IOException ex) { ErzmarkenMod.LOGGER.error("Bugreport-Export #{} fehlgeschlagen (Originalmeldung erhalten)", r.id, ex); }
        lastReports.put(player.getUUID(), now);
        message(source, "§aFehler #" + r.id + " gespeichert. Danke fuer die Meldung.");
        MinecraftServer server = source.getServer();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (!staff(online.getName().getString())) continue;
            online.sendSystemMessage(Component.literal("§6[Dullie Hausen] §eNeuer Bug #" + r.id + " von " + r.name + ": §f" + r.message + " §7(/bugs show " + r.id + ")"));
            r.notifiedStaff.add(online.getName().getString().toLowerCase(Locale.ROOT));
        }
        if (!r.notifiedStaff.isEmpty()) persist();
        return 1;
    }
    private static int list(CommandSourceStack source) {
        if (db == null) return 0;
        message(source, "§eBugmeldungen (letzte maximal 15 von " + db.reports.size() + "):");
        for (int i = db.reports.size() - 1; i >= Math.max(0, db.reports.size() - 15); i--) {
            Report r = db.reports.get(i);
            message(source, "§f#" + r.id + " §7[" + r.status + "] " + r.name + ": " + r.message.substring(0, Math.min(65, r.message.length())));
        }
        return 1;
    }
    private static int show(CommandSourceStack source, int id) {
        Report r = get(id);
        if (r == null) { error(source, "Unbekannte Bug-ID."); return 0; }
        message(source, "§e#" + id + " §7(" + r.status + ") von " + r.name + " am " + r.timestamp + " UUID: " + r.uuid);
        message(source, "§f" + r.message);
        message(source, "§7Export: dulliehausen/bugreports/exports/bug-" + String.format(Locale.ROOT, "%06d", id) + ".json");
        message(source, "§7KI-Vorschlag (optional): /bugs ki " + id);
        if (r.answer != null && !r.answer.isBlank()) message(source, "§aAntwort: " + r.answer);
        return 1;
    }
    private static int aiReview(CommandSourceStack source, int id) {
        if (get(id) == null) { error(source, "Unbekannte Bug-ID."); return 0; }
        if (aiOutbox == null) { error(source, "KI-Ablage noch nicht initialisiert."); return 0; }
        try {
            BugAiReviewReader.Review review = BugAiReviewReader.read(aiOutbox, id);
            if (review == null) { message(source, "§7Kein optionaler KI-Vorschlag fuer Bug #" + id + " vorhanden."); return 1; }
            message(source, "§eKI-Vorschlag zu Bug #" + id + " §c[UNGEPRUEFT - NUR VORSCHLAG]");
            message(source, "§7Einstufung: " + review.classification() + " / Konfidenz: " + review.confidence());
            message(source, "§fZusammenfassung: " + review.summary());
            message(source, "§7Schritte: " + review.steps());
            message(source, "§7Erwartet: " + review.expected());
            message(source, "§7Beobachtet: " + review.observed());
            message(source, "§eLoesungsidee: " + review.suggestedFix());
            message(source, "§7Begruendung: " + review.reason());
            message(source, "§cKeine automatische Einstufung, Loeschung oder Statusaenderung.");
            return 1;
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.warn("KI-Vorschlag fuer Bug #{} nicht lesbar", id, ex);
            error(source, "KI-Vorschlag ungueltig/nicht lesbar; Originalreport ist unveraendert.");
            return 0;
        }
    }

    private static int update(CommandSourceStack source, int id, String status) {
        Report r = get(id);
        if (r == null) { error(source, "Unbekannte Bug-ID."); return 0; }
        if (!List.of(VALID).contains(status)) { error(source, "Erlaubt: offen, in_bearbeitung, behoben"); return 0; }
        r.status = status;
        if (status.equals("behoben")) r.playerNotified = false;
        persist(); message(source, "Bug #" + id + " = " + status);
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            if (p.getUUID().toString().equals(r.uuid)) {
                p.sendSystemMessage(Component.literal("§6[Dullie Hausen] §eBug #" + id + " Status: " + status));
                if (status.equals("behoben")) { r.playerNotified = true; persist(); }
            }
        }
        return 1;
    }
    private static int answer(CommandSourceStack source, int id, String raw) {
        Report r = get(id);
        if (r == null) { error(source, "Unbekannte Bug-ID."); return 0; }
        String answer = raw.strip();
        if (answer.isBlank() || answer.length() > 500) { error(source, "Antwort: 1 bis 500 Zeichen."); return 0; }
        r.answer = answer; persist(); message(source, "Antwort zu Bug #" + id + " gespeichert.");
        return 1;
    }
}
