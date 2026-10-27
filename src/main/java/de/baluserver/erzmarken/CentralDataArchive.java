package de.baluserver.erzmarken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * One-time, read-only archival of LEGACY files. The originals remain authoritative.
 * This is NOT a live migration or a restore tool. All inputs are fixed internal paths.
 */
public final class CentralDataArchive {
    public record Result(int copied, int unchanged, int changedSinceArchive, List<String> notices) { }
    private record Source(Path absolutePath, String destination) { }
    private CentralDataArchive() { }

    /** Capture only known legacy files; no recursive playerdata or unrestricted file trees. */
    public static Result capture(Path serverRoot, Path worldRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        Path world = worldRoot.toAbsolutePath().normalize();
        Path archive = root.resolve("dulliehausen/legacy-archive");
        Files.createDirectories(archive);
        if (Files.isSymbolicLink(archive)) throw new IOException("Legacy archive is a symlink");
        List<Source> sources = new ArrayList<>();
        sources.add(new Source(root.resolve("config/erzmarken.json"), "config/erzmarken.json"));
        sources.add(new Source(root.resolve("config/dulliehausen/bugs.json"), "config/bugs.json"));
        for (String name : List.of("erzmarken.json", "erzmarken-shop.json", "erzmarken-stream.json", "dulliehausen-bugs.json"))
            sources.add(new Source(world.resolve("data").resolve(name), "world-data/" + name));
        // The journal, vault and AI files can contain multiple records; preserve structure and prevent symlink traversal.
        for (String folder : List.of("erzmarken-shop-journal", "erzmarken-shop-evidence", "erzmarken-shop-live-evidence", "dulliehausen-inventory-vault", "dulliehausen-ki")) {
            Path base = world.resolve("data").resolve(folder);
            if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(base)) throw new IOException("Legacy data directory is a symlink: " + folder);
            try (var walk = Files.walk(base)) {
                for (Path entry : walk.toList()) {
                    if (Files.isSymbolicLink(entry)) throw new IOException("Symlink inside legacy data: " + entry);
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                        sources.add(new Source(entry, "world-data/" + folder + "/" + base.relativize(entry).toString().replace('\\', '/')));
                }
            }
        }
        Path integrity=archive.resolve(".integrity");
        if (Files.exists(integrity, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(integrity) || !Files.isDirectory(integrity, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Unsafe integrity directory");
        Files.createDirectories(integrity);
        int copied=0, same=0, changed=0;
        List<String> notes=new ArrayList<>();
        for (Source source : sources) {
            Path from=source.absolutePath();
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(from) || !Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Unsafe legacy source: " + from);
            Path destination=archive.resolve(source.destination()).normalize();
            if (!destination.startsWith(archive)) throw new IOException("Archive path escape");
            Path parent=destination.getParent();
            Files.createDirectories(parent);
            for (Path p=archive; p!=null && p.startsWith(archive); p=(p.equals(archive)?null:p.getParent()))
                if (Files.isSymbolicLink(p)) throw new IOException("Symlink within archive: " + p);
            // Integrity files are immutable receipts, not retroactively inferred from old archives.
            Path receipt=integrity.resolve(source.destination() + ".sha256").normalize();
            if (!receipt.startsWith(integrity)) throw new IOException("Integrity path escape");
            Path receiptParent=receipt.getParent();
            Files.createDirectories(receiptParent);
            for (Path p=receiptParent; p!=null && p.startsWith(integrity); p=p.equals(integrity)?null:p.getParent())
                if (Files.isSymbolicLink(p)) throw new IOException("Symlink inside integrity directory: " + p);
            boolean existing=Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            boolean receiptExists=Files.exists(receipt, LinkOption.NOFOLLOW_LINKS);
            if (receiptExists && (Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Unsafe integrity receipt: " + receipt);
            if (existing) {
                if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unsafe archival destination: " + destination);
                String archiveHash=hash(destination);
                if (!receiptExists) {
                    notes.add("ARCHIVE_UNVERIFIED_LEGACY " + source.destination());
                } else if (!Files.readString(receipt, StandardCharsets.US_ASCII).equals(archiveHash + "\n")) {
                    throw new IOException("Archived file fails integrity verification: " + source.destination());
                }
                if (hash(from).equals(archiveHash)) same++;
                else { changed++; notes.add("SOURCE_CHANGED_SINCE_ARCHIVE " + source.destination()); }
                continue;
            }
            // An orphan receipt indicates interrupted or manipulated archival state.
            if (receiptExists) throw new IOException("Integrity receipt without archival file: " + receipt);
            Path temp=Files.createTempFile(parent,".archive-", ".tmp");
            try {
                Files.copy(from,temp,StandardCopyOption.REPLACE_EXISTING);
                if (!hash(from).equals(hash(temp))) throw new IOException("Source changed during archival: " + from);
                String copiedHash=hash(temp);
                try { Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp,destination); }
                // Create-once receipt. Crash between archival publication and receipt is
                // deliberately marked UNVERIFIED on next startup (never silently trusted).
                Path receiptTemp=Files.createTempFile(receiptParent,".receipt-", ".tmp");
                try {
                    Files.writeString(receiptTemp,copiedHash + "\n", StandardCharsets.US_ASCII);
                    try { Files.move(receiptTemp,receipt,StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(receiptTemp,receipt); }
                } finally { Files.deleteIfExists(receiptTemp); }
                copied++;
            } finally { Files.deleteIfExists(temp); }
        }
        // Diagnostic only: reject links and publish atomically so a crash cannot truncate it.
        Path report=archive.resolve("ARCHIV-STATUS.txt");
        if (Files.exists(report, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(report)))
            throw new IOException("Unsafe archive status target: " + report);
        StringBuilder details=new StringBuilder("Nur Sicherheitskopien; Originaldateien bleiben massgeblich.\n"
            + "Dateien kopiert: " + copied + ", unveraendert: " + same + ", inzwischen geaendert: " + changed + "\n");
        for (String note : notes) details.append(note).append('\n');
        Path statusTemp=Files.createTempFile(archive,".status-", ".tmp");
        try {
            Files.writeString(statusTemp, details.toString(), StandardCharsets.UTF_8);
            // Atomic replacement is required; on unsupported filesystems fail rather than truncate.
            Files.move(statusTemp, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(statusTemp); }
        return new Result(copied,same,changed,List.copyOf(notes));
    }
    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream in=Files.newInputStream(file)) {
                byte[] bytes=new byte[8192]; int n;
                while ((n=in.read(bytes))!=-1) digest.update(bytes,0,n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
