package vn.edu.p2p.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import vn.edu.p2p.crypto.PeerTlsSession;
import vn.edu.p2p.protocol.FilePayloads;
import vn.edu.p2p.protocol.FrameCodec;
import vn.edu.p2p.protocol.Message;
import vn.edu.p2p.protocol.MessageType;
import vn.edu.p2p.protocol.ProtocolException;

/** One verified connection, one file, one active worker operation. close() may be called to abort. */
public final class TransferSession implements AutoCloseable {
    private final PeerTlsSession tls;
    private boolean used;
    private boolean decided;
    private FilePayloads.Offer incoming;
    private InputStream input;
    private OutputStream output;

    TransferSession(PeerTlsSession tls) {
        this.tls = tls;
    }

    public SessionInfo info() {
        var value = tls.info();
        return new SessionInfo(value.sessionId(), SessionInfo.State.valueOf(value.state().name()),
                value.localFingerprint(), value.remoteFingerprint(), value.protocol(), value.cipherSuite());
    }

    public SessionInfo verify(UUID sessionId, String trustedFingerprint) throws IOException {
        tls.verify(sessionId, trustedFingerprint);
        return info();
    }

    private synchronized void begin() throws IOException {
        // M2 enforces verification; never create application streams before it succeeds.
        if (used) {
            throw new IOException("Only one file operation is permitted per connection");
        }
        input = new BufferedInputStream(tls.input(), FilePayloads.CHUNK_BYTES);
        output = new BufferedOutputStream(tls.output(), FilePayloads.CHUNK_BYTES);
        used = true;
    }

