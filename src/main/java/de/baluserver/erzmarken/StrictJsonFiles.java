package de.baluserver.erzmarken;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bounded, read-only JSON loading; never silently discard duplicate object keys. */
final class StrictJsonFiles {
    private StrictJsonFiles() { }
    static JsonObject readObject(byte[] bytes, int max) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > max)
            throw new IOException("JSON-Snapshot fehlt oder ist zu gross");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) { return object(reader); }
    }
    static JsonObject readObject(Path path, long max) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Verlinkter Datenpfad");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > max)
            throw new IOException("Datendatei fehlt oder ist zu gross");
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            return object(reader);
        }
    }
    private static JsonObject object(JsonReader reader) throws IOException {
        JsonElement value = value(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject())
            throw new IOException("Ungueltige JSON-Wurzel oder zusaetzlicher Inhalt");
        return value.getAsJsonObject();
    }
    private static JsonElement value(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("JSON zu tief verschachtelt");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Doppelter JSON-Schluessel");
                    object.add(key, value(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(value(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Ungueltiger JSON-Wert");
        };
    }
}
