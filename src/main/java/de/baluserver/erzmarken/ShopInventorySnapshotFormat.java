package de.baluserver.erzmarken;

import com.google.gson.JsonObject;
import java.io.IOException;

/** Inventory format 2 includes selected hotbar slot; cursor must be empty for saving. */
public final class ShopInventorySnapshotFormat {
    public static final int VERSION = 2;
    private ShopInventorySnapshotFormat() { }
    public static JsonObject read(byte[] bytes, int slots) throws IOException {
        try {
            JsonObject root = StrictJsonFiles.readObject(bytes, 1024 * 1024);
            if (root.get("version").getAsBigDecimal().intValueExact() != VERSION
                    || root.get("slots").getAsBigDecimal().intValueExact() != slots)
                throw new IOException("Inventarformat oder Slotzahl passt nicht (Altformat nicht automatisch migrieren)");
            int selected = root.get("selectedSlot").getAsBigDecimal().intValueExact();
            if (selected < 0 || selected >= 9) throw new IOException("Ungueltiger Hotbar-Slot");
            if (!root.has("cursor") || !root.get("cursor").isJsonNull())
                throw new IOException("Cursor muss vor Speicherung leer sein");
            var items = root.getAsJsonArray("items");
            if (items.size() != slots) throw new IOException("Unvollstaendige Inventarliste");
            for (var item : items)
                if (!item.isJsonNull() && !item.isJsonObject()) throw new IOException("Ungueltiger Itemeintrag");
            return root;
        } catch (RuntimeException failure) { throw new IOException("Inventar-Snapshot ungueltig", failure); }
    }
}
