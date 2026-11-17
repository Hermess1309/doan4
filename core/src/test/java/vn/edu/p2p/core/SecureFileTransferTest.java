package vn.edu.p2p.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.p2p.crypto.LocalIdentity;
import vn.edu.p2p.crypto.PeerTlsSession;
import vn.edu.p2p.protocol.FilePayloads;
import vn.edu.p2p.protocol.FrameCodec;
import vn.edu.p2p.protocol.Message;
import vn.edu.p2p.protocol.MessageType;

@Timeout(90)
class SecureFileTransferTest {
    @TempDir static Path identities;
    @TempDir Path directory;
    private static char[] password;
    private static PeerNode alice;
    private static PeerNode bob;
    private static LocalIdentity rawAlice;

    @BeforeAll
    static void setupIdentities() throws Exception {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        password = HexFormat.of().formatHex(random).toCharArray();
        generate("alice");
        generate("bob");
        alice = PeerNode.loadIdentity(identities.resolve("alice.p12"), password, "peer");
        bob = PeerNode.loadIdentity(identities.resolve("bob.p12"), password, "peer");
        rawAlice = LocalIdentity.load(identities.resolve("alice.p12"), password, "peer");
    }

    @AfterAll
    static void clearPassword() {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void transfersRealUtf8TextFileAndChecksBothHashesAndProgress() throws Exception {
        Path source = directory.resolve("văn-bản.txt");
        Files.writeString(source, "P2P file transfer — kiểm thử TLS 1.3\n", StandardCharsets.UTF_8);
        transfer(source);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 256, 131099, 10485760})
    void streamsEmptyBinaryMultiChunkAndTenMiB(long size) throws Exception {
        transfer(createSource("source.bin", size));
    }

    @Test
    @Timeout(180)
    @EnabledIfEnvironmentVariable(named = "P2P_LARGE_TEST", matches = "1")
    void optionalOneGiBStreaming() throws Exception {
        transfer(createSource("large.bin", 1024L * 1024 * 1024));
    }

