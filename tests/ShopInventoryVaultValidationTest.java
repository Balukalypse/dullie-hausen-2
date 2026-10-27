import de.baluserver.erzmarken.ShopInventoryVault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Corrupt but checksum-valid envelopes must neither unlock nor be rewritten. */
public class ShopInventoryVaultValidationTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static byte[] envelope(String phase, String priv, String shop) {
        String signed = String.join("\n", "DULLIE_VAULT_1", phase, priv, shop);
        CRC32 crc = new CRC32(); crc.update(signed.getBytes(StandardCharsets.UTF_8));
        return (signed + "\n" + Long.toHexString(crc.getValue())).getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vault-validation-");
        try {
            var vault = new ShopInventoryVault(root);
            UUID id = UUID.randomUUID(); Path file = root.resolve(id + ".vault");
            String[][] invalid = {
                {"PRIVATE_ACTIVE", "AQ==", "-"},
                {"PRIVATE_ACTIVE", "-", ""},
                {"ENTER_PREPARED", "", "-"},
                {"SHOP_ACTIVE", "-", "AQ=="},
                {"EXIT_PREPARED", "AQ==", "-"},
                {"RECOVERY_REQUIRED", "AQ==", ""}
            };
            for (String[] values : invalid) {
                byte[] original = envelope(values[0], values[1], values[2]); Files.write(file, original);
                try { vault.read(id); throw new AssertionError("accepted " + Arrays.toString(values)); }
                catch (IOException expected) { checks++; }
                var inspection = vault.inspectUnfinished();
                check(inspection.size() == 1 && inspection.get(0).phase() == null);
                try { vault.flagRecovery(id); throw new AssertionError("rewrote corrupt record"); }
                catch (IOException expected) { checks++; }
                check(Arrays.equals(original, Files.readAllBytes(file)));
            }
            String[][] valid = {
                {"PRIVATE_ACTIVE", "-", "-"}, {"PRIVATE_ACTIVE", "-", "AQ=="},
                {"ENTER_PREPARED", "AQ==", "-"}, {"SHOP_ACTIVE", "AQ==", "-"},
                {"EXIT_PREPARED", "AQ==", "Ag=="}, {"RECOVERY_REQUIRED", "AQ==", "-"}
            };
            for (String[] values : valid) {
                Files.write(file, envelope(values[0], values[1], values[2]));
                check(vault.read(id).phase().name().equals(values[0]));
            }
            Files.delete(file);
            for (String name : List.of("1-1-1-1-1.vault", "ABCDEFAB-1234-1234-1234-123456789ABC.vault")) {
                Path unusual = root.resolve(name); byte[] original = envelope("SHOP_ACTIVE", "AQ==", "-");
                Files.write(unusual, original);
                try { vault.inspectUnfinished(); throw new AssertionError("ignored " + name); }
                catch (IOException expected) { checks++; }
                check(Arrays.equals(original, Files.readAllBytes(unusual)));
                Files.delete(unusual);
            }
            System.out.println("Vault structural validation: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
