package com.repopilot.audit.service;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogServiceImpl auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogServiceImpl(auditLogRepository, new com.fasterxml.jackson.databind.ObjectMapper());
        lenient().when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(auditLogRepository.saveAndFlush(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void logAction_withValidJsonObject_preservesJson() {
        User user = new User();
        String jsonMetadata = "{\"githubUsername\":\"octocat\"}";

        AuditLog result = auditLogService.logAction(user, null, null, "GITHUB_CONNECTED", "USER", UUID.randomUUID(), jsonMetadata);

        assertNotNull(result);
        assertEquals(jsonMetadata, result.getMetadata());
    }

    @Test
    void logAction_withPlainString_wrapsInJsonObject() {
        User user = new User();
        String plainMetadata = "Created project: Demo";

        AuditLog result = auditLogService.logAction(user, null, null, "PROJECT_CREATED", "PROJECT", UUID.randomUUID(), plainMetadata);

        assertNotNull(result);
        assertEquals("{\"details\":\"Created project: Demo\"}", result.getMetadata());
    }

    @Test
    void logAction_withNullMetadata_setsNull() {
        User user = new User();

        AuditLog result = auditLogService.logAction(user, null, null, "USER_LOGIN", "USER", UUID.randomUUID(), null);

        assertNotNull(result);
        assertNull(result.getMetadata());
    }

    @Test
    void logAction_withMultilineExceptionString_escapesJsonValidly() {
        User user = new User();
        String multilineError = "ERROR: invalid byte sequence for encoding \"UTF8\": 0x00\n  Where: unnamed portal parameter $3";

        AuditLog result = auditLogService.logAction(user, null, null, "REPOSITORY_INDEX_FAILED", "REPOSITORY", UUID.randomUUID(), multilineError);

        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().contains("invalid byte sequence"));
        assertFalse(result.getMetadata().contains("\n")); // Verify newline is escaped as \n
    }
}
