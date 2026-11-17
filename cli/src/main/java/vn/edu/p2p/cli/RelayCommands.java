package vn.edu.p2p.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.core.TransferSession;
import vn.edu.p2p.relay.RelayClient;
import vn.edu.p2p.relay.RelayConfig;
import vn.edu.p2p.relay.RelayServer;

/** CLI orchestration only. Core owns all TLS and file transfer logic. */
final class RelayCommands {
    private RelayCommands() {}

    static void server(Map<String, String> options) throws Exception {
        int port = Integer.parseInt(CliApplication.required(options, "--port"));
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        try (var server = new RelayServer(new InetSocketAddress(
                options.getOrDefault("--host", "127.0.0.1"), port), RelayConfig.defaults())) {
            Thread hook = new Thread(server::close, "relay-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                System.out.println("RELAY LISTENING " + server.port());
                server.awaitClosed();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shuttingDown) {
                    // JVM shutdown hook already owns cleanup.
                }
            }
        }
    }

    static void transfer(String command, Map<String, String> options, PeerNode node,
            InetSocketAddress address, int timeout, String pin) throws Exception {
        if (address.getPort() == 0) {
            throw new IllegalArgumentException("Relay port must be nonzero");
        }
        if (command.equals("relay-receive")) {
            try (var ticket = RelayClient.create(address, timeout)) {
                // Explicit user display, never emitted by server or included in diagnostics.
                System.out.println("PAIRING CODE " + ticket.code() + " TTL-MS " + ticket.ttlMillis());
                try (var session = node.attach(ticket.awaitPair(), PeerNode.TlsRole.SERVER, timeout)) {
                    CliApplication.verify(session, pin);
                    receive(session, options);
                }
            }
        } else {
            try (var session = node.attach(
                    RelayClient.join(address, CliApplication.required(options, "--code"), timeout),
                    PeerNode.TlsRole.CLIENT, timeout)) {
                CliApplication.verify(session, pin);
                CliApplication.result(session.send(
                        Path.of(CliApplication.required(options, "--file")), CliApplication::progress));
            }
        }
    }

    static void receive(TransferSession session, Map<String, String> options) throws IOException {
        Path destination = Path.of(CliApplication.required(options, "--output"));
        if (options.containsKey("--accept") && options.containsKey("--reject")) {
            throw new IllegalArgumentException("Conflicting decision flags");
        }
        var offer = session.receiveOffer();
        System.out.println("OFFER " + offer.transferId() + " name=" + offer.fileName()
                + " bytes=" + offer.size() + " SHA-256=" + offer.sha256());
        boolean accepted = options.containsKey("--accept");
        if (!accepted && !options.containsKey("--reject")) {
            var console = System.console();
            if (console == null) {
                throw new IOException("Interactive approval requires console or explicit decision");
            }
            accepted = "y".equalsIgnoreCase(console.readLine("Receive into chosen local path? [y/N] "));
        }
        if (!accepted) {
            session.reject(offer.transferId());
            System.out.println("REJECTED");
            return;
        }
        CliApplication.result(session.accept(offer.transferId(), destination, CliApplication::progress));
    }
}
