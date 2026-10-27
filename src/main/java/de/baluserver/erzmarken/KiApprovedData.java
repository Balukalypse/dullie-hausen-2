package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Validates model output, then stages approved records for the future shop/quest engines.
 * No privileges, economy balance or existing shop sale prices are modified by an AI proposal.
 */
final class KiApprovedData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> JOBS = Set.of("farmer", "fisherman", "librarian", "mason", "weaponsmith", "toolsmith", "shepherd", "cleric", "cartographer");
    private static JsonObject payload(String raw) {
        if (raw == null || raw.length() > 8000) throw new IllegalArgumentException("Payload fehlt oder ist zu lang");
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonObject()) throw new IllegalArgumentException("Payload muss ein JSON-Objekt sein");
            return el.getAsJsonObject();
        } catch (RuntimeException e) { throw new IllegalArgumentException("Payload-JSON ungueltig", e); }
    }
    private static String string(JsonObject j, String key, int limit) {
        if (!j.has(key) || !j.get(key).isJsonPrimitive() || !j.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " fehlt");
        String v = j.get(key).getAsString();
        if (v.isBlank() || v.length() > limit) throw new IllegalArgumentException(key + " ist leer oder zu lang");
        return v;
    }
    private static int integer(JsonObject j, String key, int min, int max) {
        if (!j.has(key) || !j.get(key).isJsonPrimitive() || !j.get(key).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " muss Zahl sein");
        try {
            int v = j.get(key).getAsInt();
            if (v < min || v > max || j.get(key).getAsDouble() != v) throw new IllegalArgumentException(key + " ausserhalb Grenzen");
            return v;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " ungueltig", e); }
    }
    static void validate(String type, String raw) {
        JsonObject j = payload(raw);
        switch (type) {
            case "quest" -> {
                String item = string(j, "item", 128);
                if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("ungueltige Item-ID");
                String job = string(j, "profession", 40);
                if (!JOBS.contains(job)) throw new IllegalArgumentException("unbekannter Beruf");
                integer(j, "amount", 1, 128);
                integer(j, "tokens", 1, 30);
            }
            case "price_event" -> {
                String item = string(j, "item", 128);
                if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("ungueltige Item-ID");
                integer(j, "changePercent", -5, 5);
                integer(j, "durationHours", 1, 72);
            }
            case "announcement" -> { string(j, "message", 400); }
            default -> throw new IllegalArgumentException("unbekannter Typ");
        }
    }
    static void apply(Path root, int id, String type, String raw) throws IOException {
        validate(type, raw);
        // Durable typed hand-off, with atomic file replacement. Current shop and quests do NOT read these files yet.
        Path dir = root.resolve("staged").resolve(type);
        Files.createDirectories(dir);
        Path target = dir.resolve("proposal-" + id + ".json");
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, GSON.toJson(payload(raw)), StandardCharsets.UTF_8);
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
