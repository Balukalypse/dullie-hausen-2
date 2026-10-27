import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ShopPlayerDataPathsTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError(); }
    interface IO { void run() throws IOException; }
    static void denied(IO op) throws IOException {
        try { op.run(); throw new AssertionError(); } catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("playerdata-layout-"); UUID player = UUID.randomUUID();
        try {
            denied(() -> ShopPlayerDataPaths.resolveOffline(root, player));
            Path legacy = root.resolve("playerdata/" + player + ".dat");
            Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "legacy");
            check(ShopPlayerDataPaths.resolveOffline(root, player).equals(legacy));
            Path modern = root.resolve("players/data/" + player + ".dat");
            Files.createDirectories(modern.getParent());
            denied(() -> ShopPlayerDataPaths.resolveOffline(root, player)); // refuse stale fallback
            Files.writeString(modern, "modern");
            denied(() -> ShopPlayerDataPaths.resolveOffline(root, player)); // ambiguous dual files
            Files.delete(legacy);
            check(ShopPlayerDataPaths.resolveOffline(root, player).equals(modern));
            Files.writeString(modern, ""); denied(() -> ShopPlayerDataPaths.resolveOffline(root, player));
            Files.delete(modern); Files.createDirectory(modern);
            denied(() -> ShopPlayerDataPaths.resolveOffline(root, player));
            Files.delete(modern); Files.delete(modern.getParent()); Files.delete(root.resolve("players"));
            Files.writeString(root.resolve("players"), "not a directory");
            Files.writeString(legacy, "stale legacy");
            denied(() -> ShopPlayerDataPaths.resolveOffline(root, player));
            System.out.println("Playerdata layouts: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
