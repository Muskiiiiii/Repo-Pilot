package com.repopilot.repository.service;

import com.repopilot.repository.chunker.ChunkResult;
import com.repopilot.repository.chunker.CodeChunker;
import com.repopilot.repository.chunker.SlidingWindowCodeChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Service
public class CodeChunkerService {

    private static final Logger logger = LoggerFactory.getLogger(CodeChunkerService.class);

    private final List<CodeChunker> chunkers;
    private final SlidingWindowCodeChunker fallbackChunker;
    private final SecretDetectionService secretDetectionService;
    private final int maxChunkTokens;
    private final int maxChunkChars;

    public CodeChunkerService(List<CodeChunker> chunkers,
                              SlidingWindowCodeChunker fallbackChunker,
                              SecretDetectionService secretDetectionService) {
        this(chunkers, fallbackChunker, secretDetectionService, 2000, 6000);
    }

    @Autowired
    public CodeChunkerService(List<CodeChunker> chunkers,
                              SlidingWindowCodeChunker fallbackChunker,
                              SecretDetectionService secretDetectionService,
                              @Value("${repopilot.indexing.max-chunk-tokens:2000}") int maxChunkTokens,
                              @Value("${repopilot.indexing.max-chunk-chars:6000}") int maxChunkChars) {
        this.chunkers = chunkers;
        this.fallbackChunker = fallbackChunker;
        this.secretDetectionService = secretDetectionService;
        this.maxChunkTokens = maxChunkTokens;
        this.maxChunkChars = maxChunkChars;
    }

