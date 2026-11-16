package vn.edu.p2p.relay;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import vn.edu.p2p.protocol.RelayProtocol;

/** Bounded byte bridge: no TLS context, certificate, file parser or payload logging. */
public final class RelayServer implements AutoCloseable {
    private static final long RATE_WINDOW = TimeUnit.SECONDS.toNanos(30);
    private final RelayConfig config;
    private final ServerSocket listener = new ServerSocket();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();
    private final SecureRandom random = new SecureRandom();
    private final Set<Connection> connections = new HashSet<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Set<Tunnel> tunnels = new HashSet<>();
    private final Map<InetAddress, Rate> rates = new HashMap<>();
    private final AtomicLong forwardedBytes = new AtomicLong();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private long globalWindow = System.nanoTime();
    private int globalAttempts;
    private boolean closed;

    public RelayServer(InetSocketAddress address, RelayConfig config) throws IOException {
        this.config = java.util.Objects.requireNonNull(config);
        try {
            listener.bind(address);
            sweeper.scheduleAtFixedRate(this::sweep, 50, 50, TimeUnit.MILLISECONDS);
            workers.execute(this::acceptLoop);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    public void awaitClosed() throws InterruptedException {
        stopped.await();
    }

    /** Aggregate metrics only; never retains payload or codes. */
    public synchronized Stats stats() {
        return new Stats(connections.size(), pending.size(), tunnels.size(), forwardedBytes.get());
    }

    public record Stats(int connections, int pendingSessions, int activeTunnels, long forwardedBytes) {}

    private void acceptLoop() {
        try {
            while (!listener.isClosed()) {
                Socket socket = listener.accept();
                Connection connection;
                synchronized (this) {
                    if (closed || connections.size() >= config.maxConnections()
                            || !allow(socket.getInetAddress(), System.nanoTime())) {
                        closeSocket(socket);
                        continue;
                    }
                    connection = new Connection(socket);
                    connections.add(connection);
                }
                try {
                    socket.setSoTimeout(config.metadataTimeoutMillis());
                    socket.setTcpNoDelay(true);
                    workers.execute(() -> route(connection));
                } catch (IOException | RuntimeException failure) {
                    connection.close();
                }
            }
        } catch (IOException ignored) {
            // Listener shutdown or fatal accept error; fail closed.
        } finally {
            close();
        }
    }

    // Bounded table + global cap, including CREATE/invalid/slow requests. No code oracle in errors.
    private boolean allow(InetAddress address, long now) {
        rates.entrySet().removeIf(e -> now - e.getValue().since >= RATE_WINDOW);
        if (now - globalWindow >= RATE_WINDOW) {
            globalWindow = now;
            globalAttempts = 0;
        }
        if (++globalAttempts > config.maxConnections() * 10) {
            return false;
        }
        Rate rate = rates.get(address);
        if (rate == null) {
            if (rates.size() >= 1024) {
                return false;
            }
            rate = new Rate(now);
            rates.put(address, rate);
        }
        return ++rate.attempts <= config.attemptsPerIpPerWindow();
    }

    private static final class Rate {
        private final long since;
        private int attempts;

        private Rate(long since) {
            this.since = since;
        }
    }

    private void route(Connection connection) {
        boolean retained = false;
        try {
            int command = RelayProtocol.readHeader(connection.socket.getInputStream());
            if (command == RelayProtocol.CREATE) {
                retained = create(connection);
            } else if (command == RelayProtocol.JOIN) {
                retained = join(connection, RelayProtocol.readCode(connection.socket.getInputStream()));
            }
        } catch (IOException | RuntimeException ignored) {
            // Untrusted metadata is never printed. All failures release ownership.
        } finally {
            if (!retained) {
                connection.close();
            }
        }
    }

    private boolean create(Connection connection) throws IOException {
        // Publish the code while already holding the host-output lock: even a JOIN that
        // observes the plaintext code early cannot interleave PAIRED with CREATED/TTL.
        synchronized (connection) {
            return createLocked(connection);
        }
    }

    private boolean createLocked(Connection connection) throws IOException {
        Pending ticket = null;
        synchronized (this) {
            if (!closed && pending.size() + tunnels.size() < config.maxSessions()) {
                String code;
                do {
                    var value = new StringBuilder(RelayProtocol.CODE_LENGTH);
                    for (int i = 0; i < RelayProtocol.CODE_LENGTH; i++) {
                        value.append(RelayProtocol.ALPHABET.charAt(random.nextInt(RelayProtocol.ALPHABET.length())));
                    }
                    code = value.toString();
                } while (pending.containsKey(code));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.codeTtlMillis());
                ticket = new Pending(code, connection, deadline);
                pending.put(code, ticket);
                connection.deadline = deadline;
            }
        }
        if (ticket == null) {
            RelayProtocol.writeHeader(connection.socket.getOutputStream(), RelayProtocol.BUSY);
            return false;
        }
        try {
            RelayProtocol.writeHeader(connection.socket.getOutputStream(), RelayProtocol.CREATED);
            RelayProtocol.writeCode(connection.socket.getOutputStream(), ticket.code);
            var output = new DataOutputStream(connection.socket.getOutputStream());
            output.writeInt(config.codeTtlMillis());
            output.flush();
            return true;
        } catch (IOException failure) {
            synchronized (this) {
                pending.remove(ticket.code, ticket);
            }
            throw failure;
        }
    }

    private boolean join(Connection joiner, String code) throws IOException {
        Tunnel tunnel = null;
        Pending expired = null;
        synchronized (this) {
            Pending ticket = pending.remove(code); // Single-use, even when handoff fails.
            if (ticket != null) {
                if (closed || System.nanoTime() >= ticket.deadline || ticket.host.done.get()) {
                    expired = ticket;
                } else {
                    tunnel = new Tunnel(ticket.host, joiner);
                    tunnels.add(tunnel);
                }
            }
        }
        if (expired != null) {
            expired.host.close();
        }
        if (tunnel == null) {
            RelayProtocol.writeHeader(joiner.socket.getOutputStream(), RelayProtocol.UNAVAILABLE);
            return false;
        }
        try {
            // Both control replies precede forwarding. No buffered reader can steal TLS bytes.
            synchronized (tunnel.left) {
                RelayProtocol.writeHeader(tunnel.left.socket.getOutputStream(), RelayProtocol.PAIRED);
            }
            RelayProtocol.writeHeader(tunnel.right.socket.getOutputStream(), RelayProtocol.PAIRED);
            Tunnel paired = tunnel;
            workers.execute(() -> paired.pump(paired.left, paired.right));
            workers.execute(() -> paired.pump(paired.right, paired.left));
            return true;
        } catch (IOException | RuntimeException failure) {
            tunnel.close();
            throw failure;
        }
    }

    private void sweep() {
        long now = System.nanoTime();
        ArrayList<Connection> timedOut = new ArrayList<>();
        ArrayList<Tunnel> idle = new ArrayList<>();
        synchronized (this) {
            pending.values().removeIf(p -> p.host.done.get() || now >= p.deadline);
            for (Connection connection : connections) {
                if (now >= connection.deadline) {
                    timedOut.add(connection);
                }
            }
            for (Tunnel tunnel : tunnels) {
                if (now - tunnel.activity >= TimeUnit.MILLISECONDS.toNanos(config.idleTimeoutMillis())) {
                    idle.add(tunnel);
                }
            }
        }
        timedOut.forEach(Connection::close);
        idle.forEach(Tunnel::close);
    }

    private final class Connection {
        private final Socket socket;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(config.metadataTimeoutMillis());

        private Connection(Socket socket) {
            this.socket = socket;
        }

        private void close() {
            if (done.compareAndSet(false, true)) {
                closeSocket(socket);
                synchronized (RelayServer.this) {
                    connections.remove(this);
                }
            }
        }
    }

    private record Pending(String code, Connection host, long deadline) {}

    private final class Tunnel {
        private final Connection left;
        private final Connection right;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile long activity = System.nanoTime();

        private Tunnel(Connection left, Connection right) {
            this.left = left;
            this.right = right;
            left.deadline = activity + TimeUnit.MILLISECONDS.toNanos(config.maxTunnelMillis());
            right.deadline = left.deadline;
        }

        private void pump(Connection from, Connection to) {
            byte[] buffer = new byte[16384];
            try {
                from.socket.setSoTimeout(Math.min(config.idleTimeoutMillis(), 1000));
                var input = from.socket.getInputStream();
                var output = to.socket.getOutputStream();
                while (!done.get()) {
                    int count;
                    try {
                        count = input.read(buffer);
                    } catch (SocketTimeoutException poll) {
                        continue; // Shared idle/lifetime sweeper also interrupts blocked writes.
                    }
                    if (count < 0) {
                        break;
                    }
                    activity = System.nanoTime();
                    output.write(buffer, 0, count);
                    forwardedBytes.addAndGet(count);
                }
            } catch (IOException ignored) {
                // No contents or exception chains in logs.
            } finally {
                close();
            }
        }

        private void close() {
            if (done.compareAndSet(false, true)) {
                left.close();
                right.close();
                synchronized (RelayServer.this) {
                    tunnels.remove(this);
                }
            }
        }
    }

    @Override
    public void close() {
        ArrayList<Connection> owned;
        ArrayList<Tunnel> active;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            owned = new ArrayList<>(connections);
            active = new ArrayList<>(tunnels);
            pending.clear();
            rates.clear();
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // Continue cleanup.
        }
        active.forEach(Tunnel::close);
        owned.forEach(Connection::close);
        sweeper.shutdownNow();
        workers.shutdownNow();
        stopped.countDown();
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // No further operations permitted on this socket.
        }
    }
}
