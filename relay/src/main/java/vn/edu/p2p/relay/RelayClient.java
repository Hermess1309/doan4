package vn.edu.p2p.relay;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import vn.edu.p2p.protocol.RelayProtocol;

/** Outbound TCP routing only. Returned sockets MUST be authenticated with peer TLS before app I/O. */
public final class RelayClient {
    private RelayClient() {}

    public static Pending create(InetSocketAddress relay, int timeoutMillis) throws IOException {
        Socket socket = connect(relay, timeoutMillis);
        try {
            RelayProtocol.writeHeader(socket.getOutputStream(), RelayProtocol.CREATE);
            expect(socket, RelayProtocol.CREATED);
            String code = RelayProtocol.readCode(socket.getInputStream());
            int ttl = new DataInputStream(socket.getInputStream()).readInt();
            if (ttl < 100 || ttl > 300000) {
                throw new IOException("Invalid relay expiry");
            }
            socket.setSoTimeout(ttl + 1000);
            return new Pending(socket, code, ttl);
        } catch (IOException | RuntimeException failure) {
            closeFailed(socket, failure);
            throw failure;
        }
    }

    public static Socket join(InetSocketAddress relay, String code, int timeoutMillis) throws IOException {
        RelayProtocol.validateCode(code);
        Socket socket = connect(relay, timeoutMillis);
        try {
            RelayProtocol.writeHeader(socket.getOutputStream(), RelayProtocol.JOIN);
            RelayProtocol.writeCode(socket.getOutputStream(), code);
            expect(socket, RelayProtocol.PAIRED);
            return socket;
        } catch (IOException | RuntimeException failure) {
            closeFailed(socket, failure);
            throw failure;
        }
    }

    private static Socket connect(InetSocketAddress relay, int timeout) throws IOException {
        if (timeout <= 0 || timeout > 120000) {
            throw new IllegalArgumentException("Invalid timeout");
        }
        Socket socket = new Socket();
        try {
            socket.connect(relay, Math.min(timeout, 10000));
            socket.setSoTimeout(timeout);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException | RuntimeException failure) {
            closeFailed(socket, failure);
            throw failure;
        }
    }

    private static void expect(Socket socket, int expected) throws IOException {
        if (RelayProtocol.readHeader(socket.getInputStream()) != expected) {
            throw new IOException("Relay request unavailable or capacity limited");
        }
    }

    private static void closeFailed(Socket socket, Exception failure) {
        try {
            socket.close();
        } catch (IOException closing) {
            failure.addSuppressed(closing);
        }
    }

    /** Single-use ticket. Closing interrupts awaitPair; ownership moves to caller on success. */
    public static final class Pending implements AutoCloseable {
        private final Socket socket;
        private final String code;
        private final int ttlMillis;
        private boolean awaiting;
        private boolean detached;
        private boolean closed;

        private Pending(Socket socket, String code, int ttlMillis) {
            this.socket = socket;
            this.code = code;
            this.ttlMillis = ttlMillis;
        }

        public String code() {
            return code;
        }

        public int ttlMillis() {
            return ttlMillis;
        }

        public Socket awaitPair() throws IOException {
            synchronized (this) {
                if (awaiting || detached || closed) {
                    throw new IOException("Ticket already used or closed");
                }
                awaiting = true;
            }
            try {
                expect(socket, RelayProtocol.PAIRED);
                synchronized (this) {
                    if (closed) {
                        throw new IOException("Ticket closed");
                    }
                    detached = true;
                    return socket;
                }
            } catch (IOException | RuntimeException failure) {
                closeFailed(socket, failure);
                throw failure;
            }
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            if (!detached) {
                socket.close();
            }
        }
    }
}
