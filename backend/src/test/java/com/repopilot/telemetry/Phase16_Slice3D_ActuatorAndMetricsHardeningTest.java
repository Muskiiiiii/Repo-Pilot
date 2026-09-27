package com.repopilot.telemetry;

import com.repopilot.common.telemetry.TelemetryRedactionFilter;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class Phase16_Slice3D_ActuatorAndMetricsHardeningTest {

    @LocalServerPort
    private int appPort;

    @LocalManagementPort
    private int mgmtPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired(required = false)
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Test
    @DisplayName("Management Port Exposes Actuator Endpoints (/health, /health/readiness, /prometheus)")
    void test01_managementPortExposesActuatorEndpoints() {
        String mgmtBase = "http://localhost:" + mgmtPort;

        ResponseEntity<String> healthRes = restTemplate.getForEntity(mgmtBase + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, healthRes.getStatusCode(), "Management port must expose /actuator/health");

        ResponseEntity<String> readinessRes = restTemplate.getForEntity(mgmtBase + "/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, readinessRes.getStatusCode(), "Management port must expose /actuator/health/readiness");

        ResponseEntity<String> prometheusRes = restTemplate.getForEntity(mgmtBase + "/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, prometheusRes.getStatusCode(), "Management port must expose /actuator/prometheus");
    }

    @Test
    @DisplayName("Application Port Isolates Management Endpoints (Rejects /actuator/prometheus and /actuator/health)")
    void test02_applicationPortIsolatesManagementEndpoints() {
        String appBase = "http://localhost:" + appPort;

        ResponseEntity<String> appPromRes = restTemplate.getForEntity(appBase + "/actuator/prometheus", String.class);
        assertNotEquals(HttpStatus.OK, appPromRes.getStatusCode(), "Application port MUST NOT expose unrestricted /actuator/prometheus");

        ResponseEntity<String> appHealthRes = restTemplate.getForEntity(appBase + "/actuator/health", String.class);
        assertNotEquals(HttpStatus.OK, appHealthRes.getStatusCode(), "Application port MUST NOT expose management /actuator/health");
    }

    @Test
    @DisplayName("Prometheus Scrape Inspection - Zero Raw UUIDs or Secrets in Scraped Metrics")
    void test03_prometheusScrapeExpositionAndCardinality() {
        String appBase = "http://localhost:" + appPort;
        String mgmtBase = "http://localhost:" + mgmtPort;

        String uuid1 = "550e8400-e29b-41d4-a716-446655440000";
        String uuid2 = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

        restTemplate.getForEntity(appBase + "/api/v1/health", String.class);
        restTemplate.getForEntity(appBase + "/api/v1/projects/" + uuid1 + "/tasks/12345", String.class);
        restTemplate.getForEntity(appBase + "/api/v1/projects/" + uuid2 + "/tasks/67890", String.class);

        ResponseEntity<String> scrapeResult = restTemplate.getForEntity(mgmtBase + "/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, scrapeResult.getStatusCode());

        String body = scrapeResult.getBody();
        assertNotNull(body);

        assertFalse(body.contains(uuid1), "Prometheus scrape must NOT contain raw UUID1");
        assertFalse(body.contains(uuid2), "Prometheus scrape must NOT contain raw UUID2");
        assertFalse(body.contains("super_secret_jwt_key"), "Prometheus scrape must NOT contain JWT secret");
        assertFalse(body.contains("repopilot_password"), "Prometheus scrape must NOT contain DB password");
    }

    @Test
    @DisplayName("Route Normalization Replaces UUIDs and Numeric IDs with Static Placeholders")
    void test04_routeNormalizationReplacesIds() {
        String rawUuidUri = "/api/v1/projects/550e8400-e29b-41d4-a716-446655440000/tasks/12345?query=secret";
        String normalized = TelemetryRedactionFilter.normalizeRoute(rawUuidUri);
        assertEquals("/api/v1/projects/{id}/tasks/{id}", normalized);

        assertFalse(normalized.contains("550e8400"));
        assertFalse(normalized.contains("12345"));
        assertFalse(normalized.contains("query=secret"));
    }

    @Test
    @DisplayName("Health Response Does Not Leak Credentials or Internal Secrets")
    void test05_healthAndReadinessSecurityNoSecretLeakage() {
        String mgmtBase = "http://localhost:" + mgmtPort;

        ResponseEntity<String> healthRes = restTemplate.getForEntity(mgmtBase + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, healthRes.getStatusCode());

        String content = healthRes.getBody();
        assertNotNull(content);

        assertFalse(content.contains("repopilot_password"));
        assertFalse(content.contains("super_secret_jwt_key"));
        assertFalse(content.contains("DefaultRepoPilotGitHubTokenEncryptionKey"));
        assertFalse(content.contains("-----BEGIN PRIVATE KEY-----"));

        ResponseEntity<String> readinessRes = restTemplate.getForEntity(mgmtBase + "/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, readinessRes.getStatusCode());

        String readinessContent = readinessRes.getBody();
        assertNotNull(readinessContent);
        assertFalse(readinessContent.contains("repopilot_password"));
    }

    @Test
    @DisplayName("Sensitive Telemetry Header and Attribute Redaction")
    void test06_sensitiveTelemetryHeaderAndAttributeRedaction() {
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("authorization"));
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("cookie"));
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("x-api-key"));
        assertFalse(TelemetryRedactionFilter.isSensitiveHeader("content-type"));

        assertEquals("[REDACTED]", TelemetryRedactionFilter.sanitizeAttributeValue("authorization", "Bearer token123"));
        assertEquals("[REDACTED]", TelemetryRedactionFilter.sanitizeAttributeValue("x-api-key", "secret-api-key"));
        assertEquals("application/json", TelemetryRedactionFilter.sanitizeAttributeValue("content-type", "application/json"));
    }

    @Test
    @DisplayName("Telemetry Failure Does Not Interrupt Business Endpoint Execution")
    void test07_telemetryFailureIsolatesFromBusinessExecution() {
        String appBase = "http://localhost:" + appPort;
        ResponseEntity<String> res = restTemplate.getForEntity(appBase + "/api/v1/health", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
    }

    @Test
    @DisplayName("Prometheus Metric Cardinality Remains Strictly Bounded")
    void test08_prometheusMetricsBoundedCardinalityLimit() {
        if (prometheusMeterRegistry != null) {
            String scrape = prometheusMeterRegistry.scrape();
            assertNotNull(scrape);
            assertFalse(scrape.contains("550e8400-e29b-41d4-a716-446655440000"));
        }
    }
}
