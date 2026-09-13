package com.wallet.dto;

import java.time.Instant;
import java.util.Map;

public record MetricsDashboard(
        Instant timestamp,
        long uptimeSeconds,
        RequestMetrics requests,
        DomainCounters domain,
        InfraMetrics infra
) {
    public record RequestMetrics(
            long totalRequests,
            long totalErrors,
            double errorRatePercent,
            Map<String, EndpointStats> endpoints
    ) {}

    public record EndpointStats(
            long count,
            double avgLatencyMs,
            double maxLatencyMs
    ) {}

    public record DomainCounters(
            long transfersCompleted,
            long transfersDeclined,
            long idempotentReplays,
            long walletsCreated
    ) {}

    public record InfraMetrics(
            double cpuUsagePercent,
            long heapUsedBytes,
            long heapMaxBytes,
            int hikariActiveConnections,
            int hikariIdleConnections,
            int hikariMaxConnections
    ) {}
}
