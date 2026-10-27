package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Read-only quarantine evidence export. Never unlocks sessions, mutates live
 * inventories or invokes automatic recovery. Run with the Minecraft server
 * stopped; external vanilla playerdata must be backed up separately.
 */
public final class ShopInventoryRecoveryExport {
    public record Finding(UUID player, String phase, String checksum, String error) { }
    private ShopInventoryRecoveryExport() { }

    /**
     * Copies each vault as evidence before producing a human-readable report.
     * Does not modify the vault directory. If any vault is corrupt it is
     * copied unchanged, and the report records the error.
     */
    public static List<Finding> export(Path vaultRoot, Path destination) throws IOException {
        if (vaultRoot.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())
            || destination.toAbsolutePath().normalize().startsWith(vaultRoot.toAbsolutePath().normalize()))
            throw new IOException("Recovery-Ziel darf nicht innerhalb des Vault-Ordners liegen");
        if (Files.exists(destination)) throw new IOException("Recovery-Ziel existiert bereits");
        Files.createDirectories(destination);
        Path evidence = destination.resolve("vault-evidence");
        Files.createDirectories(evidence);
        ShopInventoryVault vault = new ShopInventoryVault(vaultRoot);
        // inspectUnfinished reports dirty and corrupt sessions, but ordinary
        // PRIVATE_ACTIVE files are also copied as contextual evidence.
        if (Files.isDirectory(vaultRoot)) {
            try (var stream = Files.list(vaultRoot)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    if (!file.getFileName().toString().endsWith(".vault")) continue;
                    Files.copy(file, evidence.resolve(file.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        var inspections = vault.inspectUnfinished();
        java.util.ArrayList<Finding> results = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder("Dullie Hausen - Shop Recovery evidence\n"
            + "READ ONLY. Do not restore automatically. Backup world/playerdata before intervention.\n"
            + "IMPORTANT: Shop-Vault is NOT vanilla playerdata. Compare both plus item drops.\n\n");
        for (var inspection : inspections) {
            Path file = evidence.resolve(inspection.playerId() + ".vault");
            String sum = hash(Files.readAllBytes(file));
            String phase = inspection.phase() == null ? "CORRUPT" : inspection.phase().name();
            String error = inspection.error() == null ? "" : inspection.error().replace('\n',' ');
            Finding f = new Finding(inspection.playerId(), phase, sum, error);
            results.add(f);
            text.append(f.player()).append(" | ").append(phase).append(" | SHA256=").append(sum)
                .append(" | ").append(error).append('\n');
        }
        text.append("\nUnfinished sessions: ").append(results.size()).append('\n');
        Files.writeString(destination.resolve("RECOVERY-REPORT.txt"), text.toString(), StandardCharsets.UTF_8);
        return List.copyOf(results);
    }
    private static String hash(byte[] data) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
