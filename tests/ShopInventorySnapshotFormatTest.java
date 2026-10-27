import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ShopInventorySnapshotFormatTest {
    static int checks;
    static byte[] bytes(JsonObject object) { return object.toString().getBytes(StandardCharsets.UTF_8); }
    static void denied(byte[] data) throws IOException {
        try { ShopInventorySnapshotFormat.read(data, 43); throw new AssertionError(); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        JsonObject root = new JsonObject(); root.addProperty("version", 2); root.addProperty("slots", 43);
        root.addProperty("selectedSlot", 8); root.add("cursor", JsonNull.INSTANCE);
        JsonArray items = new JsonArray(); for (int i = 0; i < 43; i++) items.add(JsonNull.INSTANCE);
        root.add("items", items);
        ShopInventorySnapshotFormat.read(bytes(root), 43); checks++;
        JsonObject altered = root.deepCopy(); altered.addProperty("version", 1); denied(bytes(altered));
        altered = root.deepCopy(); altered.addProperty("selectedSlot", -1); denied(bytes(altered));
        altered = root.deepCopy(); altered.addProperty("selectedSlot", 9); denied(bytes(altered));
        altered = root.deepCopy(); altered.addProperty("selectedSlot", 1.5); denied(bytes(altered));
        altered = root.deepCopy(); altered.addProperty("slots", 42); denied(bytes(altered));
        altered = root.deepCopy(); altered.getAsJsonArray("items").remove(0); denied(bytes(altered));
        altered = root.deepCopy(); altered.add("cursor", new JsonObject()); denied(bytes(altered));
        altered = root.deepCopy(); altered.remove("selectedSlot"); denied(bytes(altered));
        altered = root.deepCopy(); altered.getAsJsonArray("items").set(0, new JsonPrimitive("bad")); denied(bytes(altered));
        denied(new byte[]{(byte)0xc3, 0x28});
        denied((root.toString().replace("\"version\":2", "\"version\":1,\"version\":2")).getBytes(StandardCharsets.UTF_8));
        System.out.println("Snapshot format 2: " + checks + " checks passed");
    }
}
