package com.repopilot.common.telemetry;

import io.micrometer.core.instrument.config.MeterFilter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for OpenTelemetry tracing sampler and Micrometer meter filters.
 *
 * Configurable properties:
 * - repopilot.telemetry.tracing.sampling-probability (Env: MANAGEMENT_TRACING_SAMPLING_PROBABILITY)
 *   Default: 1.0 (100% sampling). Valid range: [0.0, 1.0].
 *   Invalid or unparseable inputs default safely to 1.0 without crashing application context.
 */
@Configuration
public class TelemetryConfig {

    @Value("${repopilot.telemetry.tracing.sampling-probability:1.0}")
    private String samplingProbabilityRaw;

    /**
     * Configures parent-based trace sampling with configurable traceId ratio probability.
     * Safely bounds probability between 0.0 and 1.0, handling invalid/malformed configuration inputs gracefully.
     * Sampling decisions are strictly ratio-based and NEVER depend on tenant IDs, user identities,
     * roles, or business outcomes.
     */
    @Bean
    public Sampler openTelemetrySampler() {
        double probability = parseAndBoundProbability(samplingProbabilityRaw);
        return Sampler.parentBased(Sampler.traceIdRatioBased(probability));
    }

    public static double parseAndBoundProbability(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1.0;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isNaN(value)) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /**
     * MeterFilter that intercepts all Micrometer metrics (including http.server.requests)
     * and normalizes the 'uri' tag values to prevent Prometheus label cardinality explosion.
     */
    @Bean
    public MeterFilter prometheusCardinalityMeterFilter() {
        return MeterFilter.replaceTagValues("uri", uri -> TelemetryRedactionFilter.normalizeRoute(uri));
    }
}
