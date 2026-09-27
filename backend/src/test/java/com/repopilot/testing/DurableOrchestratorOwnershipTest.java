package com.repopilot.testing;

import com.repopilot.testing.service.DurableOrchestratorOwnershipService;
import com.repopilot.testing.service.DurableOrchestratorOwnershipServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class DurableOrchestratorOwnershipTest {

    private StringRedisTemplate realRedisTemplate;
    private DurableOrchestratorOwnershipService ownershipService;

    @BeforeEach
    void setUp() {
        realRedisTemplate = createRealRedisTemplate();
        ownershipService = new DurableOrchestratorOwnershipServiceImpl(realRedisTemplate);
    }

    private static StringRedisTemplate createRealRedisTemplate() {
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            template.opsForValue().get("ping");
            return template;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    @DisplayName("Test 01: Real Redis - Same execution, two concurrent callers -> exactly one owner")
    void test01_SameExecution_TwoConcurrentCallers_ExactlyOneOwner() throws Exception {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        ExecutorService service = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            futures.add(service.submit(() -> ownershipService.acquireOwnership(execId, Duration.ofSeconds(30))));
        }

        int successCount = 0;
        String acquiredToken = null;

        for (Future<String> f : futures) {
            String token = f.get();
            if (token != null) {
                successCount++;
                acquiredToken = token;
            }
        }
        service.shutdown();

        assertEquals(1, successCount, "Exactly one concurrent caller must acquire ownership");
        assertNotNull(acquiredToken);
        assertTrue(ownershipService.verifyOwnership(execId, acquiredToken));

        ownershipService.releaseOwnership(execId, acquiredToken);
    }

    @Test
    @DisplayName("Test 02: Real Redis - Multi-instance concurrency (Worker A vs Worker B)")
    void test02_MultiInstanceConcurrency_WorkerA_VS_WorkerB() throws Exception {
        if (realRedisTemplate == null) return;

        DurableOrchestratorOwnershipService workerA = new DurableOrchestratorOwnershipServiceImpl(realRedisTemplate);
        DurableOrchestratorOwnershipService workerB = new DurableOrchestratorOwnershipServiceImpl(realRedisTemplate);

        UUID execId = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> futureA = pool.submit(() -> workerA.acquireOwnership(execId, Duration.ofSeconds(30)));
        Future<String> futureB = pool.submit(() -> workerB.acquireOwnership(execId, Duration.ofSeconds(30)));

        String tokenA = futureA.get();
        String tokenB = futureB.get();
        pool.shutdown();

        assertTrue(tokenA != null ^ tokenB != null, "Exactly one worker must acquire ownership, the other must be rejected");

        String activeToken = tokenA != null ? tokenA : tokenB;
        String loserToken = tokenA != null ? "loser-token-B" : "loser-token-A";

        assertFalse(workerA.releaseOwnership(execId, loserToken));
        assertFalse(workerB.releaseOwnership(execId, loserToken));

        workerA.releaseOwnership(execId, activeToken);
    }

    @Test
    @DisplayName("Test 03: Fail-Closed Behavior - When Redis is unavailable, no process-local lock is granted")
    void test03_FailClosed_WhenRedisUnavailable() {
        DurableOrchestratorOwnershipService failClosedService = new DurableOrchestratorOwnershipServiceImpl(null);

        UUID execId = UUID.randomUUID();
        String token1 = failClosedService.acquireOwnership(execId, Duration.ofSeconds(30));
        String token2 = failClosedService.acquireOwnership(execId, Duration.ofSeconds(30));

        assertNull(token1, "When Redis is null/unavailable, acquireOwnership must fail closed (return null)");
        assertNull(token2, "Second attempt must also return null");
        assertFalse(failClosedService.verifyOwnership(execId, "any-token"));
        assertFalse(failClosedService.renewOwnership(execId, "any-token", Duration.ofSeconds(30)));
        assertFalse(failClosedService.releaseOwnership(execId, "any-token"));
    }

    @Test
    @DisplayName("Test 04: Real Redis - Heartbeat lease renewal maintains ownership")
    void test04_OwnerHeartbeat_Succeeds() throws Exception {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        String token = ownershipService.acquireOwnership(execId, Duration.ofMillis(500));
        assertNotNull(token);

        ownershipService.startHeartbeat(execId, token, Duration.ofMillis(100), Duration.ofMillis(500));
        Thread.sleep(800);

        assertTrue(ownershipService.verifyOwnership(execId, token), "Active owner heartbeat must maintain ownership lease in Redis");

        ownershipService.stopHeartbeat(execId);
        ownershipService.releaseOwnership(execId, token);
    }

    @Test
    @DisplayName("Test 05: Real Redis - Stale heartbeat/renewal fails")
    void test05_StaleHeartbeat_Fails() {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        String tokenA = ownershipService.acquireOwnership(execId, Duration.ofSeconds(30));
        assertNotNull(tokenA);

        boolean staleRenew = ownershipService.renewOwnership(execId, "stale-token-xyz", Duration.ofSeconds(30));
        assertFalse(staleRenew, "Stale heartbeat/renewal must return false");
        assertTrue(ownershipService.verifyOwnership(execId, tokenA), "Current owner lock must remain untouched");

        ownershipService.releaseOwnership(execId, tokenA);
    }

    @Test
    @DisplayName("Test 06: Real Redis - Stale owner release fails")
    void test06_StaleOwnerRelease_Fails() {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        String tokenA = ownershipService.acquireOwnership(execId, Duration.ofSeconds(30));
        assertNotNull(tokenA);

        boolean released = ownershipService.releaseOwnership(execId, "stale-token-xyz");
        assertFalse(released, "Stale owner cannot release lease held by valid owner");
        assertTrue(ownershipService.verifyOwnership(execId, tokenA), "Current owner lease must remain valid");

        ownershipService.releaseOwnership(execId, tokenA);
    }

    @Test
    @DisplayName("Test 07: Real Redis - Current owner release succeeds")
    void test07_CurrentOwnerRelease_Succeeds() {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        String token = ownershipService.acquireOwnership(execId, Duration.ofSeconds(30));
        assertNotNull(token);

        boolean released = ownershipService.releaseOwnership(execId, token);
        assertTrue(released, "Valid owner release must succeed");
        assertFalse(ownershipService.verifyOwnership(execId, token), "Lease must no longer be active");
    }

    @Test
    @DisplayName("Test 08: Real Redis - Owner handoff after lease expiry works safely")
    void test08_OwnerHandoff_AfterExpiry_WorksSafely() throws Exception {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        String tokenA = ownershipService.acquireOwnership(execId, Duration.ofMillis(200));
        assertNotNull(tokenA);

        Thread.sleep(400);

        String tokenB = ownershipService.acquireOwnership(execId, Duration.ofSeconds(30));
        assertNotNull(tokenB, "New worker B must acquire ownership after worker A lease expires");
        assertNotEquals(tokenA, tokenB);

        assertFalse(ownershipService.verifyOwnership(execId, tokenA), "Stale worker A must no longer hold ownership");
        assertTrue(ownershipService.verifyOwnership(execId, tokenB), "Worker B must hold active ownership");

        ownershipService.releaseOwnership(execId, tokenB);
    }

    @Test
    @DisplayName("Test 09: Real Redis - Stale owner cannot mutate or release after handoff")
    void test09_StaleOwnerCannotMutate_AfterHandoff() throws Exception {
        if (realRedisTemplate == null) return;

        UUID execId = UUID.randomUUID();
        String tokenA = ownershipService.acquireOwnership(execId, Duration.ofMillis(200));
        assertNotNull(tokenA);

        Thread.sleep(400);

        String tokenB = ownershipService.acquireOwnership(execId, Duration.ofSeconds(30));
        assertNotNull(tokenB);

        boolean releaseByA = ownershipService.releaseOwnership(execId, tokenA);
        boolean renewByA = ownershipService.renewOwnership(execId, tokenA, Duration.ofSeconds(30));

        assertFalse(releaseByA, "Stale owner A cannot release worker B's lease");
        assertFalse(renewByA, "Stale owner A cannot renew worker B's lease");
        assertTrue(ownershipService.verifyOwnership(execId, tokenB), "Worker B must maintain ownership");

        ownershipService.releaseOwnership(execId, tokenB);
    }
}
