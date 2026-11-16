package vn.edu.p2p.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class PeerTlsSessionTest {
    @TempDir
    static Path directory;
    private static char[] password;
    private static LocalIdentity alice;
    private static LocalIdentity bob;
    private static LocalIdentity changedBob;

    @BeforeAll
    static void generateIndependentIdentities() throws Exception {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        password = HexFormat.of().formatHex(random).toCharArray();
        generate("alice");
        generate("bob");
        generate("changed-bob");
        generate("expired", "-startdate", "-2d", "-validity", "1");
        generate("weak", "-keysize", "2048");
        alice = identity("alice");
        bob = identity("bob");
        changedBob = identity("changed-bob");
    }

    @AfterAll
    static void clearPassword() {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void fingerprintsUseDerAndSupportFullHexFormats() throws Exception {
        var certificate = certificate("alice");
        String expected = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        assertEquals(expected, alice.fingerprint());
        assertEquals(expected, Fingerprint.sha256(certificate));
        String colonHex = HexFormat.ofDelimiter(":").formatHex(HexFormat.of().parseHex(expected));
        assertTrue(Fingerprint.matches(expected, colonHex));
        assertEquals(expected, Fingerprint.canonical(colonHex));
        assertFalse(Fingerprint.matches(alice.fingerprint(), bob.fingerprint()));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.canonical("1234"));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.canonical("x".repeat(64)));
    }

    @Test
    void pendingSessionsBlockAllApplicationAccessAndSendNoTlsBytes() throws Exception {
        try (var pair = pair(bob, 2000)) {
            var before = pair.client.info();
            assertEquals(TlsSessionInfo.State.PENDING_VERIFICATION, before.state());
            assertEquals(alice.fingerprint(), before.localFingerprint());
            assertTrue(before.remoteFingerprint().isEmpty());
            assertTrue(before.protocol().isEmpty());
            assertNotEquals(before.sessionId(), pair.server.info().sessionId());
            assertThrows(SSLPeerUnverifiedException.class, pair.client::input);
            assertThrows(SSLPeerUnverifiedException.class, pair.client::output);
            assertThrows(SSLPeerUnverifiedException.class, pair.server::input);
            assertThrows(SSLPeerUnverifiedException.class, pair.server::output);
            pair.serverSocket.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, () -> pair.serverSocket.getInputStream().read());
        }
    }

    @Test
    void mutuallyAuthenticatesDistinctCertificatesUsingOnlyTls13() throws Exception {
        try (var pair = pair(bob, 5000)) {
            UUID sessionId = pair.client.info().sessionId();
            verifyBoth(pair);
            var client = pair.client.info();
            var server = pair.server.info();
            assertEquals(TlsSessionInfo.State.VERIFIED, client.state());
            assertEquals(TlsSessionInfo.State.VERIFIED, server.state());
            assertEquals(sessionId, client.sessionId());
            assertEquals("TLSv1.3", client.protocol().orElseThrow());
            assertEquals("TLSv1.3", server.protocol().orElseThrow());
            assertEquals(client.cipherSuite(), server.cipherSuite());
            assertEquals(bob.fingerprint(), client.remoteFingerprint().orElseThrow());
            assertEquals(alice.fingerprint(), server.remoteFingerprint().orElseThrow());
            assertNotEquals(client.localFingerprint(), client.remoteFingerprint().orElseThrow());
        }
    }

    @Test
    void verifiedPeersTransferSmallBinaryDataInBothDirections() throws Exception {
        try (var pair = pair(bob, 5000)) {
            verifyBoth(pair);
            byte[] data = {0, 1, 127, -128, -1};
            var cachedOutput = pair.client.output();
            var cachedInput = pair.client.input();
            cachedOutput.write(data);
            cachedOutput.flush();
            assertArrayEquals(data, pair.server.input().readNBytes(data.length));
            pair.server.output().write(data);
            pair.server.output().flush();
            assertArrayEquals(data, cachedInput.readNBytes(data.length));
            pair.client.close();
            assertThrows(SSLPeerUnverifiedException.class, () -> cachedOutput.write(1));
            assertThrows(SSLPeerUnverifiedException.class, cachedInput::read);
            assertTrue(pair.clientSocket.isClosed());
        }
    }

    @Test
    void wrongServerPinRejectsHandshakeAndClosesConnection() throws Exception {
        try (var pair = pair(bob, 3000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = threads.submit(() -> attempt(pair.server, alice.fingerprint()));
            assertNotNull(attempt(pair.client, alice.fingerprint()));
            assertNotNull(server.get(8, TimeUnit.SECONDS));
            assertClosed(pair.client, pair.clientSocket);
            assertClosed(pair.server, pair.serverSocket);
        }
    }

    @Test
    void serverAlsoRequiresTheCorrectClientCertificatePin() throws Exception {
        try (var pair = pair(bob, 3000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = threads.submit(() -> attempt(pair.server, bob.fingerprint()));
            IOException clientFailure = attempt(pair.client, bob.fingerprint());
            assertNotNull(server.get(8, TimeUnit.SECONDS));
            assertClosed(pair.server, pair.serverSocket);
            // TLS 1.3 client may finish locally before server processes its final flight.
            if (clientFailure == null) {
                assertThrows(IOException.class, () -> pair.client.input().read());
            }
            assertClosed(pair.client, pair.clientSocket);
        }
    }

    @Test
    void rotatedCertificateDoesNotInheritPreviousVerification() throws Exception {
        UUID oldId;
        try (var original = pair(bob, 5000)) {
            verifyBoth(original);
            oldId = original.client.info().sessionId();
        }
        try (var changed = pair(changedBob, 3000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            assertNotEquals(oldId, changed.client.info().sessionId());
            assertEquals(TlsSessionInfo.State.PENDING_VERIFICATION, changed.client.info().state());
            assertTrue(changed.client.info().remoteFingerprint().isEmpty());
            assertThrows(SSLPeerUnverifiedException.class, changed.client::output);
            var server = threads.submit(() -> attempt(changed.server, alice.fingerprint()));
            assertNotNull(attempt(changed.client, bob.fingerprint()));
            assertNotNull(server.get(8, TimeUnit.SECONDS));
            assertClosed(changed.client, changed.clientSocket);
        }
    }

    @Test
    void staleUiConfirmationCannotVerifyAnotherSession() throws Exception {
        try (var pair = pair(bob, 1000)) {
            assertThrows(SSLPeerUnverifiedException.class,
                    () -> pair.client.verify(pair.server.info().sessionId(), bob.fingerprint()));
            assertClosed(pair.client, pair.clientSocket);
        }
    }

    @Test
    void invalidFingerprintAndExplicitRejectionCloseTransport() throws Exception {
        try (var pair = pair(bob, 1000)) {
            assertThrows(IOException.class, () -> pair.client.verify(pair.client.info().sessionId(), "short"));
            assertClosed(pair.client, pair.clientSocket);
            pair.server.close();
            assertClosed(pair.server, pair.serverSocket);
        }
    }

    @Test
    void handshakeWithSilentPeerTimesOutAndCloses() throws Exception {
        try (var pair = pair(bob, 200)) {
            assertNotNull(attempt(pair.client, bob.fingerprint()));
            assertClosed(pair.client, pair.clientSocket);
        }
    }

    @Test
    void closeCanCancelHandshakeWithoutResurrectingVerification() throws Exception {
        try (var pair = pair(bob, 5000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var handshake = threads.submit(() -> attempt(pair.client, bob.fingerprint()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (pair.client.info().state() == TlsSessionInfo.State.PENDING_VERIFICATION
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(TlsSessionInfo.State.HANDSHAKING, pair.client.info().state());
            pair.client.close();
            assertNotNull(handshake.get(5, TimeUnit.SECONDS));
            assertClosed(pair.client, pair.clientSocket);
        }
    }

    @Test
    void rejectsExpiredWeakWrongPasswordAndInvalidStoreConfiguration() throws Exception {
        assertThrows(CertificateExpiredException.class, () -> identity("expired"));
        assertThrows(CertificateException.class, () -> identity("weak"));
        char[] wrong = UUID.randomUUID().toString().toCharArray();
        try {
            assertThrows(IOException.class, () -> LocalIdentity.load(directory.resolve("alice.p12"), wrong, "peer"));
        } finally {
            Arrays.fill(wrong, '\0');
        }
        assertThrows(java.security.GeneralSecurityException.class,
                () -> LocalIdentity.load(directory.resolve("alice.p12"), password, "missing"));
        assertThrows(IOException.class, () -> LocalIdentity.load(directory, password, "peer"));
        Path large = directory.resolve("oversized.p12");
        Files.write(large, new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> LocalIdentity.load(large, password, "peer"));
        assertThrows(IllegalArgumentException.class,
                () -> PeerTlsSession.prepare(new Socket(), alice, PeerTlsSession.Role.CLIENT, 1000));
    }

    @Test
    void apiDoesNotLogPasswordsPrivateKeysOrApplicationContent() throws Exception {
        var captured = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        var logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                try {
                    captured.write(record.getMessage().getBytes(StandardCharsets.UTF_8));
                } catch (IOException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger root = Logger.getLogger("");
        try (var stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            System.setErr(stream);
            root.addHandler(logHandler);
            LocalIdentity.load(directory.resolve("alice.p12"), password, "peer");
            try (var pair = pair(bob, 5000)) {
                verifyBoth(pair);
                pair.client.output().write(new byte[] {7, 8, 9});
                pair.client.output().flush();
                assertArrayEquals(new byte[] {7, 8, 9}, pair.server.input().readNBytes(3));
            }
            assertTrue(captured.size() == 0, "Security API must not emit console or JUL output");
        } finally {
            root.removeHandler(logHandler);
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }


    @Test
    void refusesTls12WithoutProtocolDowngrade() throws Exception {
        var context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(bob.keyManagers(), new javax.net.ssl.TrustManager[] {
                new PinnedTrustManager(alice.fingerprint())}, null);
        try (var listener = (javax.net.ssl.SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 4, InetAddress.getLoopbackAddress());
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setEnabledProtocols(new String[] {"TLSv1.2"});
            listener.setSoTimeout(3000);
            var server = threads.submit(() -> {
                try (var socket = (javax.net.ssl.SSLSocket) listener.accept()) {
                    socket.setSoTimeout(3000);
                    socket.startHandshake();
                    return false;
                } catch (IOException rejected) {
                    return true;
                }
            });
            var tcp = new Socket(listener.getInetAddress(), listener.getLocalPort());
            try (var client = PeerTlsSession.prepare(tcp, alice, PeerTlsSession.Role.CLIENT, 3000)) {
                assertNotNull(attempt(client, bob.fingerprint()));
                assertClosed(client, tcp);
                assertTrue(server.get(8, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void missingClientCertificateIsRejectedByServer() throws Exception {
        try (var pair = pair(bob, 3000);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = threads.submit(() -> attempt(pair.server, alice.fingerprint()));
            var context = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
            context.init(new javax.net.ssl.KeyManager[0], new javax.net.ssl.TrustManager[] {
                    new PinnedTrustManager(bob.fingerprint())}, null);
            try (var unauthenticated = (javax.net.ssl.SSLSocket) context.getSocketFactory().createSocket(
                    pair.clientSocket, "localhost", pair.clientSocket.getPort(), true)) {
                unauthenticated.setEnabledProtocols(new String[] {"TLSv1.3"});
                unauthenticated.setSoTimeout(3000);
                assertThrows(IOException.class, () -> {
                    unauthenticated.startHandshake();
                    unauthenticated.getInputStream().read();
                });
                assertNotNull(server.get(8, TimeUnit.SECONDS));
                assertClosed(pair.server, pair.serverSocket);
            }
        }
    }

    private static IOException attempt(PeerTlsSession session, String pin) {
        try {
            session.verify(session.info().sessionId(), pin);
            return null;
        } catch (IOException expected) {
            return expected;
        }
    }

    private static void verifyBoth(Pair pair) throws Exception {
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = threads.submit(() -> pair.server.verify(
                    pair.server.info().sessionId(), alice.fingerprint()));
            pair.client.verify(pair.client.info().sessionId(), pair.server.info().localFingerprint());
            server.get(10, TimeUnit.SECONDS);
        }
    }

    private static Pair pair(LocalIdentity serverIdentity, int timeout) throws IOException {
        try (var listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3000);
            Socket clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()), 2000);
            Socket serverSocket = listener.accept();
            return new Pair(clientSocket, serverSocket,
                    PeerTlsSession.prepare(clientSocket, alice, PeerTlsSession.Role.CLIENT, timeout),
                    PeerTlsSession.prepare(serverSocket, serverIdentity, PeerTlsSession.Role.SERVER, timeout));
        }
    }

    private record Pair(Socket clientSocket, Socket serverSocket, PeerTlsSession client, PeerTlsSession server)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                client.close();
            } finally {
                server.close();
            }
        }
    }

    private static void assertClosed(PeerTlsSession session, Socket socket) {
        assertEquals(TlsSessionInfo.State.CLOSED, session.info().state());
        assertTrue(socket.isClosed());
        assertThrows(SSLPeerUnverifiedException.class, session::output);
    }

    private static LocalIdentity identity(String name) throws Exception {
        return LocalIdentity.load(directory.resolve(name + ".p12"), password, "peer");
    }

    private static X509Certificate certificate(String name) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(directory.resolve(name + ".p12"))) {
            store.load(input, password);
        }
        return (X509Certificate) store.getCertificate("peer");
    }

    private static void generate(String name, String... extra) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "peer", "-keyalg", "RSA", "-keysize", "3072",
                "-sigalg", "SHA256withRSA", "-validity", "365", "-dname", "CN=" + name,
                "-ext", "BC=ca:false", "-ext", "KU=digitalSignature",
                "-ext", "EKU=serverAuth,clientAuth", "-storetype", "PKCS12",
                "-keystore", directory.resolve(name + ".p12").toString(),
                "-storepass:env", "P2P_TEST_STORE_PASS", "-noprompt"));
        arguments.addAll(List.of(extra));
        Path diagnostics = directory.resolve(name + "-keytool.log");
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(diagnostics.toFile());
        builder.environment().put("P2P_TEST_STORE_PASS", new String(password));
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new IOException("Test keytool timed out");
        }
        assertEquals(0, process.exitValue(), "Test keytool generation failed; inspect temporary diagnostics locally");
    }
}
