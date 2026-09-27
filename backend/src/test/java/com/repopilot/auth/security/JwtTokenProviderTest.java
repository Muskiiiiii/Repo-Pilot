package com.repopilot.auth.security;

import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private User testUser;
    private final String secret = "test_super_secret_jwt_key_that_is_long_enough_for_hmac_sha_256_verification_123456";
    private final long expirationMs = 3600000; // 1 hour

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(secret, expirationMs);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("dev@repopilot.ai");
        testUser.setName("Developer");
        testUser.setRole(UserRole.USER);
    }

    @Test
    void generateAccessToken_ShouldReturnValidJwtToken() {
        String token = jwtTokenProvider.generateAccessToken(testUser);

        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
        assertEquals(testUser.getId(), jwtTokenProvider.getUserIdFromToken(token));
        assertEquals("dev@repopilot.ai", jwtTokenProvider.getEmailFromToken(token));
    }

    @Test
    void validateToken_ShouldReturnFalseForInvalidToken() {
        assertFalse(jwtTokenProvider.validateToken("invalid.token.structure"));
    }

    @Test
    void validateToken_ShouldReturnFalseForExpiredToken() {
        // Create provider with -1 second expiration
        JwtTokenProvider expiredProvider = new JwtTokenProvider(secret, -1000);
        String token = expiredProvider.generateAccessToken(testUser);

        assertFalse(jwtTokenProvider.validateToken(token));
    }
}
