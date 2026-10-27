import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class Phase2TestPolicyTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static Phase2TestPolicy.ServerScope scope(String ip, int port, boolean auth, boolean whitelist, Set<UUID> online) {
        return new Phase2TestPolicy.ServerScope(true, ip, port, auth, whitelist, 2, online);
    }
    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("phase2-test-policy-");
        try {
            UUID owner = UUID.randomUUID(), buyer = UUID.randomUUID();
            var local = scope("127.0.0.1", 25566, true, true, Set.of(owner));
            check(!Phase2TestPolicy.inspect(true, true, world, local).allowed());
            JsonObject marker = new JsonObject(); marker.addProperty("schema", 1);
            marker.addProperty("purpose", "isolated-phase2-test"); marker.addProperty("testId", UUID.randomUUID().toString());
            marker.addProperty("worldPath", world.toAbsolutePath().normalize().toString());
            JsonArray testers = new JsonArray(); testers.add(owner.toString()); testers.add(buyer.toString());
            marker.add("testers", testers); JsonArray features = new JsonArray();
            features.add("inventory"); features.add("buyback"); features.add("recovery"); marker.add("features", features);
            Path file = world.resolve(".dulliehausen-phase2-test.json"); Files.writeString(file, marker.toString());
            var allowed = Phase2TestPolicy.inspect(true, true, world, local);
            check(allowed.permits(owner, Phase2TestPolicy.Feature.INVENTORY));
            check(allowed.permits(buyer, Phase2TestPolicy.Feature.BUYBACK));
            check(allowed.permits(null, Phase2TestPolicy.Feature.RECOVERY));
            check(!allowed.permits(buyer, Phase2TestPolicy.Feature.RETAIL));
            features.add("retail"); Files.writeString(file, marker.toString());
            check(Phase2TestPolicy.inspect(true, true, world, local).permits(buyer, Phase2TestPolicy.Feature.RETAIL));
            features.remove(3); Files.writeString(file, marker.toString());
            check(!allowed.permits(UUID.randomUUID(), Phase2TestPolicy.Feature.BUYBACK));
            check(!allowed.permits(owner, null));
            check(!Phase2TestPolicy.inspect(false, true, world, local).allowed());
            check(!Phase2TestPolicy.inspect(true, false, world, local).allowed());
            check(!Phase2TestPolicy.inspect(false, false, world, local).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world, scope("0.0.0.0", 25566, true, true, Set.of())).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world, scope("127.0.0.1", 25565, true, true, Set.of())).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world, scope("127.0.0.1", 25566, false, true, Set.of())).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world, scope("127.0.0.1", 25566, true, false, Set.of())).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world, scope(null, 25566, true, true, Set.of())).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world, scope("127.0.0.1", 25566, true, true, Set.of(UUID.randomUUID()))).allowed());
            check(!Phase2TestPolicy.inspect(true, true, world,
                new Phase2TestPolicy.ServerScope(false, "127.0.0.1", 25566, true, true, 2, Set.of())).allowed());
            Path copy = world.resolve("copied-world"); Files.createDirectory(copy); Files.copy(file, copy.resolve(file.getFileName()));
            check(!Phase2TestPolicy.inspect(true, true, copy, local).allowed());
            testers.add(owner.toString()); Files.writeString(file, marker.toString());
            check(!Phase2TestPolicy.inspect(true, true, world, local).allowed()); testers.remove(2);
            marker.addProperty("schema", 1.5); Files.writeString(file, marker.toString());
            check(!Phase2TestPolicy.inspect(true, true, world, local).allowed()); marker.addProperty("schema", 1);
            features.add("other"); Files.writeString(file, marker.toString());
            check(!Phase2TestPolicy.inspect(true, true, world, local).allowed()); features.remove(3);
            Files.writeString(file, marker.toString().replace("\"schema\":1", "\"schema\":1,\"schema\":1"));
            check(!Phase2TestPolicy.inspect(true, true, world, local).allowed());
            Files.writeString(file, "{"); check(!Phase2TestPolicy.inspect(true, true, world, local).allowed());
            System.out.println("Phase-2 sandbox admission: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(world)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
