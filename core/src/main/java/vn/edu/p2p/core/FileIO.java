package vn.edu.p2p.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import vn.edu.p2p.protocol.FilePayloads;

final class FileIO {
    private FileIO() {}

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hash(FileChannel channel, long expected) throws IOException {
        channel.position(0);
        var digest = digest();
        var buffer = ByteBuffer.allocate(FilePayloads.CHUNK_BYTES);
        long count = 0;
        int read;
        while ((read = channel.read(buffer)) != -1) {
            if (read > expected - count) {
                throw new IOException("File size changed while hashing");
            }
            digest.update(buffer.array(), 0, read);
            count += read;
            buffer.clear();
        }
        if (count != expected || channel.size() != expected) {
            throw new IOException("File size changed while hashing");
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    static boolean sameHash(String first, String second) {
        return MessageDigest.isEqual(HexFormat.of().parseHex(first), HexFormat.of().parseHex(second));
    }

    /** Temp and final share a trusted local directory. Publication uses an atomic no-replace hard link. */
    static final class Incoming implements AutoCloseable {
        final Path target;
        final Path temporary;
        final FileChannel channel;

        Incoming(Path destination) throws IOException {
            Path absolute = destination.toAbsolutePath();
            try {
                FilePayloads.validateName(absolute.getFileName().toString());
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IOException("Invalid local destination filename", invalid);
            }
            Path parent = absolute.getParent().toRealPath();
            target = parent.resolve(absolute.getFileName());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException("Destination already exists");
            }
            temporary = Files.createTempFile(parent, ".p2p-", ".part");
            try {
                channel = FileChannel.open(temporary, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(temporary);
                throw failed;
            }
        }

        Path publish() throws IOException {
            channel.force(true);
            channel.close();
            try {
                // ATOMIC_MOVE can replace an existing target on some providers; never use it here.
                Files.createLink(target, temporary);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Destination filesystem must support no-replace hard-link publication", unsupported);
            }
            Files.delete(temporary);
            return target;
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
