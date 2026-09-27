package com.repopilot.repository.service;

import com.repopilot.repository.chunker.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeChunkerServiceTest {

    private CodeChunkerService codeChunkerService;

    @BeforeEach
    void setUp() {
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        SlidingWindowCodeChunker slidingWindowChunker = new SlidingWindowCodeChunker();
        JavaCodeChunker javaChunker = new JavaCodeChunker();
        PythonCodeChunker pythonChunker = new PythonCodeChunker();

        List<CodeChunker> chunkers = List.of(javaChunker, pythonChunker, slidingWindowChunker);
        codeChunkerService = new CodeChunkerService(chunkers, slidingWindowChunker, secretDetectionService);
    }

    @Test
    void chunkFile_javaCode_createsChunks() {
        String javaCode = "public class UserService {\n" +
                "    public void createUser() {\n" +
                "        System.out.println(\"User created\");\n" +
                "    }\n" +
                "}";

        List<ChunkResult> chunks = codeChunkerService.chunkFile("UserService.java", javaCode, "java");
        assertFalse(chunks.isEmpty());
        ChunkResult first = chunks.get(0);
        assertNotNull(first.getContentHash());
        assertFalse(first.isSensitive());
    }

    @Test
    void chunkFile_pythonCode_createsChunks() {
        String pythonCode = "def process_data():\n" +
                "    print('processing')\n\n" +
                "class DataProcessor:\n" +
                "    def run(self):\n" +
                "        pass\n";

        List<ChunkResult> chunks = codeChunkerService.chunkFile("processor.py", pythonCode, "python");
        assertFalse(chunks.isEmpty());
        assertFalse(chunks.get(0).isSensitive());
    }

    @Test
    void chunkFile_secretDetected_redactsChunkAndSetsSensitiveFlag() {
        String secretCode = "public class Config {\n" +
                "    private String awsKey = \"AKIAIOSFODNN7EXAMPLE\";\n" +
                "}";

        List<ChunkResult> chunks = codeChunkerService.chunkFile("Config.java", secretCode, "java");
        assertFalse(chunks.isEmpty());
        ChunkResult chunk = chunks.get(0);
        assertTrue(chunk.isSensitive());
        assertEquals("SENSITIVE_SKIPPED", chunk.getChunkType());
        assertEquals("[REDACTED SENSITIVE CHUNK]", chunk.getContent());
        assertFalse(chunk.getContent().contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void chunkFile_oversizedMultiLineChunk_splitsDeterministicallyWithoutContentLoss() {
        // Instantiate CodeChunkerService with small bounds (50 tokens, 150 chars) for easy testing
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        SlidingWindowCodeChunker slidingWindowChunker = new SlidingWindowCodeChunker();
        JavaCodeChunker javaChunker = new JavaCodeChunker();
        CodeChunkerService boundedService = new CodeChunkerService(
                List.of(javaChunker, slidingWindowChunker),
                slidingWindowChunker,
                secretDetectionService,
                50,
                150
        );

        StringBuilder sb = new StringBuilder("public class LargeClass {\n");
        for (int i = 1; i <= 30; i++) {
            sb.append("    public void testMethod").append(i).append("() { System.out.println(\"line ").append(i).append("\"); }\n");
        }
        sb.append("}");

        String originalCode = sb.toString();
        List<ChunkResult> chunks = boundedService.chunkFile("LargeClass.java", originalCode, "java");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() > 1, "Oversized chunk should be split into multiple sub-chunks");

        // Verify bounds, part metadata, line numbers, and 100% content preservation
        StringBuilder reconstructed = new StringBuilder();
        int expectedNextLine = 1;

        for (int idx = 0; idx < chunks.size(); idx++) {
            ChunkResult c = chunks.get(idx);
            assertEquals(idx, c.getChunkIndex());
            assertTrue(CodeChunkerService.estimateTokens(c.getContent()) <= 50, "Token count must be <= 50");
            assertTrue(c.getContent().length() <= 150, "Char length must be <= 150");
            assertTrue(c.getSymbolName().contains("[Part "), "Symbol name should contain part metadata");
            assertEquals(expectedNextLine, c.getStartLine(), "Sub-chunk startLine should continue from previous endLine");

            String content = c.getContent();
            reconstructed.append(content);
            if (idx < chunks.size() - 1) {
                reconstructed.append("\n");
            }
            int lineCountInChunk = content.split("\r?\n", -1).length;
            expectedNextLine = c.getStartLine() + lineCountInChunk;
        }

        assertEquals(originalCode, reconstructed.toString(), "100% of original source content must be preserved");
    }

    @Test
    void chunkFile_oversizedSingleLine_slicesIntoBoundedChunks() {
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        SlidingWindowCodeChunker slidingWindowChunker = new SlidingWindowCodeChunker();
        CodeChunkerService boundedService = new CodeChunkerService(
                List.of(slidingWindowChunker),
                slidingWindowChunker,
                secretDetectionService,
                30,
                90
        );

        String longLine = "A".repeat(300);
        List<ChunkResult> chunks = boundedService.chunkFile("minified.js", longLine, "javascript");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 3);

        StringBuilder joined = new StringBuilder();
        for (ChunkResult c : chunks) {
            assertTrue(c.getContent().length() <= 90);
            joined.append(c.getContent());
        }

        assertEquals(longLine, joined.toString(), "Single long line content must be 100% preserved when sliced");
    }

    @Test
    void refineAndEnforceChunkBounds_accountsForContextHeaderOverheadAndDefensiveValidation() {
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        SlidingWindowCodeChunker slidingWindowChunker = new SlidingWindowCodeChunker();
        CodeChunkerService service = new CodeChunkerService(
                List.of(slidingWindowChunker),
                slidingWindowChunker,
                secretDetectionService,
                2000,
                6000
        );

        // Create a raw content string of 5980 characters (under 6000 raw, but with header it exceeds 6000)
        String path = "solutions/typescript/tsconfig.json";
        String line = "// " + "x".repeat(100) + "\n";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 5980) {
            sb.append(line);
        }
        String content = sb.toString();

        List<ChunkResult> chunks = service.chunkFile(path, content, "json");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2, "Chunk that exceeds formatted bounds must be split");

        for (int i = 0; i < chunks.size(); i++) {
            ChunkResult c = chunks.get(i);
            assertEquals(i, c.getChunkIndex());
            String formatted = "File: " + path + " | Symbol: " + (c.getSymbolName() != null ? c.getSymbolName() : "") + "\n" + c.getContent();
            assertTrue(formatted.length() <= 6000, "Formatted context length must be <= 6000");
            assertTrue(CodeChunkerService.estimateTokens(formatted) <= 2000, "Formatted context tokens must be <= 2000");
            assertNotNull(c.getContentHash());
            assertFalse(c.getContentHash().isEmpty());
        }
    }

    @Test
    void chunkFile_exceedingBothLimits_splitsAndPreservesTraceability() {
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        SlidingWindowCodeChunker slidingWindowChunker = new SlidingWindowCodeChunker();
        CodeChunkerService service = new CodeChunkerService(
                List.of(slidingWindowChunker),
                slidingWindowChunker,
                secretDetectionService,
                100,
                300
        );

        String path = "src/HeavyFile.ts";
        String content = ("const data = '" + "A".repeat(500) + "';\n").repeat(5);

        List<ChunkResult> chunks = service.chunkFile(path, content, "typescript");
        assertFalse(chunks.isEmpty());

        for (int i = 0; i < chunks.size(); i++) {
            ChunkResult c = chunks.get(i);
            assertEquals(i, c.getChunkIndex());
            String formatted = "File: " + path + " | Symbol: " + (c.getSymbolName() != null ? c.getSymbolName() : "") + "\n" + c.getContent();
            assertTrue(formatted.length() <= 300);
            assertTrue(CodeChunkerService.estimateTokens(formatted) <= 100);
        }
    }
}
