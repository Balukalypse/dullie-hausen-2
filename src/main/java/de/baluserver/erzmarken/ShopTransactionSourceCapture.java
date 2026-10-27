package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Resolves the four persisted evidence sources for a single player buyback.
 *  Offline / quiescent analysis ONLY: .dat playerdata is not the authoritative
 *  current inventory for an online player. Does not commit or release trades.
 */
public final class ShopTransactionSourceCapture {
    private ShopTransactionSourceCapture() { }

    public record Fileset(Map<ShopTransactionEvidenceStore.Source, Path> paths,
                          boolean registerAndWarehouseShareFile) { }

    /** All paths must be present, ordinary files, and confined to this world. */
    public static Fileset resolveOffline(Path worldRoot, UUID playerId, boolean playerOnline) throws IOException {
        if (worldRoot == null || playerId == null) throw new IOException("Missing world/player");
        if (playerOnline) throw new IOException("Online playerdata is potentially stale; capture denied");
        Path root = worldRoot.toAbsolutePath().normalize();
        Path data = root.resolve("data");
        Path player = ShopPlayerDataPaths.resolveOffline(root, playerId);
        Path tokens = data.resolve("erzmarken.json");
        Path shop = data.resolve("erzmarken-shop.json");
        EnumMap<ShopTransactionEvidenceStore.Source, Path> paths =
            new EnumMap<>(ShopTransactionEvidenceStore.Source.class);
        paths.put(ShopTransactionEvidenceStore.Source.PLAYERDATA, player);
        paths.put(ShopTransactionEvidenceStore.Source.PLAYER_TOKENS, tokens);
        paths.put(ShopTransactionEvidenceStore.Source.SHOP_REGISTER, shop);
        paths.put(ShopTransactionEvidenceStore.Source.SHOP_WAREHOUSE, shop);
        for (var entry : paths.entrySet()) {
            Path candidate = entry.getValue();
            if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(candidate) == 0)
                throw new IOException("Missing/unsafe/stale source: " + entry.getKey());
        }
        return new Fileset(Collections.unmodifiableMap(paths), true);
    }

    /** Read-only, conservative evidence acquisition for a pending transaction.
     *  Does not establish cross-file atomicity or confirm that values were committed.
     */
    public static ShopTransactionEvidenceStore.Result captureOffline(
        Path worldRoot, UUID playerId, boolean playerOnline, String transactionId,
        ShopTransactionEvidenceStore.Moment moment) throws IOException {
        Fileset sources = resolveOffline(worldRoot, playerId, playerOnline);
        return ShopTransactionEvidenceStore.capture(worldRoot, transactionId, moment, sources.paths());
    }
}