    private void transfer(Path source) throws Exception {
        Path destination = directory.resolve("chosen-by-receiver.bin");
        var sent = new ArrayList<TransferProgress>();
        var received = new ArrayList<TransferProgress>();
        try (var listener = bob.listen(loopback(0), 30000, 60000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiving = threads.submit(() -> {
                try (var session = listener.accept()) {
                    session.verify(session.info().sessionId(), alice.localFingerprint());
                    assertEquals("TLSv1.3", session.info().protocol().orElseThrow());
                    var offer = session.receiveOffer();
                    assertEquals(Files.size(source), offer.size());
                    assertFalse(Files.exists(destination));
                    return session.accept(offer.transferId(), destination, value -> {
                        received.add(value);
                        if (value.state() == TransferProgress.State.COMPLETED) {
                            assertTrue(Files.isRegularFile(destination));
                        }
                    });
                }
            });
            try (var session = alice.connect(loopback(listener.port()), 3000, 60000)) {
                assertEquals(SessionInfo.State.PENDING_VERIFICATION, session.info().state());
                assertTrue(session.info().remoteFingerprint().isEmpty());
                session.verify(session.info().sessionId(), bob.localFingerprint());
                assertEquals("TLSv1.3", session.info().protocol().orElseThrow());
                assertEquals(bob.localFingerprint(), session.info().remoteFingerprint().orElseThrow());
                TransferResult sending = session.send(source, sent::add);
                TransferResult saved = receiving.get(150, TimeUnit.SECONDS);
                assertEquals(sending.transferId(), saved.transferId());
                assertEquals(Files.size(source), saved.bytes());
                assertEquals(sending.sha256(), saved.sha256());
                assertEquals(independentHash(source), saved.sha256());
                assertEquals(independentHash(destination), saved.sha256());
                assertEquals(-1, Files.mismatch(source, destination));
                assertEquals(destination.toRealPath(), saved.savedTo().orElseThrow());
                assertTrue(sending.savedTo().isEmpty());
                assertProgress(sent, saved.bytes());
                assertProgress(received, saved.bytes());
                assertEquals(SessionInfo.State.CLOSED, session.info().state());
            }
        }
        assertNoParts();
    }

    @Test
    void pendingCoreSessionCannotOfferOrReceiveBeforeVerification() throws Exception {
        try (var listener = bob.listen(loopback(0), 3000, 3000);
                var client = alice.connect(loopback(listener.port()), 3000, 3000);
                var server = listener.accept()) {
            assertThrows(SSLPeerUnverifiedException.class,
                    () -> client.send(directory.resolve("not-even-opened.bin"), null));
            assertThrows(SSLPeerUnverifiedException.class, server::receiveOffer);
            assertEquals(SessionInfo.State.PENDING_VERIFICATION, client.info().state());
            assertNoParts();
        }
    }

    @Test
    void wrongPinFailsBeforeAnyOfferOrDestination() throws Exception {
        try (var listener = bob.listen(loopback(0), 3000, 3000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiving = threads.submit(() -> {
                try (var session = listener.accept()) {
                    assertThrows(IOException.class,
                            () -> session.verify(session.info().sessionId(), alice.localFingerprint()));
                    assertEquals(SessionInfo.State.CLOSED, session.info().state());
                }
                return null;
            });
            try (var session = alice.connect(loopback(listener.port()), 3000, 3000)) {
                assertThrows(IOException.class,
                        () -> session.verify(session.info().sessionId(), alice.localFingerprint()));
                assertEquals(SessionInfo.State.CLOSED, session.info().state());
            }
            receiving.get(8, TimeUnit.SECONDS);
        }
        assertNoParts();
    }

    @Test
    void receiverMayRejectWithoutCreatingAnyFile() throws Exception {
        Path source = createSource("source.bin", 100);
        var events = new ArrayList<TransferProgress>();
        try (var listener = bob.listen(loopback(0), 5000, 5000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiving = threads.submit(() -> {
                try (var session = listener.accept()) {
                    session.verify(session.info().sessionId(), alice.localFingerprint());
                    var offer = session.receiveOffer();
                    session.reject(offer.transferId());
                }
                return null;
            });
            try (var session = alice.connect(loopback(listener.port()), 3000, 5000)) {
                session.verify(session.info().sessionId(), bob.localFingerprint());
                assertThrows(TransferRejectedException.class, () -> session.send(source, events::add));
                assertEquals(SessionInfo.State.CLOSED, session.info().state());
            }
            receiving.get(8, TimeUnit.SECONDS);
        }
        assertTrue(events.stream().anyMatch(p -> p.state() == TransferProgress.State.REJECTED));
        assertFalse(events.stream().anyMatch(p -> p.state() == TransferProgress.State.COMPLETED));
        assertNoParts();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.txt", "..\\escape.txt", "/tmp/escape.txt", "C:\\escape.txt",
            "data:stream", "CON.txt"})
    void rejectsTraversalOverAuthenticatedTls(String name) throws Exception {
        malicious(raw -> write(raw, unsafeOffer(name, 0)), new ArrayList<>());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 17179869185L})
    void rejectsInvalidMetadataSizeOverTls(long size) throws Exception {
        malicious(raw -> write(raw, unsafeOffer("file.bin", size)), new ArrayList<>());
    }

    @Test
    void rejectsTruncatedOfferPayloadOverTls() throws Exception {
        malicious(raw -> write(raw, new Message(MessageType.OFFER, new byte[0])), new ArrayList<>());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disconnect", "missing-chunk", "hash", "offset", "wrong-id", "oversize", "empty"})
    void incompleteCorruptOrInvalidChunksNeverComplete(String mode) throws Exception {
        var events = new ArrayList<TransferProgress>();
        malicious(raw -> {
            UUID id = UUID.randomUUID();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[16]));
            write(raw, FilePayloads.offer(new FilePayloads.Offer(id, "safe.bin", 16, hash)));
            Message accepted = FrameCodec.read(raw.input()).orElseThrow();
            assertEquals(MessageType.ACCEPT, accepted.type());
            byte[] data = mode.equals("missing-chunk") || mode.equals("disconnect") ? new byte[8] : new byte[16];
            if (mode.equals("hash")) {
                Arrays.fill(data, (byte) 1);
            }
            UUID chunkId = mode.equals("wrong-id") ? UUID.randomUUID() : id;
            long offset = mode.equals("offset") ? 1 : 0;
            if (mode.equals("oversize") || mode.equals("empty")) {
                int bytes = mode.equals("empty") ? 0 : FilePayloads.CHUNK_BYTES + 1;
                byte[] payload = ByteBuffer.allocate(24 + bytes).putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits()).putLong(0).array();
                write(raw, new Message(MessageType.CHUNK, payload));
            } else {
                write(raw, FilePayloads.chunk(new FilePayloads.Chunk(chunkId, offset, data)));
                if (mode.equals("disconnect")) {
                    raw.close();
                } else {
                    write(raw, FilePayloads.complete(new FilePayloads.Completion(id, 16, hash)));
                }
            }
        }, events);
        assertFalse(events.stream().anyMatch(p -> p.state() == TransferProgress.State.COMPLETED));
        assertTrue(events.stream().anyMatch(p -> p.state() == TransferProgress.State.FAILED));
    }

    @Test
    void doesNotOverwriteExistingOrLateCreatedDestination() throws Exception {
        for (boolean late : new boolean[] {false, true}) {
            Path source = createSource("source-" + late + ".bin", 1024);
            Path target = directory.resolve("protected-" + late + ".bin");
            if (!late) {
                Files.writeString(target, "keep-existing");
            }
            var events = new ArrayList<TransferProgress>();
            try (var listener = bob.listen(loopback(0), 5000, 5000);
                    var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                var receiver = threads.submit(() -> {
                    try (var session = listener.accept()) {
                        session.verify(session.info().sessionId(), alice.localFingerprint());
                        var offer = session.receiveOffer();
                        assertThrows(IOException.class, () -> session.accept(offer.transferId(), target, p -> {
                            events.add(p);
                            if (late && p.state() == TransferProgress.State.VERIFYING) {
                                try {
                                    Files.writeString(target, "keep-existing", StandardOpenOption.CREATE_NEW);
                                } catch (IOException failed) {
                                    throw new AssertionError(failed);
                                }
                            }
                        }));
                    }
                    return null;
                });
                try (var client = alice.connect(loopback(listener.port()), 3000, 5000)) {
                    client.verify(client.info().sessionId(), bob.localFingerprint());
                    assertThrows(IOException.class, () -> client.send(source, null));
                }
                receiver.get(8, TimeUnit.SECONDS);
            }
            assertEquals("keep-existing", Files.readString(target));
            assertFalse(events.stream().anyMatch(p -> p.state() == TransferProgress.State.COMPLETED));
        }
        assertNoParts();
    }

    @Test
    void changedSourceCannotBeReportedAsSuccessful() throws Exception {
        Path source = createSource("mutable.bin", 1024);
        Path target = directory.resolve("mutable-output.bin");
        var events = new ArrayList<TransferProgress>();
        try (var listener = bob.listen(loopback(0), 5000, 5000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiver = threads.submit(() -> {
                try (var session = listener.accept()) {
                    session.verify(session.info().sessionId(), alice.localFingerprint());
                    var offer = session.receiveOffer();
                    assertThrows(IOException.class, () -> session.accept(offer.transferId(), target, events::add));
                }
                return null;
            });
            try (var client = alice.connect(loopback(listener.port()), 3000, 5000)) {
                client.verify(client.info().sessionId(), bob.localFingerprint());
                assertThrows(IOException.class, () -> client.send(source, p -> {
                    if (p.state() == TransferProgress.State.OFFERED) {
                        try {
                            Files.write(source, new byte[1024], StandardOpenOption.TRUNCATE_EXISTING);
                        } catch (IOException failed) {
                            throw new AssertionError(failed);
                        }
                    }
                }));
            }
            receiver.get(8, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(target));
        assertFalse(events.stream().anyMatch(p -> p.state() == TransferProgress.State.COMPLETED));
        assertNoParts();
    }

    private void malicious(Attack attack, List<TransferProgress> events) throws Exception {
        Path target = directory.resolve("received.bin");
        try (var listener = bob.listen(loopback(0), 5000, 5000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiver = threads.submit(() -> {
                try (var session = listener.accept()) {
                    session.verify(session.info().sessionId(), alice.localFingerprint());
                    try {
                        var offer = session.receiveOffer();
                        session.accept(offer.transferId(), target, events::add);
                        return null;
                    } catch (IOException rejected) {
                        assertEquals(SessionInfo.State.CLOSED, session.info().state());
                        return rejected;
                    }
                }
            });
            var tcp = new Socket(InetAddress.getLoopbackAddress(), listener.port());
            try (var raw = PeerTlsSession.prepare(tcp, rawAlice, PeerTlsSession.Role.CLIENT, 5000)) {
                raw.verify(raw.info().sessionId(), bob.localFingerprint());
                attack.run(raw);
                assertNotNull(receiver.get(8, TimeUnit.SECONDS));
            }
        }
        assertFalse(Files.exists(target));
        assertNoParts();
    }

    private static Message unsafeOffer(String name, long size) {
        byte[] text = name.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = ByteBuffer.allocate(58 + text.length).putLong(1).putLong(2).putLong(size)
                .put(new byte[32]).putShort((short) text.length).put(text).array();
        return new Message(MessageType.OFFER, bytes);
    }

    private static void write(PeerTlsSession raw, Message message) throws IOException {
        FrameCodec.write(message, raw.output());
        raw.output().flush();
    }

    @FunctionalInterface
    private interface Attack {
        void run(PeerTlsSession raw) throws Exception;
    }

    private Path createSource(String name, long size) throws IOException {
        Path path = directory.resolve(name);
        byte[] buffer = new byte[FilePayloads.CHUNK_BYTES];
        new java.util.Random(1234).nextBytes(buffer); // Public reproducible test data, not cryptographic keys.
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            long written = 0;
            while (written < size) {
                int count = (int) Math.min(buffer.length, size - written);
                output.write(buffer, 0, count);
                written += count;
            }
        }
        return path;
    }

    private static String independentHash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the independent hash.
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void assertProgress(List<TransferProgress> values, long size) {
        assertFalse(values.isEmpty());
        long previous = 0;
        for (var value : values) {
            assertTrue(value.bytesTransferred() >= previous);
            assertTrue(value.bytesTransferred() <= size);
            previous = value.bytesTransferred();
        }
        assertEquals(TransferProgress.State.COMPLETED, values.getLast().state());
        assertEquals(size, values.getLast().bytesTransferred());
    }

    private void assertNoParts() throws IOException {
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".part")));
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static void generate(String name) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var arguments = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "peer", "-keyalg", "RSA", "-keysize", "3072",
                "-sigalg", "SHA256withRSA", "-validity", "365", "-dname", "CN=" + name,
                "-ext", "BC=ca:false", "-ext", "KU=digitalSignature", "-ext", "EKU=serverAuth,clientAuth",
                "-storetype", "PKCS12", "-keystore", identities.resolve(name + ".p12").toString(),
                "-storepass:env", "P2P_M3_TEST_PASSWORD", "-noprompt");
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true)
                .redirectOutput(identities.resolve(name + "-keytool.log").toFile());
        builder.environment().put("P2P_M3_TEST_PASSWORD", new String(password));
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new IOException("Test keytool timed out");
        }
        assertEquals(0, process.exitValue(), "Test identity generation failed");
    }
}
