package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Distinguishes absent evidence from unsafe, partial or tampered evidence. */
final class ShopEvidencePresence {
    private ShopEvidencePresence() { }

    static boolean exists(Path worldRoot, String id, ShopTransactionEvidenceStore.Moment moment) throws IOException {
        Path parent = worldRoot.resolve("data/erzmarken-shop-evidence");
        Path transaction = parent.resolve(id);
        Path snapshot = transaction.resolve(moment.name().toLowerCase(Locale.ROOT));
        for (Path p : new Path[]{parent, transaction, snapshot}) {
            if (Files.isSymbolicLink(p)) throw new IOException("Unsichere Verknuepfung");
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Ungueltiger Snapshot-Pfad");
        }
        return Files.isDirectory(snapshot, LinkOption.NOFOLLOW_LINKS);
    }
}
