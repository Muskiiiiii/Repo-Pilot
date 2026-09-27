package com.repopilot.testing;

import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;
import com.repopilot.testing.daemon.model.DaemonOperationType;
import com.repopilot.testing.daemon.model.DaemonStatus;
import com.repopilot.testing.daemon.service.SandboxDaemonService;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketClient;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class Phase14_Slice3_DaemonTransportProtocolTest {

    private Path socketPath;
    private SandboxDaemonUnixSocketServer daemonServer;
    private SandboxDaemonUnixSocketClient daemonClient;
    private MockDaemonService mockDaemonService;

    @BeforeEach
    void setUp() throws IOException {
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        Path baseDir = Path.of("/tmp").toRealPath();
        socketPath = baseDir.resolve("rp_test_tp_" + shortId + ".sock").toAbsolutePath().normalize();

        mockDaemonService = new MockDaemonService();
        daemonServer = new SandboxDaemonUnixSocketServer(socketPath, mockDaemonService);
        daemonServer.start();

        daemonClient = new SandboxDaemonUnixSocketClient(socketPath);
    }

    @AfterEach
    void tearDown() {
        if (daemonServer != null) {
            daemonServer.stop();
        }
    }

    @Test
    @DisplayName("1. Length-prefixed framing request & response succeeds without deadlock")
    void test1_lengthPrefixedFramingSuccess() throws IOException {
        DaemonExecutionRequest request = new DaemonExecutionRequest(
                "req-tp-1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                "exec-tp-1", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );

        DaemonExecutionResponse response = daemonClient.sendRequest(request);
        assertNotNull(response);
        assertEquals(DaemonStatus.SUCCESS, response.getStatus());
        assertEquals("req-tp-1", response.getRequestId());
    }

    @Test
    @DisplayName("2. Oversized payload (> 64KB) rejected gracefully by server")
    void test2_oversizedPayloadRejected() throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(address)) {
            // Write 4-byte header specifying oversized payload (70KB)
            ByteBuffer header = ByteBuffer.allocate(4);
            header.putInt(70000);
            header.flip();
            channel.write(header);

            // Server detects 70KB > 64KB limit, responds with error frame or closes socket
            try {
                byte[] respBytes = SandboxDaemonUnixSocketClient.readFrame(channel, 65536);
                if (respBytes != null && respBytes.length > 0) {
                    DaemonExecutionResponse resp = new com.fasterxml.jackson.databind.ObjectMapper().readValue(respBytes, DaemonExecutionResponse.class);
                    assertEquals(DaemonStatus.INVALID_REQUEST, resp.getStatus());
                }
            } catch (IOException expected) {
                // Channel closure on oversized payload is valid fail-closed behavior
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    @DisplayName("3. Hostile concurrency: 10 concurrent clients execute requests without deadlock")
    void test3_hostileConcurrency10Clients() throws Exception {
        int clientCount = 10;
        int requestsPerClient = 5;
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int c = 0; c < clientCount; c++) {
            final int clientIndex = c;
            futures.add(executor.submit(() -> {
                SandboxDaemonUnixSocketClient client = new SandboxDaemonUnixSocketClient(socketPath);
                for (int r = 0; r < requestsPerClient; r++) {
                    DaemonExecutionRequest request = new DaemonExecutionRequest(
                            "req-c" + clientIndex + "-r" + r, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                            "exec-c" + clientIndex, DaemonOperationType.INSPECT, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
                    );
                    DaemonExecutionResponse response = client.sendRequest(request);
                    if (response == null || response.getStatus() != DaemonStatus.SUCCESS) {
                        return false;
                    }
                }
                return true;
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent test execution MUST complete within 10s");

        for (Future<Boolean> f : futures) {
            assertTrue(f.get(), "All concurrent client requests MUST succeed");
        }
    }

    private static class MockDaemonService implements SandboxDaemonService {
        @Override
        public DaemonExecutionResponse processRequest(DaemonExecutionRequest request) {
            return DaemonExecutionResponse.success(
                    request.getRequestId(), request.getJobId(), request.getExecutionId(),
                    request.getFencingVersion(), "SUCCESS"
            );
        }
    }
}
