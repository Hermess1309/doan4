package vn.edu.p2p.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.core.TransferProgress;
import vn.edu.p2p.core.TransferResult;
import vn.edu.p2p.core.TransferSession;

/** Single-file direct TLS demo. Passwords are never accepted as command-line values. */
public final class CliApplication {
    private CliApplication() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception failure) {
            // Do not dump exception chains, arguments, paths, passwords or file content.
            System.err.println("FAILED: " + failure.getClass().getSimpleName()
                    + ". Check inputs, peer pin, destination and timeouts; no success was confirmed.");
            System.exit(2);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println("""
                    Commands:
                      fingerprint --store PATH [--alias peer] [--password-env NAME]
                      receive --store PATH --pin FULL_SHA256 --port PORT --output LOCAL_PATH [--accept|--reject]
                      send --store PATH --pin FULL_SHA256 --host HOST --port PORT --file PATH
                    Options: --alias peer, --host 127.0.0.1, --timeout-ms 30000, --password-env NAME.
                    Supply pins only after comparison over an independent trusted channel.
                    Password defaults to console input; --password-env names an existing environment variable.
                    Use cli:installDist and the installed launcher for interactive console input.
                    One connection/file; no relay, resume or UI. Receive never chooses a path from remote metadata.
                    """);
            return;
        }
        String command = args[0];
        if (!Set.of("fingerprint", "receive", "send").contains(command)) {
            throw new IllegalArgumentException("Unknown command");
        }
        Map<String, String> options = parse(args);
        char[] password = password(options);
        PeerNode node;
        try {
            node = PeerNode.loadIdentity(Path.of(required(options, "--store")), password,
                    options.getOrDefault("--alias", "peer"));
        } finally {
            Arrays.fill(password, '\0');
        }
        System.out.println("LOCAL SHA-256: " + node.localFingerprint());
        if (command.equals("fingerprint")) {
            return;
        }
        String pin = required(options, "--pin");
        int timeout = Integer.parseInt(options.getOrDefault("--timeout-ms", "30000"));
        if (timeout <= 0 || timeout > 120000) {
            throw new IllegalArgumentException("Timeout outside range");
        }
        int port = Integer.parseInt(required(options, "--port"));
        if (port < 0 || port > 65535 || (command.equals("send") && port == 0)) {
            throw new IllegalArgumentException("Invalid port");
        }
        var address = new InetSocketAddress(options.getOrDefault("--host", "127.0.0.1"), port);
        if (command.equals("receive")) {
            Path destination = Path.of(required(options, "--output"));
            if (options.containsKey("--accept") && options.containsKey("--reject")) {
                throw new IllegalArgumentException("Conflicting decision flags");
            }
            try (var listener = node.listen(address, timeout, timeout)) {
                System.out.println("LISTENING " + listener.port());
                try (var session = listener.accept()) {
                    verify(session, pin);
                    var offer = session.receiveOffer();
                    System.out.println("OFFER " + offer.transferId() + " name=" + offer.fileName()
                            + " bytes=" + offer.size() + " SHA-256=" + offer.sha256());
                    boolean accepted = options.containsKey("--accept");
                    if (!accepted && !options.containsKey("--reject")) {
                        var console = System.console();
                        if (console == null) {
                            throw new IOException("Interactive approval requires console or explicit --accept/--reject");
                        }
                        accepted = "y".equalsIgnoreCase(console.readLine("Receive into chosen local path? [y/N] "));
                    }
                    if (!accepted) {
                        session.reject(offer.transferId());
                        System.out.println("REJECTED");
                        return;
                    }
                    result(session.accept(offer.transferId(), destination, CliApplication::progress));
                }
            }
        } else {
            Path source = Path.of(required(options, "--file"));
            try (var session = node.connect(address, Math.min(timeout, 10000), timeout)) {
                verify(session, pin);
                result(session.send(source, CliApplication::progress));
            }
        }
    }

    private static void verify(TransferSession session, String pin) throws IOException {
        var pending = session.info();
        System.out.println("PENDING_VERIFICATION " + pending.sessionId() + " (TCP only)");
        var verified = session.verify(pending.sessionId(), pin);
        System.out.println("VERIFIED " + verified.protocol().orElseThrow()
                + " REMOTE SHA-256: " + verified.remoteFingerprint().orElseThrow());
    }

    private static void progress(TransferProgress value) {
        System.out.println(value.state() + " " + value.transferId()
                + " " + value.bytesTransferred() + "/" + value.totalBytes());
    }

    private static void result(TransferResult result) {
        System.out.println("SUCCESS bytes=" + result.bytes() + " SHA-256=" + result.sha256());
    }

    private static char[] password(Map<String, String> options) throws IOException {
        if (options.containsKey("--password-env")) {
            String value = System.getenv(required(options, "--password-env"));
            if (value == null || value.isEmpty()) {
                throw new IOException("Password environment variable is missing");
            }
            return value.toCharArray();
        }
        var console = System.console();
        if (console == null) {
            throw new IOException("No console available for password input");
        }
        char[] value = console.readPassword("PKCS12 password: ");
        if (value == null) {
            throw new IOException("Password input cancelled");
        }
        return value;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing option");
        }
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        var options = new HashMap<String, String>();
        Set<String> values = Set.of("--store", "--alias", "--pin", "--host", "--port", "--output",
                "--file", "--timeout-ms", "--password-env");
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            String value;
            if (key.equals("--accept") || key.equals("--reject")) {
                value = "true";
            } else if (values.contains(key) && i + 1 < args.length) {
                value = args[++i];
            } else {
                throw new IllegalArgumentException("Unknown or incomplete option");
            }
            if (options.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate option");
            }
        }
        return options;
    }
}
