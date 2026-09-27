package com.repopilot.testing.daemon.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;
import com.repopilot.testing.daemon.model.DaemonStatus;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

public class SandboxDaemonUnixSocketClient {

    private static final int MAX_REQUEST_PAYLOAD_BYTES = 65536; // 64 KB
    private static final int MAX_RESPONSE_PAYLOAD_BYTES = 65536; // 64 KB

    private final Path socketPath;
    private final ObjectMapper objectMapper;

    public SandboxDaemonUnixSocketClient(Path socketPath) {
        this.socketPath = socketPath.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper();
    }

    public DaemonExecutionResponse sendRequest(DaemonExecutionRequest request) throws IOException {
        byte[] reqBytes = objectMapper.writeValueAsBytes(request);
        if (reqBytes.length > MAX_REQUEST_PAYLOAD_BYTES) {
            return DaemonExecutionResponse.error(
                    request.getRequestId(), DaemonStatus.INVALID_REQUEST,
                    "ERR_OVERSIZED_REQUEST", "Request payload exceeds 64KB limit",
                    request.getJobId(), request.getExecutionId(), request.getFencingVersion()
            );
        }

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(address)) {
            writeFrame(channel, reqBytes);
            channel.shutdownOutput();

            byte[] respBytes = readFrame(channel, MAX_RESPONSE_PAYLOAD_BYTES);
            if (respBytes == null || respBytes.length == 0) {
                return DaemonExecutionResponse.error(
                        request.getRequestId(), DaemonStatus.DAEMON_UNAVAILABLE,
                        "ERR_EMPTY_RESPONSE", "Empty response received from sandbox daemon socket",
                        request.getJobId(), request.getExecutionId(), request.getFencingVersion()
                );
            }

            return objectMapper.readValue(respBytes, DaemonExecutionResponse.class);
        }
    }

    public static void writeFrame(SocketChannel channel, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        ByteBuffer header = ByteBuffer.allocate(4);
        header.putInt(payload.length);
        header.flip();
        writeFully(channel, header);

        if (payload.length > 0) {
            ByteBuffer payloadBuf = ByteBuffer.wrap(payload);
            writeFully(channel, payloadBuf);
        }
    }

    public static byte[] readFrame(SocketChannel channel, int maxPayloadBytes) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(4);
        if (!readFully(channel, header)) {
            return null; // Clean EOF
        }
        header.flip();
        int payloadLength = header.getInt();

        if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
            throw new IOException("Invalid frame payload length: " + payloadLength + " (max allowed: " + maxPayloadBytes + ")");
        }

        if (payloadLength == 0) {
            return new byte[0];
        }

        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLength);
        if (!readFully(channel, payloadBuf)) {
            throw new IOException("Unexpected EOF while reading frame payload of length " + payloadLength);
        }
        payloadBuf.flip();
        byte[] payload = new byte[payloadLength];
        payloadBuf.get(payload);
        return payload;
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written < 0) {
                throw new IOException("Socket channel closed during write");
            }
        }
    }

    private static boolean readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                return false;
            }
        }
        return true;
    }
}
