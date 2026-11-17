package vn.edu.p2p.relay;

/** Hard limits, in milliseconds. The server is a bounded MVP, not a public DDoS service. */
public record RelayConfig(int maxConnections, int maxSessions, int metadataTimeoutMillis,
        int codeTtlMillis, int idleTimeoutMillis, int maxTunnelMillis, int attemptsPerIpPerWindow) {
    public RelayConfig {
        if (maxConnections < 2 || maxConnections > 4096 || maxSessions < 1
                || maxSessions > maxConnections / 2 || metadataTimeoutMillis < 100
                || metadataTimeoutMillis > 30000 || codeTtlMillis < 100 || codeTtlMillis > 300000
                || idleTimeoutMillis < 100 || idleTimeoutMillis > 300000
                || maxTunnelMillis < idleTimeoutMillis || maxTunnelMillis > 3600000
                || attemptsPerIpPerWindow < 1 || attemptsPerIpPerWindow > 1000) {
            throw new IllegalArgumentException("Invalid relay limits");
        }
    }

    public static RelayConfig defaults() {
        return new RelayConfig(64, 32, 5000, 120000, 60000, 900000, 30);
    }
}