    public int getMaxChunkTokens() {
        return maxChunkTokens;
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 3.0));
    }

    public List<ChunkResult> chunkFile(String path, String content, String language) {
        CodeChunker selectedChunker = fallbackChunker;

        if (chunkers != null && language != null) {
            for (CodeChunker c : chunkers) {
                if (!(c instanceof SlidingWindowCodeChunker) && c.supports(language)) {
                    selectedChunker = c;
                    break;
                }
            }
        }

        List<ChunkResult> rawChunks = selectedChunker.chunk(path, content, language);

        // Enforce max token and char bounds on chunks before persistence/embedding
        List<ChunkResult> boundedChunks = refineAndEnforceChunkBounds(path, rawChunks, maxChunkTokens, maxChunkChars);

        // Process secrets and compute content hashes
        for (ChunkResult chunk : boundedChunks) {
            String rawContent = chunk.getContent();
            boolean isSecret = secretDetectionService.containsSecret(rawContent);

            if (isSecret) {
                logger.warn("Secret Detection: Redacting sensitive chunk in file '{}'", path);
                chunk.setSensitive(true);
                chunk.setChunkType("SENSITIVE_SKIPPED");
                chunk.setContent("[REDACTED SENSITIVE CHUNK]");
                chunk.setContentHash(computeSha256("[REDACTED SENSITIVE CHUNK]"));
            } else {
                chunk.setSensitive(false);
                chunk.setContentHash(computeSha256(rawContent));
            }
        }

        return boundedChunks;
    }

    public static List<ChunkResult> refineAndEnforceChunkBounds(
            String path,
            List<ChunkResult> rawChunks,
            int maxTokens,
            int maxChars) {

        if (rawChunks == null || rawChunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChunkResult> result = new ArrayList<>();
        int globalChunkIndex = 0;

        for (ChunkResult raw : rawChunks) {
            String content = raw.getContent();
            if (content == null || content.isEmpty()) {
                continue;
            }

            String baseSymbol = (raw.getSymbolName() != null && !raw.getSymbolName().isEmpty())
                    ? raw.getSymbolName()
                    : getFileName(path);

            // Compute context header overhead (including part suffix metadata) and proportional safety margins so formattedContext stays strictly within maxTokens and maxChars
            int headerLen = ("File: " + path + " | Symbol: " + baseSymbol + " [Part 99/99]\n").length();
            int tokenMargin = Math.min(50, (int) (maxTokens * 0.05));
            int charMargin = Math.min(150, (int) (maxChars * 0.05));
            int safeMaxChars = Math.max(1, Math.min(maxChars - headerLen - charMargin, (maxTokens - tokenMargin) * 3));
            int safeMaxTokens = Math.max(1, Math.min(maxTokens - tokenMargin, (int) Math.floor(safeMaxChars / 3.0)));

            int tokens = estimateTokens(content);
            if (tokens <= safeMaxTokens && content.length() <= safeMaxChars) {
                raw.setChunkIndex(globalChunkIndex++);
                result.add(raw);
                continue;
            }

            // Raw chunk exceeds safe token or char bounds; split into bounded sub-chunks with zero content loss
            List<ChunkPiece> pieces = splitRawChunkContent(content, raw.getStartLine(), safeMaxTokens, safeMaxChars);
            int totalParts = pieces.size();

            for (int p = 0; p < totalParts; p++) {
                ChunkPiece piece = pieces.get(p);
                ChunkResult sub = new ChunkResult();
                sub.setChunkIndex(globalChunkIndex++);
                sub.setContent(piece.content);
                if (totalParts > 1) {
                    sub.setSymbolName(baseSymbol + " [Part " + (p + 1) + "/" + totalParts + "]");
                } else {
                    sub.setSymbolName(raw.getSymbolName());
                }
                sub.setChunkType(raw.getChunkType() != null ? raw.getChunkType() : "WINDOW");
                sub.setStartLine(piece.startLine);
                sub.setEndLine(piece.endLine);
                result.add(sub);
            }
        }

        // Final defensive validation: ensure every resulting chunk's formattedContext strictly obeys bounds
        for (ChunkResult res : result) {
            String testFormatted = "File: " + path + " | Symbol: " + (res.getSymbolName() != null ? res.getSymbolName() : "") + "\n" + res.getContent();
            int estTokens = estimateTokens(testFormatted);
            int charLen = testFormatted.length();
            if (estTokens > maxTokens || charLen > maxChars) {
                throw new IllegalStateException(
                        "Oversized chunk produced after bounds refinement for file '" + path + "': " + estTokens + " tokens / " + charLen + " chars vs limit " + maxTokens + " / " + maxChars
                );
            }
        }

        return result;
    }

    private static class ChunkPiece {
        final String content;
        final int startLine;
        final int endLine;

        ChunkPiece(String content, int startLine, int endLine) {
            this.content = content;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    private static List<ChunkPiece> splitRawChunkContent(String content, int baseStartLine, int maxTokens, int maxChars) {
        List<ChunkPiece> pieces = new ArrayList<>();
        int effectiveMaxChars = Math.max(1, Math.min(maxChars, maxTokens * 3));

        String[] lines = content.split("\r?\n", -1);
        int totalLines = lines.length;

        StringBuilder currentBuf = new StringBuilder();
        int pieceStartLineIndex = 0;

        for (int i = 0; i < totalLines; i++) {
            String line = lines[i];

            if (line.length() > effectiveMaxChars) {
                if (currentBuf.length() > 0) {
                    String bufStr = currentBuf.toString();
                    int pieceEndLineIndex = i - 1;
                    pieces.add(new ChunkPiece(
                            bufStr.endsWith("\n") ? bufStr.substring(0, bufStr.length() - 1) : bufStr,
                            baseStartLine + pieceStartLineIndex,
                            baseStartLine + pieceEndLineIndex
                    ));
                    currentBuf.setLength(0);
                }

                int slicePos = 0;
                int lineNo = baseStartLine + i;
                while (slicePos < line.length()) {
                    int endPos = Math.min(slicePos + effectiveMaxChars, line.length());
                    String slice = line.substring(slicePos, endPos);
                    pieces.add(new ChunkPiece(slice, lineNo, lineNo));
                    slicePos = endPos;
                }

                pieceStartLineIndex = i + 1;
                continue;
            }

            int lineLenWithNL = line.length() + 1;
            int proposedLen = currentBuf.length() + lineLenWithNL;
            int proposedTokens = estimateTokens(currentBuf.toString() + line + "\n");

            if (currentBuf.length() > 0 && (proposedLen > effectiveMaxChars || proposedTokens > maxTokens)) {
                String bufStr = currentBuf.toString();
                int pieceEndLineIndex = i - 1;
                pieces.add(new ChunkPiece(
                        bufStr.endsWith("\n") ? bufStr.substring(0, bufStr.length() - 1) : bufStr,
                        baseStartLine + pieceStartLineIndex,
                        baseStartLine + pieceEndLineIndex
                ));
                currentBuf.setLength(0);
                pieceStartLineIndex = i;
            }

            currentBuf.append(line).append("\n");
        }

        if (currentBuf.length() > 0) {
            String bufStr = currentBuf.toString();
            int pieceEndLineIndex = totalLines - 1;
            pieces.add(new ChunkPiece(
                    bufStr.endsWith("\n") ? bufStr.substring(0, bufStr.length() - 1) : bufStr,
                    baseStartLine + pieceStartLineIndex,
                    baseStartLine + pieceEndLineIndex
            ));
        }

        return pieces;
    }

    private static String getFileName(String path) {
        if (path == null) return "file";
        return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    }

    public static String computeSha256(String input) {
        if (input == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
