package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Offline-safe review gate: suggestions are DATA ONLY, never executable commands. */
final class KiDirector {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> TYPES = Set.of("quest", "price_event", "announcement");
    static final class Proposal {
        int id;
        String type;
        String title;
        String description;
        String payload; // inert JSON text; never interpreted as a console command
        String status = "pending";
        String createdAt;
        String decidedAt;
        Set<String> approvals = new HashSet<>();
    }
    static final class State {
        int nextId = 1;
        List<Proposal> proposals = new ArrayList<>();
    }
    private static State state;
    private static Path dbPath;
    private static Path inboxPath;
    private static Path acceptedPath;
    private static Path reviewersPath;
    private static Set<String> authorizedReviewers = new HashSet<>();

    static void start(MinecraftServer server) throws IOException {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-ki");
        Files.createDirectories(dir);
        dbPath = dir.resolve("proposals.json");
        inboxPath = dir.resolve("inbox");
        acceptedPath = dir.resolve("approved");
        reviewersPath = dir.resolve("reviewer-uuids.json");
        authorizedReviewers = new HashSet<>();
        if (Files.exists(reviewersPath)) {
            try (Reader r = Files.newBufferedReader(reviewersPath, StandardCharsets.UTF_8)) {
                String[] entries = GSON.fromJson(r, String[].class);
                if (entries == null) throw new IOException("KI-Rechte-Datei ungueltig");
                for (String entry : entries) authorizedReviewers.add(UUID.fromString(entry).toString());
            } catch (RuntimeException e) { throw new IOException("KI-Rechte-Datei ungueltig", e); }
        }
        Files.createDirectories(inboxPath);
        Files.createDirectories(acceptedPath);
        if (Files.exists(dbPath)) {
            try (Reader r = Files.newBufferedReader(dbPath, StandardCharsets.UTF_8)) { state = GSON.fromJson(r, State.class); }
            catch (RuntimeException e) { throw new IOException("KI-Vorschlagsdaten unlesbar", e); }
            if (state == null || state.proposals == null || state.nextId < 1) throw new IOException("KI-Vorschlagsdaten ungueltig");
        } else { state = new State(); save(); }
    }
    static void stop() { try { save(); } catch (IOException e) { ErzmarkenMod.LOGGER.error("KI-Vorschlaege nicht gespeichert", e); } state = null; }
    private static void save() throws IOException {
        if (state == null) return;
        Path tmp = dbPath.resolveSibling("proposals.json.tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { GSON.toJson(state, w); }
        try { Files.move(tmp, dbPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, dbPath, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static boolean reviewer(CommandSourceStack src) {
        if (TokenCommands.consoleOnly(src)) return true;
        if (!(src.getEntity() instanceof ServerPlayer p)) return false;
        return authorizedReviewers.contains(p.getUUID().toString());
    }
    private static String identity(CommandSourceStack src) {
        if (TokenCommands.consoleOnly(src)) return "console";
        if (!(src.getEntity() instanceof ServerPlayer p)) return "";
        // UUID is the sole reviewer identity.
        return p.getUUID().toString();
    }
    private static int message(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal("§6[Dullie Hausen KI] §r" + msg), false); return 1;
    }
    private static int error(CommandSourceStack src, String msg) { src.sendFailure(Component.literal("[Dullie Hausen KI] " + msg)); return 0; }
    private static Proposal find(int id) { if (state == null) return null; for (Proposal p : state.proposals) if (p.id == id) return p; return null; }
    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("ki")
            .then(Commands.literal("reviewer").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("setzen")
                    .then(Commands.argument("uuid1", StringArgumentType.word())
                        .then(Commands.argument("uuid2", StringArgumentType.word())
                            .executes(c -> setReviewers(c.getSource(), StringArgumentType.getString(c, "uuid1"), StringArgumentType.getString(c, "uuid2")))))))
            .then(Commands.literal("liste").requires(KiDirector::reviewer).executes(c -> list(c.getSource())))
            .then(Commands.literal("anzeigen").requires(KiDirector::reviewer).then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> show(c.getSource(), IntegerArgumentType.getInteger(c, "id")))))
            .then(Commands.literal("zustimmen").requires(KiDirector::reviewer).then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> decide(c.getSource(), IntegerArgumentType.getInteger(c, "id"), true))))
            .then(Commands.literal("ablehnen").requires(KiDirector::reviewer).then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> decide(c.getSource(), IntegerArgumentType.getInteger(c, "id"), false))))
            .then(Commands.literal("importieren").requires(TokenCommands::consoleOnly)
                .executes(c -> importInbox(c.getSource())))
            .then(Commands.literal("synchronisieren").requires(TokenCommands::consoleOnly)
                .executes(c -> syncApproved(c.getSource()))));
    }
    private static int setReviewers(CommandSourceStack src, String first, String second) {
        try {
            String a = UUID.fromString(first).toString(), b = UUID.fromString(second).toString();
            if (a.equals(b)) return error(src, "Es muessen zwei unterschiedliche UUIDs sein");
            Set<String> next = Set.of(a, b);
            Path tmp = reviewersPath.resolveSibling("reviewer-uuids.json.tmp");
            Files.writeString(tmp, GSON.toJson(next), StandardCharsets.UTF_8);
            try { Files.move(tmp, reviewersPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, reviewersPath, StandardCopyOption.REPLACE_EXISTING); }
            authorizedReviewers = new HashSet<>(next);
            return message(src, "Zwei UUID-Freigabeberechtigte sicher hinterlegt");
        } catch (IllegalArgumentException e) { return error(src, "Ungueltige Spieler-UUID"); }
          catch (IOException e) { return error(src, "Berechtigungen konnten nicht gespeichert werden"); }
    }
    private static int list(CommandSourceStack src) {
        if (state == null) return error(src, "Nicht gestartet");
        int shown = 0;
        for (Proposal p : state.proposals) if ("pending".equals(p.status)) {
            message(src, "#" + p.id + " [" + p.type + "] " + p.title + " – " + p.approvals.size() + "/2 Freigaben");
            if (++shown == 15) break;
        }
        if (shown == 0) message(src, "Keine offenen Vorschlaege");
        return 1;
    }
    private static int show(CommandSourceStack src, int id) {
        Proposal p = find(id); if (p == null) return error(src, "Vorschlag nicht gefunden");
        message(src, "#" + id + " " + p.type + " (" + p.status + "): " + p.title);
        message(src, p.description);
        message(src, "Payload: " + p.payload.substring(0, Math.min(450, p.payload.length())));
        return 1;
    }
    private static int decide(CommandSourceStack src, int id, boolean yes) {
        Proposal p = find(id); if (p == null) return error(src, "Vorschlag nicht gefunden");
        if (!"pending".equals(p.status)) return error(src, "Bereits entschieden");
        if (!yes) { p.status = "rejected"; p.decidedAt = Instant.now().toString(); }
        else {
            // Console is intentionally NOT allowed to supply both personal approvals.
            String key = identity(src);
            if (key.equals("console")) return error(src, "Die beiden Spieler muessen selbst zustimmen");
            if (!authorizedReviewers.contains(key)) return error(src, "Nicht freigeschaltet");
            if (!p.approvals.add(key)) return error(src, "Du hast bereits zugestimmt");
            if (p.approvals.containsAll(authorizedReviewers) && authorizedReviewers.size() == 2) {
                try { KiApprovedData.validate(p.type, p.payload); }
                catch (IllegalArgumentException e) { p.approvals.clear(); return error(src, "Ungueltiger Inhalt: " + e.getMessage()); }
                p.status = "approved"; p.decidedAt = Instant.now().toString();
            }
        }
        try {
            save();
            if ("approved".equals(p.status)) {
                // Integration in separate curated data stores. Never execute generated code/commands.
                KiApprovedData.apply(dbPath.getParent(), p.id, p.type, p.payload);
                Path out = acceptedPath.resolve("proposal-" + p.id + ".json");
                Files.writeString(out, GSON.toJson(p), StandardCharsets.UTF_8);
                return message(src, "Beide Freigaben erhalten. Gepruefter Vorschlag in KI-Datenbestand uebernommen.");
            }
        } catch (IOException e) { ErzmarkenMod.LOGGER.error("KI-Vorschlag konnte nicht gespeichert werden", e); return error(src, "Speicherfehler"); }
        return message(src, yes ? "Zustimmung gespeichert; zweite Freigabe fehlt" : "Vorschlag abgelehnt");
    }
    private static int syncApproved(CommandSourceStack src) {
        if (state == null) return error(src, "Nicht gestartet");
        int count = 0;
        for (Proposal proposal : state.proposals) {
            if (!"approved".equals(proposal.status)) continue;
            try { KiApprovedData.apply(dbPath.getParent(), proposal.id, proposal.type, proposal.payload); count++; }
            catch (IOException | IllegalArgumentException e) { ErzmarkenMod.LOGGER.error("KI-Nachverarbeitung #{} fehlgeschlagen", proposal.id, e); return error(src, "Vorschlag #" + proposal.id + " nicht uebernommen"); }
        }
        return message(src, count + " genehmigte Datensaetze synchronisiert");
    }
    private static int importInbox(CommandSourceStack src) {
        if (state == null) return error(src, "Nicht gestartet");
        int loaded = 0;
        try (var files = Files.list(inboxPath)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).limit(30).toList()) {
                try {
                    if (Files.size(file) > 32_768) { ErzmarkenMod.LOGGER.warn("KI-Datei zu gross: {}", file); continue; }
                    Proposal incoming;
                    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { incoming = GSON.fromJson(r, Proposal.class); }
                    if (incoming == null || !TYPES.contains(incoming.type) || incoming.title == null || incoming.title.isBlank()
                        || incoming.title.length() > 120 || incoming.description == null || incoming.description.length() > 700
                        || incoming.payload == null || incoming.payload.length() > 8000) { ErzmarkenMod.LOGGER.warn("KI-Vorschlag ungueltig: {}", file); continue; }
                    try { KiApprovedData.validate(incoming.type, incoming.payload); }
                    catch (IllegalArgumentException e) { ErzmarkenMod.LOGGER.warn("KI-Vorschlag mit ungueltigem Inhalt: {} {}", file, e.getMessage()); continue; }
                    incoming.id = state.nextId++;
                    incoming.status = "pending";
                    incoming.createdAt = Instant.now().toString();
                    incoming.decidedAt = null;
                    incoming.approvals = new HashSet<>();
                    state.proposals.add(incoming);
                    save();
                    Files.move(file, file.resolveSibling(file.getFileName() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
                    loaded++;
                } catch (IOException | RuntimeException e) { ErzmarkenMod.LOGGER.warn("KI-Import fehlgeschlagen: {}", file, e); }
            }
        } catch (IOException e) { return error(src, "Inbox nicht lesbar: " + e.getMessage()); }
        return message(src, loaded + " Vorschlaege importiert. /ki liste");
    }
}
