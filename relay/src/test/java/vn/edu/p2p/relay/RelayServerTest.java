package vn.edu.p2p.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class RelayServerTest {
    private static final RelayConfig LIMITS = new RelayConfig(12, 4, 500, 1000, 500, 3000, 30);

    @Test
    void pairsBothDirectionsAndConsumesCodeExactlyOnce() throws Exception {
        try (var relay = new RelayServer(address(0), LIMITS);
                var ticket = RelayClient.create(address(relay.port()), 2000);
                var right = RelayClient.join(address(relay.port()), ticket.code(), 2000);
                var left = ticket.awaitPair()) {
            assertThrows(IOException.class, () -> RelayClient.join(address(relay.port()), ticket.code(), 2000));
            left.setSoTimeout(2000);
            right.setSoTimeout(2000);
            left.getOutputStream().write(new byte[] {1, 2, 3});
            assertArrayEquals(new byte[] {1, 2, 3}, right.getInputStream().readNBytes(3));
            right.getOutputStream().write(99);
            assertEquals(99, left.getInputStream().read());
            // Receiving bytes can precede the forwarding worker's post-write counter update.
            eventually(() -> relay.stats().forwardedBytes() >= 4);
        }
    }

    @Test
    void wrongAndExpiredCodesAreRejectedAndWaitingSocketCloses() throws Exception {
        var config = new RelayConfig(12, 4, 500, 150, 500, 3000, 30);
        try (var relay = new RelayServer(address(0), config);
                var ticket = RelayClient.create(address(relay.port()), 2000)) {
            assertThrows(IOException.class, () -> RelayClient.join(address(relay.port()), "AAAAAAAAAAAA", 1000));
            eventually(() -> relay.stats().pendingSessions() == 0);
            assertThrows(IOException.class, ticket::awaitPair);
            assertThrows(IOException.class, () -> RelayClient.join(address(relay.port()), ticket.code(), 1000));
            eventually(() -> relay.stats().connections() == 0);
        }
    }

    @Test
    void limitsSessionsAndReleasesAbandonedHostByExpiry() throws Exception {
        var config = new RelayConfig(4, 1, 500, 300, 500, 3000, 30);
        try (var relay = new RelayServer(address(0), config)) {
            try (var abandoned = RelayClient.create(address(relay.port()), 1000)) {
                assertThrows(IOException.class, () -> RelayClient.create(address(relay.port()), 1000));
                assertTrue(relay.stats().pendingSessions() <= 1);
            }
            eventually(() -> relay.stats().connections() == 0 && relay.stats().pendingSessions() == 0);
            try (var next = RelayClient.create(address(relay.port()), 1000)) {
                assertNotNull(next.code());
            }
        }
    }

    @Test
    void limitsConnectionsAndClosesSlowMetadata() throws Exception {
        var config = new RelayConfig(2, 1, 250, 1000, 500, 3000, 30);
        try (var relay = new RelayServer(address(0), config);
                var one = new Socket("127.0.0.1", relay.port());
                var two = new Socket("127.0.0.1", relay.port())) {
            eventually(() -> relay.stats().connections() == 2);
            try (var excess = new Socket("127.0.0.1", relay.port())) {
                excess.setSoTimeout(1000);
                assertEquals(-1, excess.getInputStream().read());
            }
            eventually(() -> relay.stats().connections() == 0);
        }
    }

    @Test
    void rateLimitsRequestsFromSameAddress() throws Exception {
        var config = new RelayConfig(8, 2, 500, 1000, 500, 3000, 2);
        try (var relay = new RelayServer(address(0), config)) {
            for (int i = 0; i < 2; i++) {
                assertThrows(IOException.class,
                        () -> RelayClient.join(address(relay.port()), "AAAAAAAAAAAA", 1000));
            }
            assertThrows(IOException.class, () -> RelayClient.create(address(relay.port()), 1000));
            assertEquals(0, relay.stats().activeTunnels());
        }
    }

    @Test
    void idleTunnelAndPeerDisconnectCloseBothSocketsAndWorkers() throws Exception {
        try (var relay = new RelayServer(address(0), LIMITS)) {
            try (var ticket = RelayClient.create(address(relay.port()), 1000);
                    var right = RelayClient.join(address(relay.port()), ticket.code(), 1000);
                    var left = ticket.awaitPair()) {
                left.close();
                right.setSoTimeout(2000);
                assertEquals(-1, right.getInputStream().read());
                eventually(() -> relay.stats().connections() == 0 && relay.stats().activeTunnels() == 0);
            }
            try (var ticket = RelayClient.create(address(relay.port()), 1000);
                    var right = RelayClient.join(address(relay.port()), ticket.code(), 1000);
                    var left = ticket.awaitPair()) {
                eventually(() -> relay.stats().activeTunnels() == 0);
                assertEquals(-1, left.getInputStream().read());
                assertEquals(-1, right.getInputStream().read());
            }
        }
    }

    @Test
    void reverseSilenceDoesNotKillActiveOneWayTrafficButLifetimeIsBounded() throws Exception {
        var config = new RelayConfig(8, 2, 500, 1000, 250, 1300, 30);
        try (var relay = new RelayServer(address(0), config);
                var ticket = RelayClient.create(address(relay.port()), 1000);
                var right = RelayClient.join(address(relay.port()), ticket.code(), 1000);
                var left = ticket.awaitPair()) {
            right.setSoTimeout(1000);
            for (int i = 0; i < 8; i++) {
                left.getOutputStream().write(i);
                assertEquals(i, right.getInputStream().read());
                Thread.sleep(60);
            }
            assertEquals(1, relay.stats().activeTunnels());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean ended = false;
            while (System.nanoTime() < deadline) {
                try {
                    left.getOutputStream().write(42);
                    if (right.getInputStream().read() == -1) { ended = true; break; }
                } catch (IOException closed) { ended = true; break; }
                Thread.sleep(60);
            }
            assertTrue(ended, "Absolute lifetime must expire despite ongoing traffic");
            eventually(() -> relay.stats().activeTunnels() == 0);
        }
    }

    @Test
    void closeInterruptsPendingWaitAndServerWait() throws Exception {
        var relay = new RelayServer(address(0), LIMITS);
        try (relay; var ticket = RelayClient.create(address(relay.port()), 1000);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(() -> assertThrows(IOException.class, ticket::awaitPair));
            var stopping = executor.submit(() -> { relay.awaitClosed(); return true; });
            relay.close();
            waiting.get(2, TimeUnit.SECONDS);
            assertTrue(stopping.get(2, TimeUnit.SECONDS));
            assertEquals(0, relay.stats().connections());
        }
    }

    @Test
    void closingTicketCancelsAwaitWithoutHoldingMonitorAcrossRead() throws Exception {
        try (var relay = new RelayServer(address(0), LIMITS);
                var ticket = RelayClient.create(address(relay.port()), 1000);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(() -> assertThrows(IOException.class, ticket::awaitPair));
            ticket.close();
            waiting.get(2, TimeUnit.SECONDS);
            eventually(() -> relay.stats().connections() == 0);
        }
    }

    @Test
    void malformedAndTruncatedRequestsAreClosedWithoutSessions() throws Exception {
        try (var relay = new RelayServer(address(0), LIMITS)) {
            for (byte[] bytes : new byte[][] {
                    {0, 0, 0, 0, 1}, {0x50, 0x32, 0x52, 0x31, 99}, {0x50, 0x32}}) {
                try (var socket = new Socket("127.0.0.1", relay.port())) {
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write(bytes);
                    socket.shutdownOutput();
                    assertEquals(-1, socket.getInputStream().read());
                }
            }
            eventually(() -> relay.stats().connections() == 0);
            assertEquals(0, relay.stats().pendingSessions());
        }
    }

    @Test
    void backpressureCannotKeepBlockedForwarderAlivePastDeadline() throws Exception {
        var config = new RelayConfig(8, 2, 500, 1000, 300, 1000, 30);
        try (var relay = new RelayServer(address(0), config);
                var ticket = RelayClient.create(address(relay.port()), 1000);
                var right = RelayClient.join(address(relay.port()), ticket.code(), 1000);
                var left = ticket.awaitPair();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writing = executor.submit(() -> assertThrows(IOException.class, () -> {
                byte[] buffer = new byte[16384];
                while (true) {
                    left.getOutputStream().write(buffer);
                }
            }));
            // right intentionally never reads; scheduler closes both sockets, including blocked write.
            writing.get(4, TimeUnit.SECONDS);
            eventually(() -> relay.stats().connections() == 0 && relay.stats().activeTunnels() == 0);
        }
    }
    @Test
    void earlyJoinCannotInterleaveHostControlReplyWithTunnelBytes() throws Exception {
        try (var relay = new RelayServer(address(0), LIMITS);
                var host = new Socket("127.0.0.1", relay.port())) {
            host.setSoTimeout(2000);
            vn.edu.p2p.protocol.RelayProtocol.writeHeader(host.getOutputStream(), 1);
            assertEquals(1, vn.edu.p2p.protocol.RelayProtocol.readHeader(host.getInputStream()));
            String code = vn.edu.p2p.protocol.RelayProtocol.readCode(host.getInputStream());
            // Join before this host consumes the remaining CREATED TTL field.
            try (var joiner = RelayClient.join(address(relay.port()), code, 2000)) {
                joiner.getOutputStream().write(0x16);
                assertEquals(LIMITS.codeTtlMillis(), new java.io.DataInputStream(host.getInputStream()).readInt());
                assertEquals(2, vn.edu.p2p.protocol.RelayProtocol.readHeader(host.getInputStream()));
                assertEquals(0x16, host.getInputStream().read());
            }
        }
    }
    private static InetSocketAddress address(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            while (!condition.getAsBoolean()) {
                Thread.sleep(10);
            }
        });
    }
}
