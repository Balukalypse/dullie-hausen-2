package de.baluserver.erzmarken;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Untrusted optional AI output. Strictly read-only; never changes authoritative reports. */
final class BugAiReviewReader {
    private static final long MAX_FILE_BYTES = 32 * 1024;
    record Review(String classification, String summary, String steps, String expected,
                  String observed, String suggestedFix, String reason, String confidence) {}

    static Review read(Path outbox, int reportId) throws IOException {
        if (reportId < 1) throw new IOException("Ungueltige Bug-ID");
        String filename = String.format(Locale.ROOT, "bug-%06d.json", reportId);
        Path file = outbox.resolve(filename);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(outbox) || Files.isSymbolicLink(file) ||
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Unzulaessige oder zu grosse KI-Datei");
        final JsonObject document;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Kein JSON-Objekt");
            document = parsed.getAsJsonObject();
            if (document.get("schemaVersion").getAsInt() != 1 ||
                document.get("reportId").getAsInt() != reportId ||
                !filename.equals(document.get("source").getAsString()) ||
                !"human_review_required".equals(document.get("reviewState").getAsString()))
                throw new IllegalArgumentException("Abweichende Metadaten");
            JsonObject proposal = document.getAsJsonObject("proposal");
            if (proposal == null) throw new IllegalArgumentException("Fehlender Vorschlag");
            String classification = field(proposal, "classification", 32);
            if (!classification.equals("plausibel") && !classification.equals("unklar") &&
                !classification.equals("vermutlich_spam")) throw new IllegalArgumentException("Unbekannte Klassifikation");
            double confidence = proposal.get("confidence").getAsDouble();
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new IllegalArgumentException("Unzulaessige Konfidenz");
            if (!proposal.has("needsHumanReview") || !proposal.get("needsHumanReview").isJsonPrimitive() ||
                !proposal.get("needsHumanReview").getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Fehlende manuelle Pruefkennzeichnung");
            return new Review(classification, field(proposal, "summary", 1200),
                field(proposal, "steps", 2400), field(proposal, "expected", 1200),
                field(proposal, "observed", 1200), field(proposal, "suggestedFix", 1800),
                field(proposal, "reason", 1200), String.format(Locale.ROOT, "%.0f%%", confidence * 100));
        } catch (RuntimeException ex) {
            throw new IOException("KI-Vorschlag ist ungueltig oder unvollstaendig", ex);
        }
    }

    private static String field(JsonObject object, String key, int maxLen) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Fehlendes Textfeld: " + key);
        String text = value.getAsString().strip();
        if (text.length() > maxLen) throw new IllegalArgumentException("Textfeld zu lang: " + key);
        // Never let AI output introduce Minecraft formatting codes, line breaks or command-looking clickable text.
        return text.replace('§', ' ').replaceAll("[\\p{Cntrl}]", " ");
    }
}
