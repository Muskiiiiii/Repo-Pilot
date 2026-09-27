package com.repopilot.task.sse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.security.SecretRedactor;
import com.repopilot.project.entity.Project;
import com.repopilot.task.entity.Task;
import com.repopilot.task.sse.dto.SseTaskEventDto;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
public class SseTaskStreamEngineImpl implements SseTaskStreamEngine {

    private static final Logger log = LoggerFactory.getLogger(SseTaskStreamEngineImpl.class);

    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern AWS_SECRET_PATTERN = Pattern.compile("(?i)(aws_secret_access_key|aws_secret|secret_key)\\s*[:=]\\s*[\"']?[A-Za-z0-9/+=_-]{16,64}[\"']?");
    private static final Pattern GENERIC_SECRET_PATTERN = Pattern.compile("(?i)(\"?(?:password|token|secret|api_key|access_key)\"?\\s*[:=]\\s*\")[^\"]+(\")");
    private static final Pattern LLM_KEY_PATTERN = Pattern.compile("sk-[a-zA-Z0-9_-]{20,}");
    private static final Pattern DB_URL_PATTERN = Pattern.compile("(postgres|postgresql|mysql|mongodb|redis)://[^\\s\"']+");

    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private final Map<UUID, AtomicInteger> activeUserStreamCounts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> activeTaskStreamCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Set<StreamSubscriber>> taskSubscribers = new ConcurrentHashMap<>();
    private final Map<SseEmitter, Runnable> emitterCleanups = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat-thread");
        t.setDaemon(true);
        return t;
    });

    public SseTaskStreamEngineImpl(AuditLogRepository auditLogRepository,
                                  AuditLogService auditLogService,
                                  ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();

        // Start 15-second heartbeat
        this.heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public SseEmitter createTaskStream(UUID taskId, User user, Project project, Task task, String lastEventIdHeader, String clientIp) {
        if (user == null || user.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot open SSE stream");
        }
        if (taskId == null || task == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
        }

        UUID userId = user.getId();

        // Atomic CAS reservation for User Stream Limit (max 5)
        AtomicInteger userCounter = activeUserStreamCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        while (true) {
            int current = userCounter.get();
            if (current >= MAX_USER_STREAMS) {
                log.warn("User {} exceeded max active SSE streams limit of {}", userId, MAX_USER_STREAMS);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Exceeded maximum allowed active SSE streams per user (5)");
            }
            if (userCounter.compareAndSet(current, current + 1)) {
                break;
            }
        }

        // Atomic CAS reservation for Task Stream Limit (max 20)
        AtomicInteger taskCounter = activeTaskStreamCounts.computeIfAbsent(taskId, k -> new AtomicInteger(0));
        while (true) {
            int current = taskCounter.get();
            if (current >= MAX_TASK_STREAMS) {
                userCounter.decrementAndGet(); // Rollback user reservation
                log.warn("Task {} exceeded max active SSE streams limit of {}", taskId, MAX_TASK_STREAMS);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Exceeded maximum allowed active SSE streams per task (20)");
            }
            if (taskCounter.compareAndSet(current, current + 1)) {
                break;
            }
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        String correlationId = "sse-" + UUID.randomUUID().toString().substring(0, 8);

        // Audit SSE Stream Connected
        auditLogService.logSseStreamConnected(user, project, task, correlationId, clientIp);

        // Parse Last-Event-ID if present
        long lastSequence = parseLastEventId(lastEventIdHeader);
        if (lastEventIdHeader != null && !lastEventIdHeader.trim().isEmpty()) {
            auditLogService.logSseStreamReplayed(user, project, task, lastEventIdHeader, correlationId);
        }

        List<AuditLog> auditLogs = auditLogRepository.findByTaskId(taskId);
        if (auditLogs == null) {
            auditLogs = Collections.emptyList();
        } else {
            auditLogs.sort(Comparator.comparing(AuditLog::getCreatedAt).thenComparing(AuditLog::getId));
        }
        long initialSeq = auditLogs.size();

        StreamSubscriber subscriber = new StreamSubscriber(
                user.getId(), taskId, emitter, initialSeq, lastSequence,
                seq -> findAuditLogByTaskSequence(taskId, seq)
        );
        Set<StreamSubscriber> subscribers = taskSubscribers.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet());
        subscribers.add(subscriber);

        AtomicBoolean cleanedUp = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (cleanedUp.compareAndSet(false, true)) {
                subscriber.close();
                subscribers.remove(subscriber);
                if (subscribers.isEmpty()) {
                    taskSubscribers.remove(taskId);
                }

                int remUser = userCounter.decrementAndGet();
                if (remUser <= 0) {
                    activeUserStreamCounts.remove(userId);
                }

                int remTask = taskCounter.decrementAndGet();
                if (remTask <= 0) {
                    activeTaskStreamCounts.remove(taskId);
                }
                emitterCleanups.remove(emitter);
                log.debug("Cleaned up SSE stream for user {} on task {}. Active user streams: {}, task streams: {}", userId, taskId, remUser, remTask);
            }
        };

        emitterCleanups.put(emitter, cleanup);

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            log.debug("SSE Stream timed out after 60s for task {}", taskId);
            cleanup.run();
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        });
        emitter.onError(e -> {
            log.debug("SSE Stream error for task {}: {}", taskId, e.getMessage());
            cleanup.run();
        });

        // Execute Replay from authoritative DB history
        replayHistoricalEvents(auditLogs, lastSequence, subscriber);

        return emitter;
    }

    @Override
    public void closeStream(SseEmitter emitter) {
        if (emitter == null) return;
        Runnable cleanup = emitterCleanups.remove(emitter);
        if (cleanup != null) {
            cleanup.run();
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {}
    }

    @Override
    public void publishTaskEvent(UUID taskId, String eventType, Object eventData) {
        Set<StreamSubscriber> subscribers = taskSubscribers.get(taskId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        String sanitizedData = sanitizeObjectData(eventData);

        for (StreamSubscriber subscriber : subscribers) {
            long seq = subscriber.incrementAndGetSequence();
            SseTaskEventDto dto = new SseTaskEventDto(
                    String.valueOf(seq),
                    eventType,
                    taskId,
                    Instant.now(),
                    sanitizedData
            );
            subscriber.handleLiveEvent(dto);
        }
    }

    @Override
    public int getActiveUserStreamCount(UUID userId) {
        AtomicInteger count = activeUserStreamCounts.get(userId);
        return count != null ? count.get() : 0;
    }

    @Override
    public int getActiveTaskStreamCount(UUID taskId) {
        AtomicInteger count = activeTaskStreamCounts.get(taskId);
        return count != null ? count.get() : 0;
    }

    private AuditLog findAuditLogByTaskSequence(UUID taskId, long taskSeq) {
        List<AuditLog> logs = auditLogRepository.findByTaskId(taskId);
        if (logs == null || logs.isEmpty()) return null;
        logs.sort(Comparator.comparing(AuditLog::getCreatedAt).thenComparing(AuditLog::getId));
        int index = (int) (taskSeq - 1);
        if (index >= 0 && index < logs.size()) {
            return logs.get(index);
        }
        return null;
    }

    private void replayHistoricalEvents(List<AuditLog> auditLogs, long lastSequence, StreamSubscriber subscriber) {
        if (auditLogs == null || auditLogs.isEmpty()) {
            subscriber.transitionToLive(0);
            return;
        }

        long replaySequence = 0;
        for (int i = 0; i < auditLogs.size(); i++) {
            AuditLog logItem = auditLogs.get(i);
            long currentSeq = i + 1;
            replaySequence = currentSeq;

            if (currentSeq > lastSequence) {
                String sanitizedMeta = sanitizeString(logItem.getMetadata());
                SseTaskEventDto dto = new SseTaskEventDto(
                        String.valueOf(currentSeq),
                        logItem.getAction(),
                        subscriber.getTaskId(),
                        logItem.getCreatedAt(),
                        sanitizedMeta
                );
                boolean sent = subscriber.tryEmitReplayEvent(dto);
                if (!sent) {
                    break;
                }
            }
        }
        subscriber.transitionToLive(replaySequence);
    }

    private long parseLastEventId(String header) {
        if (header == null || header.trim().isEmpty()) {
            return 0;
        }
        String trimmed = header.trim();
        if (trimmed.contains(":")) {
            trimmed = trimmed.substring(trimmed.lastIndexOf(":") + 1);
        }
        try {
            long val = Long.parseLong(trimmed);
            if (val < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID: cannot be negative");
            }
            return val;
        } catch (NumberFormatException e) {
            log.warn("Invalid Last-Event-ID header value: '{}'. Malformed format.", header);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID format");
        }
    }

    private void sendHeartbeats() {
        for (Set<StreamSubscriber> subscribers : taskSubscribers.values()) {
            for (StreamSubscriber subscriber : subscribers) {
                subscriber.sendKeepAlive();
            }
        }
    }

    private String sanitizeObjectData(Object data) {
        if (data == null) return null;
        if (data instanceof String str) {
            return sanitizeString(str);
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            return sanitizeString(json);
        } catch (Exception e) {
            return sanitizeString(String.valueOf(data));
        }
    }

    private String sanitizeString(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String sanitized = SecretRedactor.sanitizeLogs(input);
        sanitized = AWS_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_AWS_KEY]");
        sanitized = AWS_SECRET_PATTERN.matcher(sanitized).replaceAll("$1 = [REDACTED_AWS_SECRET]");
        sanitized = GENERIC_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED_SECRET]$2");
        sanitized = LLM_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_LLM_KEY]");
        sanitized = DB_URL_PATTERN.matcher(sanitized).replaceAll("$1://[REDACTED_DB_CREDENTIALS]");
        return sanitized;
    }

    public static class StreamSubscriber {
        public enum SubscriberState {
            REPLAYING,
            LIVE,
            CLOSED
        }

        private static final int MAX_LIVE_QUEUE_SIZE = 1000;
        private static final int MAX_GAP_RESOLUTION_RETRIES = 5;

        private final UUID userId;
        private final UUID taskId;
        private final SseEmitter emitter;
        private final AtomicLong sequenceCounter;
        private final AtomicLong lastEmittedSequence;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final java.util.function.Function<Long, AuditLog> auditLogResolver;

        private SubscriberState state = SubscriberState.REPLAYING;
        private final PriorityQueue<SseTaskEventDto> boundedLiveQueue = new PriorityQueue<>(
                Comparator.comparingLong(dto -> {
                    try {
                        return Long.parseLong(dto.getId());
                    } catch (Exception e) {
                        return 0L;
                    }
                })
        );

        public StreamSubscriber(UUID userId, UUID taskId, SseEmitter emitter, long initialSeq, long lastSequence) {
            this(userId, taskId, emitter, initialSeq, lastSequence, null);
        }

        public StreamSubscriber(UUID userId, UUID taskId, SseEmitter emitter, long initialSeq, long lastSequence, java.util.function.Function<Long, AuditLog> auditLogResolver) {
            this.userId = userId;
            this.taskId = taskId;
            this.emitter = emitter;
            this.sequenceCounter = new AtomicLong(initialSeq);
            this.lastEmittedSequence = new AtomicLong(Math.max(0, lastSequence));
            this.auditLogResolver = auditLogResolver;
        }

        public UUID getUserId() {
            return userId;
        }

        public UUID getTaskId() {
            return taskId;
        }

        public long incrementAndGetSequence() {
            return sequenceCounter.incrementAndGet();
        }

        public synchronized boolean tryEmitReplayEvent(SseTaskEventDto dto) {
            if (!active.get() || state == SubscriberState.CLOSED) return false;
            if (state != SubscriberState.REPLAYING) return false;

            long seq;
            try {
                seq = Long.parseLong(dto.getId());
            } catch (Exception e) {
                seq = 0;
            }

            if (seq > 0 && seq <= lastEmittedSequence.get()) {
                log.debug("Duplicate replay event skipped for subscriber on task {}: seq={}", taskId, seq);
                return false;
            }

            long expected = lastEmittedSequence.get() + 1;
            if (seq > expected) {
                resolveMissingSequenceFromDb(expected);
            }

            boolean sent = sendEventInternal(dto);
            if (sent && seq > 0) {
                lastEmittedSequence.set(seq);
            }
            return sent;
        }

        public synchronized boolean handleLiveEvent(SseTaskEventDto dto) {
            if (!active.get() || state == SubscriberState.CLOSED) return false;

            long seq;
            try {
                seq = Long.parseLong(dto.getId());
            } catch (Exception e) {
                seq = 0;
            }

            if (seq > 0 && seq <= lastEmittedSequence.get()) {
                log.debug("Duplicate live event skipped for subscriber on task {}: seq={}", taskId, seq);
                return false;
            }

            if (state == SubscriberState.REPLAYING) {
                if (boundedLiveQueue.size() >= MAX_LIVE_QUEUE_SIZE) {
                    log.warn("Subscriber for task {} exceeded live queue limit of {} during replay. Closing stream.", taskId, MAX_LIVE_QUEUE_SIZE);
                    failClosed("SSE live event queue overflow during replay");
                    return false;
                }
                boundedLiveQueue.offer(dto);
                return true;
            } else if (state == SubscriberState.LIVE) {
                long expected = lastEmittedSequence.get() + 1;
                if (seq == expected) {
                    boolean sent = sendEventInternal(dto);
                    if (sent) {
                        lastEmittedSequence.set(seq);
                        flushQueue();
                        return true;
                    } else {
                        failClosed("Failed to emit live event sequence " + seq);
                        return false;
                    }
                } else if (seq > expected) {
                    if (boundedLiveQueue.size() >= MAX_LIVE_QUEUE_SIZE) {
                        failClosed("SSE live event queue overflow while buffering out-of-order event");
                        return false;
                    }
                    boundedLiveQueue.offer(dto);
                    flushQueue();
                    return true;
                }
            }

            return false;
        }

        public synchronized void transitionToLive(long replayMaxSeq) {
            if (!active.get() || state == SubscriberState.CLOSED) return;

            if (replayMaxSeq > lastEmittedSequence.get()) {
                lastEmittedSequence.set(replayMaxSeq);
            }
            if (replayMaxSeq > sequenceCounter.get()) {
                sequenceCounter.set(replayMaxSeq);
            }

            flushQueue();

            if (!boundedLiveQueue.isEmpty()) {
                long expected = lastEmittedSequence.get() + 1;
                SseTaskEventDto top = boundedLiveQueue.peek();
                long topSeq = Long.parseLong(top.getId());
                if (topSeq > expected) {
                    boolean resolved = resolveMissingSequenceFromDb(expected);
                    if (resolved) {
                        flushQueue();
                    } else {
                        log.error("Unresolvable stream sequence gap for task {}: expected sequence {}, top buffered sequence {}", taskId, expected, topSeq);
                        failClosed("Unresolvable sequence gap: expected sequence " + expected + " missing");
                        return;
                    }
                }
            }

            state = SubscriberState.LIVE;
        }

        private void flushQueue() {
            if (!active.get() || state == SubscriberState.CLOSED) return;

            while (!boundedLiveQueue.isEmpty()) {
                SseTaskEventDto top = boundedLiveQueue.peek();
                long seq;
                try {
                    seq = Long.parseLong(top.getId());
                } catch (Exception e) {
                    seq = 0;
                }

                long expected = lastEmittedSequence.get() + 1;

                if (seq <= lastEmittedSequence.get()) {
                    boundedLiveQueue.poll();
                    continue;
                }

                if (seq == expected) {
                    boundedLiveQueue.poll();
                    boolean sent = sendEventInternal(top);
                    if (sent) {
                        lastEmittedSequence.set(seq);
                    } else {
                        failClosed("Failed to emit queued event sequence " + seq);
                        return;
                    }
                } else if (seq > expected) {
                    boolean resolved = resolveMissingSequenceFromDb(expected);
                    if (!resolved) {
                        break;
                    }
                }
            }
        }

        private boolean resolveMissingSequenceFromDb(long expectedSeq) {
            if (auditLogResolver == null) return false;

            for (int retry = 0; retry < MAX_GAP_RESOLUTION_RETRIES; retry++) {
                AuditLog dbLog = auditLogResolver.apply(expectedSeq);
                if (dbLog != null) {
                    String sanitizedMeta = SecretRedactor.sanitizeLogs(dbLog.getMetadata());
                    SseTaskEventDto dto = new SseTaskEventDto(
                            String.valueOf(expectedSeq),
                            dbLog.getAction(),
                            taskId,
                            dbLog.getCreatedAt(),
                            sanitizedMeta
                    );
                    boolean sent = sendEventInternal(dto);
                    if (sent) {
                        lastEmittedSequence.set(expectedSeq);
                        log.info("Authoritatively resolved missing sequence {} from DB for task {}", expectedSeq, taskId);
                        return true;
                    } else {
                        failClosed("Failed to emit authoritatively resolved sequence " + expectedSeq);
                        return false;
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return false;
        }

        public synchronized void failClosed(String reason) {
            state = SubscriberState.CLOSED;
            active.set(false);
            try {
                emitter.completeWithError(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, reason));
            } catch (Exception ignored) {}
        }

        public synchronized void close() {
            state = SubscriberState.CLOSED;
            active.set(false);
        }

        private boolean sendEventInternal(SseTaskEventDto dto) {
            try {
                emitter.send(SseEmitter.event()
                        .id(dto.getId())
                        .name(dto.getEvent())
                        .data(dto, MediaType.APPLICATION_JSON));
                return true;
            } catch (Exception e) {
                state = SubscriberState.CLOSED;
                active.set(false);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
                return false;
            }
        }

        public synchronized void sendKeepAlive() {
            if (!active.get() || state == SubscriberState.CLOSED) return;
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                state = SubscriberState.CLOSED;
                active.set(false);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
            }
        }
    }
}
