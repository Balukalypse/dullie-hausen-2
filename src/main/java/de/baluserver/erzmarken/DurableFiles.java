package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Single-writer atomic replacement. No cross-file transaction or power-loss guarantee. */
public final class DurableFiles {
    private DurableFiles() { }

    public static void replace(Path file, byte[] contents) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        Path cursor = parent.getRoot();
        for (Path part : parent) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Linked data directory: " + cursor);
        }
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe data destination: " + target);
        Path temp = Files.createTempFile(parent, ".dullie-save-", ".tmp");
        boolean published = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Refuse a non-atomic fallback; preserve the previous valid file on failure.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
        } finally {
            // After publication no cleanup error may falsely report a failed save.
            if (!published) Files.deleteIfExists(temp);
        }
    }
}
