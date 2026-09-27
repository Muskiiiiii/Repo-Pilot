package com.repopilot.testing.daemon.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;
import com.repopilot.testing.daemon.model.DaemonStatus;
import com.repopilot.testing.daemon.service.SandboxDaemonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SandboxDaemonUnixSocketServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SandboxDaemonUnixSocketServer.class);
    private static final int MAX_REQUEST_PAYLOAD_BYTES = 65536; // 64 KB

    private final Path socketPath;
    private final SandboxDaemonService daemonService;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocketChannel serverChannel;
    private ExecutorService executor;

    public SandboxDaemonUnixSocketServer(Path socketPath, SandboxDaemonService daemonService) {
        this.socketPath = socketPath.toAbsolutePath().normalize();
        this.daemonService = daemonService;
        this.objectMapper = new ObjectMapper();
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        Path parentDir = socketPath.getParent();
        if (parentDir != null) {
            Path realParent = Files.exists(parentDir) ? parentDir.toRealPath() : parentDir;
            if (!Files.exists(realParent)) {
                Files.createDirectories(realParent);
                try {
                    Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwx------");
                    Files.setPosixFilePermissions(realParent, dirPerms);
                } catch (UnsupportedOperationException ignored) {
                }
            }
        }

        // Stale socket cleanup
        if (Files.exists(socketPath)) {
            if (Files.isSymbolicLink(socketPath)) {
                throw new SecurityException("Socket path is a symbolic link: " + socketPath);
            }
            Files.delete(socketPath);
        }

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(address);

        try {
            Set<PosixFilePermission> socketPerms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(socketPath, socketPerms);
        } catch (UnsupportedOperationException ignored) {
        }

        running.set(true);
        executor = Executors.newCachedThreadPool();
        executor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get() && serverChannel != null && serverChannel.isOpen()) {
            try {
                SocketChannel clientChannel = serverChannel.accept();
                executor.submit(() -> handleClient(clientChannel));
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void handleClient(SocketChannel clientChannel) {
        try (clientChannel) {
            byte[] requestBytes = SandboxDaemonUnixSocketClient.readFrame(clientChannel, MAX_REQUEST_PAYLOAD_BYTES);
            if (requestBytes == null || requestBytes.length == 0) {
                return;
            }

            DaemonExecutionRequest request;
            try {
                request = objectMapper.readValue(requestBytes, DaemonExecutionRequest.class);
            } catch (Exception e) {
                DaemonExecutionResponse errResp = DaemonExecutionResponse.error(
                        "unknown", DaemonStatus.INVALID_REQUEST,
                        "ERR_SERVER_DECODE", "Failed to parse request JSON: " + e.getMessage(), null, null, null);
                sendResponse(clientChannel, errResp);
                return;
            }

            DaemonExecutionResponse response = daemonService.processRequest(request);
            sendResponse(clientChannel, response);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Invalid frame payload length")) {
                try {
                    DaemonExecutionResponse errResp = DaemonExecutionResponse.error(
                            "unknown", DaemonStatus.INVALID_REQUEST,
                            "ERR_OVERSIZED_REQUEST", "Request payload exceeds 64KB limit", null, null, null);
                    sendResponse(clientChannel, errResp);
                } catch (Exception ignored) {
                }
            } else {
                logger.debug("Daemon client transport IO exception: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Unexpected daemon socket handling error: {}", e.getMessage(), e);
        }
    }

    private void sendResponse(SocketChannel clientChannel, DaemonExecutionResponse response) throws IOException {
        byte[] respBytes = objectMapper.writeValueAsBytes(response);
        SandboxDaemonUnixSocketClient.writeFrame(clientChannel, respBytes);
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (serverChannel != null) {
            try { serverChannel.close(); } catch (IOException ignored) {}
        }

        if (executor != null) {
            executor.shutdownNow();
        }

        if (Files.exists(socketPath)) {
            try { Files.delete(socketPath); } catch (IOException ignored) {}
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public Path getSocketPath() {
        return socketPath;
    }
}