    public TransferResult send(Path source, Consumer<TransferProgress> callback) throws IOException {
        begin();
        UUID id = UUID.randomUUID();
        long count = 0;
        ProgressReporter progress = null;
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected a regular source file");
            }
            try (var file = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = file.size();
                if (size > FilePayloads.MAX_FILE_BYTES) {
                    throw new IOException("Source exceeds file size limit");
                }
                progress = new ProgressReporter(id, size, TransferProgress.Direction.SEND, callback);
                progress.update(TransferProgress.State.HASHING, 0);
                String hash = FileIO.hash(file, size);
                var offer = new FilePayloads.Offer(id, source.getFileName().toString(), size, hash);
                write(FilePayloads.offer(offer));
                progress.update(TransferProgress.State.OFFERED, 0);
                Message response = read();
                if (!FilePayloads.controlId(response).equals(id)) {
                    throw new ProtocolException("Transfer ID mismatch");
                }
                if (response.type() == MessageType.REJECT) {
                    progress.update(TransferProgress.State.REJECTED, 0);
                    throw new TransferRejectedException();
                }
                if (response.type() != MessageType.ACCEPT) {
                    throw new ProtocolException("Peer did not accept offer");
                }
                progress.update(TransferProgress.State.TRANSFERRING, 0);
                file.position(0);
                var digest = FileIO.digest();
                var buffer = ByteBuffer.allocate(FilePayloads.CHUNK_BYTES);
                int length;
                while ((length = file.read(buffer)) != -1) {
                    if (length == 0) {
                        continue;
                    }
                    if (length > size - count) {
                        throw new IOException("Source file size changed");
                    }
                    byte[] data = Arrays.copyOf(buffer.array(), length);
                    digest.update(data);
                    write(FilePayloads.chunk(new FilePayloads.Chunk(id, count, data)));
                    count += length;
                    progress.update(TransferProgress.State.TRANSFERRING, count);
                    buffer.clear();
                }
                String actual = HexFormat.of().withUpperCase().formatHex(digest.digest());
                if (count != size || file.size() != size || !FileIO.sameHash(hash, actual)) {
                    throw new IOException("Source file changed during transfer");
                }
                progress.update(TransferProgress.State.VERIFYING, count);
                write(FilePayloads.complete(new FilePayloads.Completion(id, size, actual)));
                var confirmation = FilePayloads.parseComplete(read());
                if (!confirmation.id().equals(id) || confirmation.size() != size
                        || !FileIO.sameHash(hash, confirmation.sha256())) {
                    throw new ProtocolException("Invalid receiver completion confirmation");
                }
                progress.update(TransferProgress.State.COMPLETED, count);
                return new TransferResult(id, size, actual, Optional.empty());
            }
        } catch (IOException | RuntimeException failed) {
            if (progress != null && !(failed instanceof TransferRejectedException)) {
                progress.update(TransferProgress.State.FAILED, count);
            }
            if (failed instanceof IOException io) {
                throw io;
            }
            throw new IOException("File send failed", failed);
        } finally {
            close();
        }
    }

    public FileOffer receiveOffer() throws IOException {
        begin();
        try {
            incoming = FilePayloads.parseOffer(read());
            return new FileOffer(incoming.id(), incoming.name(), incoming.size(), incoming.sha256());
        } catch (IOException | RuntimeException failed) {
            try {
                close();
            } catch (IOException closing) {
                failed.addSuppressed(closing);
            }
            if (failed instanceof IOException io) {
                throw io;
            }
            throw new IOException("Invalid file offer", failed);
        }
    }

    public void reject(UUID transferId) throws IOException {
        requireOffer(transferId);
        try {
            write(FilePayloads.control(MessageType.REJECT, transferId));
        } finally {
            close();
        }
    }

    public TransferResult accept(UUID transferId, Path destination, Consumer<TransferProgress> callback)
            throws IOException {
        requireOffer(transferId);
        long count = 0;
        var progress = new ProgressReporter(transferId, incoming.size(), TransferProgress.Direction.RECEIVE, callback);
        try (var file = new FileIO.Incoming(destination)) {
            progress.update(TransferProgress.State.OFFERED, 0);
            write(FilePayloads.control(MessageType.ACCEPT, transferId));
            progress.update(TransferProgress.State.TRANSFERRING, 0);
            while (true) {
                Message message = read();
                if (message.type() == MessageType.COMPLETE) {
                    var complete = FilePayloads.parseComplete(message);
                    if (!complete.id().equals(transferId) || count != incoming.size()
                            || complete.size() != count || !FileIO.sameHash(incoming.sha256(), complete.sha256())) {
                        throw new ProtocolException("Incomplete or inconsistent transfer");
                    }
                    break;
                }
                var chunk = FilePayloads.parseChunk(message);
                byte[] bytes = chunk.data();
                if (!chunk.id().equals(transferId) || chunk.offset() != count
                        || bytes.length > incoming.size() - count) {
                    throw new ProtocolException("Invalid chunk identity, offset or size");
                }
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    file.channel.write(buffer);
                }
                count += bytes.length;
                progress.update(TransferProgress.State.TRANSFERRING, count);
            }
            progress.update(TransferProgress.State.VERIFYING, count);
            file.channel.force(true);
            String actual = FileIO.hash(file.channel, count);
            if (!FileIO.sameHash(incoming.sha256(), actual)) {
                throw new IOException("Received file SHA-256 mismatch");
            }
            Path saved = file.publish();
            write(FilePayloads.complete(new FilePayloads.Completion(transferId, count, actual)));
            progress.update(TransferProgress.State.COMPLETED, count);
            return new TransferResult(transferId, count, actual, Optional.of(saved));
        } catch (IOException | RuntimeException failed) {
            progress.update(TransferProgress.State.FAILED, count);
            if (failed instanceof IOException io) {
                throw io;
            }
            throw new IOException("File receive failed", failed);
        } finally {
            close();
        }
    }

    private synchronized void requireOffer(UUID transferId) throws IOException {
        if (decided || incoming == null || !incoming.id().equals(transferId) || info().state() != SessionInfo.State.VERIFIED) {
            throw new IOException("No matching pending offer");
        }
        decided = true; // Consume the decision atomically; a second accept/reject cannot run concurrently.
    }

    private Message read() throws IOException {
        return FrameCodec.read(input).orElseThrow(() -> new EOFException("Peer disconnected before completion"));
    }

    private void write(Message message) throws IOException {
        FrameCodec.write(message, output);
        output.flush();
    }

    @Override
    public void close() throws IOException {
        // Never close the BufferedOutputStream here: close would flush buffered data after failure.
        tls.close();
    }
}
