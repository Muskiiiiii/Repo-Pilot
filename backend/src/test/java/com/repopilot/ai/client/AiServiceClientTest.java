package com.repopilot.ai.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceClientTest {

    @Mock
    private RestOperations restTemplate;

    private AiServiceClient aiServiceClient;

    @BeforeEach
    void setUp() {
        aiServiceClient = new AiServiceClientImpl(restTemplate, "http://localhost:8000");
    }

    @Test
    void generateEmbeddings_returnsFormattedVectors() {
        Map<String, Object> mockBody = new HashMap<>();
        mockBody.put("embeddings", List.of(List.of(0.1, 0.2, 0.3)));
        ResponseEntity<Map> mockResponse = new ResponseEntity<>(mockBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(mockResponse);

        List<String> results = aiServiceClient.generateEmbeddings(List.of("test chunk"));

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("[0.1,0.2,0.3]", results.get(0));
    }

    @Test
    void generateEmbeddings_retriesOn429ThenSucceeds() {
        HttpClientErrorException rateLimitErr = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit", null, null, null);

        Map<String, Object> mockBody = new HashMap<>();
        mockBody.put("embeddings", List.of(List.of(0.5, 0.6)));
        ResponseEntity<Map> successResponse = new ResponseEntity<>(mockBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(rateLimitErr)
                .thenReturn(successResponse);

        List<String> results = aiServiceClient.generateEmbeddings(List.of("test chunk"));

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("[0.5,0.6]", results.get(0));
        verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }
}
