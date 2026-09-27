package com.repopilot.approval;

import com.repopilot.testing.service.DurableOrchestratorOwnershipService;
import com.repopilot.testing.service.DurableOrchestratorOwnershipServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Phase12_2FRealRedisFencingTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private DurableOrchestratorOwnershipService ownershipService;

    private UUID executionId;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        ownershipService = new DurableOrchestratorOwnershipServiceImpl(redisTemplate);

        executionId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null && executionId != null) {
            try {
                redisTemplate.delete("orchestration:owner:" + executionId);
            } catch (Exception ignored) {
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("Requirement 4: Real Redis TTL Expiration, Ownership Token Preemption, and Stale-Worker Fencing")
    void testRealRedisLeaseExpiryAndStaleWorkerFencing() throws InterruptedException {
        // 1. Worker A acquires publication lease with short TTL of 2 seconds
        Duration shortTtl = Duration.ofSeconds(2);
        String tokenA = ownershipService.acquireOwnership(executionId, shortTtl);

        assertNotNull(tokenA, "Worker A must acquire initial Redis lease");
        assertTrue(ownershipService.verifyOwnership(executionId, tokenA), "Worker A token must be authoritative in Redis");
        assertTrue(ownershipService.isOwned(executionId), "Redis key must exist");

        // 2. Worker A pauses and allows tokenA to genuinely expire in real Redis according to TTL
        Thread.sleep(2500);

        // 3. Verify tokenA has genuinely expired in real Redis
        assertFalse(ownershipService.isOwned(executionId), "Redis key must be expired after TTL elapsed");
        assertFalse(ownershipService.verifyOwnership(executionId, tokenA), "Worker A token must no longer be valid");

        // 4. Worker B legitimately acquires lease for the same executionId
        Duration longTtl = Duration.ofSeconds(60);
        String tokenB = ownershipService.acquireOwnership(executionId, longTtl);

        assertNotNull(tokenB, "Worker B must acquire publication lease after Worker A lease expired");
        assertNotEquals(tokenA, tokenB, "Worker B token must be distinct from Worker A token");
        assertTrue(ownershipService.verifyOwnership(executionId, tokenB), "Worker B token must be authoritative in Redis");

        // 5. Worker A resumes and attempts protected mutation using its stale tokenA
        boolean workerAVerify = ownershipService.verifyOwnership(executionId, tokenA);
        boolean workerARelease = ownershipService.releaseOwnership(executionId, tokenA);

        // 6. Atomic fencing verification MUST reject Worker A
        assertFalse(workerAVerify, "Worker A verification MUST be rejected as stale");
        assertFalse(workerARelease, "Worker A release MUST be rejected by atomic Lua script");

        // 7. Worker B remains authoritative in real Redis
        assertTrue(ownershipService.verifyOwnership(executionId, tokenB), "Worker B MUST remain authoritative owner in Redis");
    }
}
