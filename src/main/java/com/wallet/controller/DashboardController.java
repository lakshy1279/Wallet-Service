package com.wallet.controller;

import com.wallet.dto.MetricsDashboard;
import com.wallet.dto.MetricsDashboard.*;
import io.micrometer.core.instrument.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Human-readable metrics dashboard.
 * Raw Prometheus format stays at /metrics for scraping tools;
 * this endpoint presents the same data in clean JSON for humans.
 */
@RestController
public class DashboardController {

    private final MeterRegistry registry;

    public DashboardController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<MetricsDashboard> dashboard() {
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        // ── Domain counters ──────────────────────────────────────
        DomainCounters domain = new DomainCounters(
                counterValue("wallet.transfers.completed"),
                counterValue("wallet.transfers.declined"),
                counterValue("wallet.idempotent.replays"),
                counterValue("wallet.wallets.created")
        );

        // ── HTTP request stats ───────────────────────────────────
        Map<String, EndpointStats> endpoints = new LinkedHashMap<>();
        long totalRequests = 0;
        long totalErrors = 0;

        for (Meter meter : registry.getMeters()) {
            if (meter instanceof Timer timer && meter.getId().getName().equals("http.server.requests")) {
                String uri = timer.getId().getTag("uri");
                String status = timer.getId().getTag("status");
                String method = timer.getId().getTag("method");
                if (uri == null || "UNKNOWN".equals(uri)) continue;

                long count = timer.count();
                double avgMs = timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
                double maxMs = timer.max(java.util.concurrent.TimeUnit.MILLISECONDS);

                String key = method + " " + uri;
                endpoints.merge(key,
                        new EndpointStats(count, round(avgMs), round(maxMs)),
                        (a, b) -> new EndpointStats(
                                a.count() + b.count(),
                                round((a.avgLatencyMs() * a.count() + b.avgLatencyMs() * b.count())
                                        / (a.count() + b.count())),
                                Math.max(a.maxLatencyMs(), b.maxLatencyMs())
                        ));

                totalRequests += count;
                if (status != null && status.startsWith("5")) {
                    totalErrors += count;
                }
            }
        }

        double errorRate = totalRequests > 0
                ? round(totalErrors * 100.0 / totalRequests)
                : 0.0;

        RequestMetrics requests = new RequestMetrics(
                totalRequests, totalErrors, errorRate, endpoints
        );

        // ── Infrastructure ───────────────────────────────────────
        InfraMetrics infra = new InfraMetrics(
                round(gaugeValue("process.cpu.usage") * 100),
                (long) gaugeValue("jvm.memory.used", "area", "heap"),
                (long) gaugeValue("jvm.memory.max", "area", "heap"),
                (int) gaugeValue("hikaricp.connections.active"),
                (int) gaugeValue("hikaricp.connections.idle"),
                (int) gaugeValue("hikaricp.connections.max")
        );

        MetricsDashboard dashboard = new MetricsDashboard(
                Instant.now(), uptimeSeconds, requests, domain, infra
        );

        return ResponseEntity.ok(dashboard);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private long counterValue(String name) {
        Counter counter = registry.find(name).counter();
        return counter != null ? (long) counter.count() : 0;
    }

    private double gaugeValue(String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private double gaugeValue(String name, String tagKey, String tagValue) {
        Gauge gauge = registry.find(name).tag(tagKey, tagValue).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
