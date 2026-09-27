package com.repopilot.testing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class DurableOrchestratorOwnershipServiceImpl implements DurableOrchestratorOwnershipService {

    private static final Logger logger = LoggerFactory.getLogger(DurableOrchestratorOwnershipServiceImpl.class);

    private static final String RENEW_LOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('PEXPIRE', KEYS[1], ARGV[2]) " +
            "else " +
            "    return 0 " +
            "end";

    private static final String RELEASE_LOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('DEL', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final Map<UUID, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "orchestration-heartbeat-thread");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public DurableOrchestratorOwnershipServiceImpl(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String acquireOwnership(UUID executionId, Duration ttl) {
        if (executionId == null || redisTemplate == null) {
            logger.warn("Cannot acquire ownership for execution {}: Redis unavailable (fail-closed)", executionId);
            return null;
        }
        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : Duration.ofSeconds(60);
        String candidateToken = UUID.randomUUID().toString();

        try {
            String lockKey = buildRedisKey(executionId);
            Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, candidateToken, effectiveTtl);
            if (Boolean.TRUE.equals(success)) {
                logger.info("Acquired Redis ownership lock for execution {} with ownerToken {}", executionId, candidateToken);
                return candidateToken;
            } else {
                logger.warn("Failed to acquire Redis ownership lock for execution {} (already owned)", executionId);
                return null;
            }
        } catch (Exception e) {
            logger.error("Redis failure during ownership acquisition for execution {}: {}. Failing closed (no process-local fallback).", executionId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean renewOwnership(UUID executionId, String ownerToken, Duration ttl) {
        if (executionId == null || ownerToken == null || ownerToken.isBlank() || redisTemplate == null) {
            return false;
        }
        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : Duration.ofSeconds(60);

        try {
            String lockKey = buildRedisKey(executionId);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RENEW_LOCK_LUA, Long.class);
            Long result = redisTemplate.execute(script, Collections.singletonList(lockKey), ownerToken, String.valueOf(effectiveTtl.toMillis()));
            return result != null && result > 0;
        } catch (Exception e) {
            logger.error("Redis failure during ownership renewal for execution {}: {}. Failing closed.", executionId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean verifyOwnership(UUID executionId, String ownerToken) {
        if (executionId == null || ownerToken == null || ownerToken.isBlank() || redisTemplate == null) {
            return false;
        }

        try {
            String lockKey = buildRedisKey(executionId);
            String currentOwnerInRedis = redisTemplate.opsForValue().get(lockKey);
            return ownerToken.equals(currentOwnerInRedis);
        } catch (Exception e) {
            logger.error("Redis failure during ownership verification for execution {}: {}. Failing closed.", executionId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean releaseOwnership(UUID executionId, String ownerToken) {
        if (executionId == null || ownerToken == null || ownerToken.isBlank()) {
            return false;
        }

        stopHeartbeat(executionId);

        if (redisTemplate == null) {
            return false;
        }

        try {
            String lockKey = buildRedisKey(executionId);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);
            Long result = redisTemplate.execute(script, Collections.singletonList(lockKey), ownerToken);
            return result != null && result > 0;
        } catch (Exception e) {
            logger.error("Redis failure during ownership release for execution {}: {}. Failing closed.", executionId, e.getMessage());
            return false;
        }
    }

    @Override
    public void startHeartbeat(UUID executionId, String ownerToken, Duration interval, Duration ttl) {
        if (executionId == null || ownerToken == null || ownerToken.isBlank()) return;
        stopHeartbeat(executionId);

        long periodMs = interval != null ? interval.toMillis() : 15_000L;
        Duration renewalTtl = ttl != null ? ttl : Duration.ofSeconds(60);

        ScheduledFuture<?> task = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = renewOwnership(executionId, ownerToken, renewalTtl);
                if (!renewed) {
                    logger.warn("Stale owner heartbeat failed for execution {}. Stopping heartbeat.", executionId);
                    stopHeartbeat(executionId);
                }
            } catch (Exception e) {
                logger.error("Heartbeat execution error for execution {}: {}", executionId, e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);

        heartbeatTasks.put(executionId, task);
    }

    @Override
    public void stopHeartbeat(UUID executionId) {
        if (executionId == null) return;
        ScheduledFuture<?> task = heartbeatTasks.remove(executionId);
        if (task != null) {
            task.cancel(true);
        }
    }

    @Override
    public boolean isOwned(UUID executionId) {
        if (executionId == null || redisTemplate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(buildRedisKey(executionId)));
        } catch (Exception e) {
            logger.warn("Error checking Redis ownership status for {}: {}", executionId, e.getMessage());
            return false;
        }
    }

    private String buildRedisKey(UUID executionId) {
        return "orchestration:owner:" + executionId;
    }
}
