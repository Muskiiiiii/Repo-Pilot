package com.repopilot.auth.service;

import com.repopilot.auth.dto.AuthResponse;
import com.repopilot.auth.dto.LoginRequest;
import com.repopilot.auth.dto.RefreshTokenRequest;
import com.repopilot.auth.dto.RegisterRequest;
import com.repopilot.auth.entity.RefreshToken;
import com.repopilot.auth.repository.RefreshTokenRepository;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.common.exception.ApiException;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private JwtTokenProvider tokenProvider;
    private RefreshTokenService refreshTokenService;
    private AuthService authService;

    private User testUser;
    private RefreshToken testRefreshToken;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(
                "test_super_secret_jwt_key_that_is_long_enough_for_hmac_sha_256_verification_123456",
                86400000L
        );
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, 604800000L);
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider, refreshTokenService);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("user@example.com");
        testUser.setPasswordHash("hashed_password");
        testUser.setName("Test User");
        testUser.setRole(UserRole.USER);
        testUser.setActive(true);

        testRefreshToken = new RefreshToken();
        testRefreshToken.setId(UUID.randomUUID());
        testRefreshToken.setUser(testUser);
        testRefreshToken.setToken("sample-refresh-token");
        testRefreshToken.setExpiryDate(Instant.now().plusSeconds(3600));
        testRefreshToken.setRevoked(false);
    }

    @Test
    void register_Success() {
        RegisterRequest request = new RegisterRequest("user@example.com", "password123", "Test User");

        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed_password");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(testRefreshToken);

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals("sample-refresh-token", response.getRefreshToken());
        assertEquals("user@example.com", response.getUser().getEmail());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void register_DuplicateEmail_ThrowsException() {
        RegisterRequest request = new RegisterRequest("user@example.com", "password123", "Test User");
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class, () -> authService.register(request));
        assertTrue(exception.getMessage().contains("Email address is already in use"));
    }

    @Test
    void login_Success() {
        LoginRequest request = new LoginRequest("user@example.com", "password123");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password123", "hashed_password")).thenReturn(true);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(testRefreshToken);

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals("sample-refresh-token", response.getRefreshToken());
    }

    @Test
    void login_InvalidPassword_ThrowsException() {
        LoginRequest request = new LoginRequest("user@example.com", "wrongpassword");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongpassword", "hashed_password")).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class, () -> authService.login(request));
        assertTrue(exception.getMessage().contains("Invalid email or password"));
    }

    @Test
    void refreshToken_Success() {
        RefreshTokenRequest request = new RefreshTokenRequest("sample-refresh-token");

        when(refreshTokenRepository.findByToken("sample-refresh-token")).thenReturn(Optional.of(testRefreshToken));

        RefreshToken newRefreshToken = new RefreshToken();
        newRefreshToken.setToken("new-refresh-token");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(newRefreshToken);

        AuthResponse response = authService.refreshToken(request);

        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals("new-refresh-token", response.getRefreshToken());
        verify(refreshTokenRepository).deleteByToken("sample-refresh-token");
    }

    @Test
    void logout_Success() {
        authService.logout("sample-refresh-token");
        verify(refreshTokenRepository).deleteByToken("sample-refresh-token");
    }
}
