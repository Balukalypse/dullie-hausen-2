package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Export is a suggestion for triage, never a substitute for the original bug report. */
final class BugReportExport {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final class Entry {
        int schemaVersion = 1;
        int reportId;
        String createdAtUtc;
        String reportedAtBerlin;
        String reporterName;
        String reporterUuid;
        String originalText;
        String normalizedSummary;
        String triage = "ungeprueft";
        String heuristicFlag = "keine_auffaelligkeit";
        String aiReview = "ausstehend_optional";
        String recommendation = "Durch Admin pruefen; zur Reproduktion Schritte, Erwartung, Ergebnis und Version erfragen.";
        List<String> notes;
    }
    static String flag(String description) {
        String lower = Normalizer.normalize(description, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // Deliberately conservative: uncertainty is not evidence of trolling.
        if (lower.matches("(?s).*([a-z!?])\\1{13,}.*")) return "moeglicherweise_spam";
        if (lower.contains("haha") && lower.contains("verarsch")) return "manuelle_pruefung_empfohlen";
        return "keine_auffaelligkeit";
    }
    static void write(Path directory, int id, String uuid, String name, String berlinTime, String message) throws IOException {
        if (id <= 0 || message == null || message.isBlank()) throw new IllegalArgumentException("Ungueltiger Report");
        Files.createDirectories(directory);
        Entry e = new Entry();
        e.reportId = id;
        e.createdAtUtc = Instant.now().toString();
        e.reportedAtBerlin = berlinTime;
        e.reporterName = name;
        e.reporterUuid = uuid;
        e.originalText = message;
        e.normalizedSummary = message.strip().replaceAll("\\s+", " ");
        e.heuristicFlag = flag(message);
        e.notes = List.of("Automatischer Export; keine KI-Auswertung erfolgt.",
            "Verdachtsmarker ist kein Troll-Nachweis; Originalmeldung bleibt fuer Admins sichtbar.",
            "Externer KI-Dienst darf nur strukturierte Vorschlaege liefern; keine Befehlsausfuehrung.");
        Path file = directory.resolve(String.format(Locale.ROOT, "bug-%06d.json", id));
        Files.writeString(file, JSON.toJson(e) + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
