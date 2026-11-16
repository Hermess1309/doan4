package vn.edu.p2p.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.core.SessionInfo;
import vn.edu.p2p.core.TransferProgress;
import vn.edu.p2p.relay.RelayClient;
import vn.edu.p2p.relay.RelayConfig;
import vn.edu.p2p.relay.RelayServer;

@Timeout(90)
class RelayTransferTest {
    private static final String MARKER = "PRIVATE-FILE-MARKER-MUST-NEVER-APPEAR-ON-THE-RELAY-WIRE";
    @TempDir static Path identities;
    @TempDir Path directory;
    private static char[] password;
    private static PeerNode alice;
    private static PeerNode bob;

    @BeforeAll
    static void identities() throws Exception {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        password = HexFormat.of().formatHex(random).toCharArray();
        generate("alice");
        generate("bob");
        alice = PeerNode.loadIdentity(identities.resolve("alice.p12"), password, "peer");
        bob = PeerNode.loadIdentity(identities.resolve("bob.p12"), password, "peer");
        assertNotEquals(alice.localFingerprint(), bob.localFingerprint());
    }

    @AfterAll
    static void clear() {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 61, 131199, 1048576})
    void transfersRealFilesWithTls13PinsHashesAndCiphertextOnly(int size) throws Exception {
        Path source = source(size);
        Path destination = directory.resolve("received.bin");
        try (var relay = new RelayServer(address(0), RelayConfig.defaults());
                var ticket = RelayClient.create(address(relay.port()), 3000);
                var tap = new WireTap(relay.port());
                var client = alice.attach(RelayClient.join(address(tap.port()), ticket.code(), 3000),
                        PeerNode.TlsRole.CLIENT, 10000);
                var server = bob.attach(ticket.awaitPair(), PeerNode.TlsRole.SERVER, 10000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(SessionInfo.State.PENDING_VERIFICATION, client.info().state());
            assertEquals(0, relay.stats().forwardedBytes(), "No TLS/application bytes before pin confirmation");
            var receiving = threads.submit(() -> {
                server.verify(server.info().sessionId(), alice.localFingerprint());
                assertEquals("TLSv1.3", server.info().protocol().orElseThrow());
                assertEquals(alice.localFingerprint(), server.info().remoteFingerprint().orElseThrow());
                var offer = server.receiveOffer();
                return server.accept(offer.transferId(), destination, p -> {});
            });
            client.verify(client.info().sessionId(), bob.localFingerprint());
            assertEquals("TLSv1.3", client.info().protocol().orElseThrow());
            assertEquals(bob.localFingerprint(), client.info().remoteFingerprint().orElseThrow());
            var sent = client.send(source, p -> {});
            var received = receiving.get(20, TimeUnit.SECONDS);
            assertEquals(size, received.bytes());
            assertEquals(sent.sha256(), received.sha256());
            assertEquals(hash(source), sent.sha256());
            assertEquals(hash(destination), sent.sha256());
            assertEquals(-1, Files.mismatch(source, destination));
            byte[] wire = tap.captured();
            assertTrue(wire.length > size, "Captured TLS record overhead and handshake");
            assertFalse(new String(wire, StandardCharsets.ISO_8859_1).contains(MARKER));
            assertTrue(relay.stats().forwardedBytes() > size);
            System.out.println("RELAY E2E PASS size=" + size + " protocol=TLSv1.3 SHA-256=" + sent.sha256()
                    + " wireBytes=" + wire.length + " plaintextMarkerAbsent=true");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrongPinAtEitherEndpointPreventsFileTransfer(boolean wrongServerPin) throws Exception {
        Path source = source(131199);
        Path destination = directory.resolve("must-not-exist.bin");
        var progress = new CopyOnWriteArrayList<TransferProgress>();
        try (var relay = new RelayServer(address(0), RelayConfig.defaults());
                var ticket = RelayClient.create(address(relay.port()), 3000);
                var client = alice.attach(RelayClient.join(address(relay.port()), ticket.code(), 3000),
                        PeerNode.TlsRole.CLIENT, 3000);
                var server = bob.attach(ticket.awaitPair(), PeerNode.TlsRole.SERVER, 3000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiving = threads.submit(() -> assertThrows(IOException.class, () -> {
                server.verify(server.info().sessionId(),
                        wrongServerPin ? bob.localFingerprint() : alice.localFingerprint());
                var offer = server.receiveOffer();
                server.accept(offer.transferId(), destination, progress::add);
            }));
            assertThrows(IOException.class, () -> {
                client.verify(client.info().sessionId(),
                        wrongServerPin ? bob.localFingerprint() : alice.localFingerprint());
                client.send(source, progress::add);
            });
            receiving.get(8, TimeUnit.SECONDS);
            assertEquals(SessionInfo.State.CLOSED, client.info().state());
            assertEquals(SessionInfo.State.CLOSED, server.info().state());
            assertFalse(Files.exists(destination));
            assertFalse(progress.stream().anyMatch(p -> p.state() == TransferProgress.State.TRANSFERRING));
        }
    }

    @Test
    void unverifiedRelaySessionCannotSendApplicationData() throws Exception {
        try (var relay = new RelayServer(address(0), RelayConfig.defaults());
                var ticket = RelayClient.create(address(relay.port()), 3000);
                var client = alice.attach(RelayClient.join(address(relay.port()), ticket.code(), 3000),
                        PeerNode.TlsRole.CLIENT, 3000);
                var server = bob.attach(ticket.awaitPair(), PeerNode.TlsRole.SERVER, 3000)) {
            assertThrows(IOException.class, () -> client.send(source(61), p -> {}));
            assertThrows(IOException.class, server::receiveOffer);
            assertEquals(0, relay.stats().forwardedBytes());
            assertFalse(Files.exists(directory.resolve("received.bin")));
        }
    }

    @Test
    void disconnectMidFileFailsAndRemovesPartialFileWithoutHanging() throws Exception {
        Path source = source(1048576);
        Path destination = directory.resolve("incomplete.bin");
        try (var relay = new RelayServer(address(0), RelayConfig.defaults());
                var ticket = RelayClient.create(address(relay.port()), 3000);
                var tap = new WireTap(relay.port(), 128 * 1024);
                var client = alice.attach(RelayClient.join(address(tap.port()), ticket.code(), 3000),
                        PeerNode.TlsRole.CLIENT, 5000);
                var server = bob.attach(ticket.awaitPair(), PeerNode.TlsRole.SERVER, 5000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var receiving = threads.submit(() -> assertThrows(IOException.class, () -> {
                server.verify(server.info().sessionId(), alice.localFingerprint());
                var offer = server.receiveOffer();
                server.accept(offer.transferId(), destination, p -> {});
            }));
            client.verify(client.info().sessionId(), bob.localFingerprint());
            assertThrows(IOException.class, () -> client.send(source, p -> {}));
            receiving.get(8, TimeUnit.SECONDS);
            assertFalse(Files.exists(destination));
            try (var paths = Files.list(directory)) {
                assertFalse(paths.anyMatch(p -> p.toString().endsWith(".part")));
            }
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (relay.stats().connections() != 0) { Thread.sleep(10); }
            });
        }
    }

    @Test
    void attachRejectsInvalidSocketAndTakesOwnershipOnFailure() throws Exception {
        Socket socket = new Socket();
        assertThrows(IllegalArgumentException.class, () -> alice.attach(socket, PeerNode.TlsRole.CLIENT, 1000));
        assertTrue(socket.isClosed());
    }

    @ParameterizedTest
    @ValueSource(ints = {61, 1048576})
    void independentRelayAndTwoPeerJvmProcessesTransferFile(int size) throws Exception {
        Path source = source(size);
        Path destination = directory.resolve("process-received.bin");
        try (var relay = child("relay-server", "--port", "0")) {
            int port = Integer.parseInt(relay.await(s -> s.startsWith("RELAY LISTENING ")).split(" ")[2]);
            try (var receiver = child("relay-receive", "--host", "127.0.0.1", "--port", "" + port,
                    "--store", identities.resolve("bob.p12").toString(), "--password-env", "P2P_M6_TEST_PASSWORD",
                    "--pin", alice.localFingerprint(), "--output", destination.toString(), "--accept")) {
                String code = receiver.await(s -> s.startsWith("PAIRING CODE ")).split(" ")[2];
                try (var sender = child("relay-send", "--host", "127.0.0.1", "--port", "" + port,
                        "--code", code, "--store", identities.resolve("alice.p12").toString(),
                        "--password-env", "P2P_M6_TEST_PASSWORD", "--pin", bob.localFingerprint(),
                        "--file", source.toString())) {
                    assertNotEquals(sender.process.pid(), receiver.process.pid());
                    assertNotEquals(sender.process.pid(), relay.process.pid());
                    sender.success();
                    receiver.success();
                    assertTrue(sender.lines.stream().anyMatch(s -> s.startsWith("VERIFIED TLSv1.3")));
                    assertTrue(receiver.lines.stream().anyMatch(s -> s.startsWith("VERIFIED TLSv1.3")));
                    String expectedHash = hash(source);
                    assertTrue(sender.lines.contains("SUCCESS bytes=" + size + " SHA-256=" + expectedHash));
                    assertTrue(receiver.lines.contains("SUCCESS bytes=" + size + " SHA-256=" + expectedHash));
                    assertEquals(expectedHash, hash(destination));
                    assertEquals(-1, Files.mismatch(source, destination));
                    assertNoSecret(sender.lines);
                    assertNoSecret(receiver.lines);
                    assertNoSecret(relay.lines);
                    System.out.println("THREE JVM PASS relayPid=" + relay.process.pid() + " senderPid="
                            + sender.process.pid() + " receiverPid=" + receiver.process.pid()
                            + " size=" + size + " TLSv1.3 SHA-256=" + expectedHash);
                }
            }
        }
    }

    @Test
    void wrongPinInSeparateProcessesProducesFailureWithoutFile() throws Exception {
        Path source = source(61);
        Path destination = directory.resolve("denied.bin");
        try (var relay = child("relay-server", "--port", "0")) {
            String port = relay.await(s -> s.startsWith("RELAY LISTENING ")).split(" ")[2];
            try (var receiver = child("relay-receive", "--port", port, "--store",
                    identities.resolve("bob.p12").toString(), "--password-env", "P2P_M6_TEST_PASSWORD",
                    "--pin", alice.localFingerprint(), "--output", destination.toString(), "--accept")) {
                String code = receiver.await(s -> s.startsWith("PAIRING CODE ")).split(" ")[2];
                try (var sender = child("relay-send", "--port", port, "--code", code, "--store",
                        identities.resolve("alice.p12").toString(), "--password-env", "P2P_M6_TEST_PASSWORD",
                        "--pin", alice.localFingerprint(), "--file", source.toString())) {
                    sender.failure();
                    receiver.failure();
                    assertFalse(Files.exists(destination));
                    assertFalse(sender.lines.stream().anyMatch(s -> s.startsWith("SUCCESS")));
                    assertFalse(receiver.lines.stream().anyMatch(s -> s.startsWith("OFFER")));
                    assertNoSecret(sender.lines);
                    assertNoSecret(receiver.lines);
                    System.out.println("THREE JVM NEGATIVE PASS wrong pin: no offer, no destination");
                }
            }
        }
    }

    private Path source(int size) throws IOException {
        byte[] data = new byte[size]; // Test fixture only; production transfer is streaming.
        new java.util.Random(6421).nextBytes(data);
        byte[] marker = MARKER.getBytes(StandardCharsets.US_ASCII);
        if (size >= marker.length) {
            System.arraycopy(marker, 0, data, 0, marker.length);
        }
        return Files.write(directory.resolve("source.bin"), data);
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void assertNoSecret(List<String> lines) {
        String output = String.join("\n", lines);
        assertFalse(output.contains(new String(password)), "Password must never be emitted");
        assertFalse(output.contains("PRIVATE KEY"), "Private key must never be emitted");
    }

    private static InetSocketAddress address(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    private static String executable(String name) {
        return Path.of(System.getProperty("java.home"), "bin",
                name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }

    private static Child child(String... args) throws Exception {
        var locations = new ArrayList<String>();
        for (String name : List.of("vn.edu.p2p.cli.CliApplication", "vn.edu.p2p.core.PeerNode",
                "vn.edu.p2p.relay.RelayClient", "vn.edu.p2p.crypto.PeerTlsSession",
                "vn.edu.p2p.protocol.FrameCodec")) {
            locations.add(Path.of(Class.forName(name).getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toString());
        }
        var command = new ArrayList<>(List.of(executable("java"), "-cp",
                locations.stream().distinct().collect(Collectors.joining(java.io.File.pathSeparator)),
                "vn.edu.p2p.cli.CliApplication"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("P2P_M6_TEST_PASSWORD", new String(password));
        return new Child(builder.start());
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<>();
        private final Thread reader;

        private Child(Process process) {
            this.process = process;
            reader = Thread.ofVirtual().start(() -> {
                try (var input = process.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (lines.size() < 200) {
                            lines.add(line); // Bounded memory, no stdout/diagnostic dump of pairing codes.
                        }
                    }
                } catch (IOException ignored) {
                    // Process termination closes streams.
                }
            });
        }

        private String await(Predicate<String> condition) {
            return assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                while (true) {
                    for (String line : lines) {
                        if (condition.test(line)) {
                            return line;
                        }
                    }
                    if (!process.isAlive()) {
                        fail("CLI exited before expected state");
                    }
                    Thread.sleep(10);
                }
            });
        }

        private void success() throws Exception {
            finished(0);
        }

        private void failure() throws Exception {
            finished(2);
        }

        private void finished(int expected) throws Exception {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI process timeout");
            reader.join(2000);
            assertEquals(expected, process.exitValue(), "CLI exit status");
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            }
            reader.join(2000);
        }
    }

    /** Test-only TCP observer. Bounded capture of actual wire bytes, no TLS keys. */
    private static final class WireTap implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        private final long disconnectAfter;
        private volatile Socket client;
        private volatile Socket upstream;

        private WireTap(int relayPort) throws IOException { this(relayPort, Long.MAX_VALUE); }
        private WireTap(int relayPort, long disconnectAfter) throws IOException {
            this.disconnectAfter = disconnectAfter;
            workers.submit(() -> {
                try {
                    client = listener.accept();
                    upstream = new Socket("127.0.0.1", relayPort);
                    workers.submit(() -> copy(client, upstream));
                    workers.submit(() -> copy(upstream, client));
                } catch (IOException ignored) {
                    // close unblocks accept/connect.
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private synchronized byte[] captured() {
            return capture.toByteArray();
        }

        private void copy(Socket from, Socket to) {
            try {
                InputStream input = from.getInputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (this) {
                        if (capture.size() + count > 2 * 1024 * 1024) {
                            throw new IOException("Test capture exceeded bound");
                        }
                        capture.write(buffer, 0, count);
                        if (capture.size() >= disconnectAfter) { throw new IOException("Test network cutoff"); }
                    }
                    to.getOutputStream().write(buffer, 0, count);
                }
            } catch (IOException ignored) {
                // Connection teardown.
            } finally {
                closeSocket(from);
                closeSocket(to);
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            closeSocket(client);
            closeSocket(upstream);
            workers.shutdownNow();
        }

        private static void closeSocket(Socket socket) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Test cleanup.
                }
            }
        }
    }

    private static void generate(String name) throws Exception {
        var command = List.of(executable("keytool"), "-genkeypair", "-alias", "peer", "-keyalg", "RSA",
                "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "365", "-dname", "CN=" + name,
                "-ext", "BC=ca:false", "-ext", "KU=digitalSignature", "-ext", "EKU=serverAuth,clientAuth",
                "-storetype", "PKCS12", "-keystore", identities.resolve(name + ".p12").toString(),
                "-storepass:env", "P2P_M6_TEST_PASSWORD", "-noprompt");
        var builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(identities.resolve(name + "-keytool.log").toFile());
        builder.environment().put("P2P_M6_TEST_PASSWORD", new String(password));
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new IOException("Test keytool timeout");
        }
        assertEquals(0, process.exitValue(), "Test identity generation");
    }
}
