package com.example.bankingrisk.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MetricsService {

    private final MeterRegistry registry;

    // Cached counters for risk-level and alert-decision dimensions
    private final ConcurrentMap<String, Counter> riskLevelCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> alertDecisionCounters = new ConcurrentHashMap<>();

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordTransferCreation(Timer.Sample sample, String riskLevel) {
        sample.stop(Timer.builder("transfer.creation.latency")
            .description("End-to-end latency of transfer creation (core ledger only, excludes async AI)")
            .tag("risk_level", riskLevel)
            .register(registry));
    }

    public void recordRiskEvaluation(Timer.Sample sample, String riskLevel) {
        sample.stop(Timer.builder("risk.evaluation.latency")
            .description("Latency of deterministic risk rule evaluation")
            .tag("risk_level", riskLevel)
            .register(registry));
    }

    public void recordAiNarrative(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("ai.narrative.latency")
            .description("Async AI narrative generation latency (measured off the critical path)")
            .tag("outcome", outcome)
            .register(registry));
    }

    public void recordIdempotencyCacheHit() {
        registry.counter("idempotency.cache.hit").increment();
    }

    public void recordIdempotencyCacheMiss() {
        registry.counter("idempotency.cache.miss").increment();
    }

    public void incrementRiskLevelCount(String riskLevel) {
        riskLevelCounters.computeIfAbsent(
            riskLevel,
            lvl -> Counter.builder("risk.level.count")
                .description("Number of transfers evaluated at each risk level")
                .tag("level", lvl)
                .register(registry)
        ).increment();
    }

    public void incrementAlertDecision(String decision) {
        alertDecisionCounters.computeIfAbsent(
            decision,
            d -> Counter.builder("alert.decision.count")
                .description("Number of analyst alert decisions (APPROVED / REJECTED)")
                .tag("decision", d)
                .register(registry)
        ).increment();
    }
}
