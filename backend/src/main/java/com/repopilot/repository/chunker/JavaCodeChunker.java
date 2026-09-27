package com.repopilot.repository.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaCodeChunker implements CodeChunker {

    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:public|protected|private|static|final|synchronized|abstract|native|\\s)+[\\w<>\\[\\]]+\\s+([a-zA-Z0-9_]+)\\s*\\([^)]*\\)\\s*(?:throws [\\w\\s,]+)?\\s*\\{");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public|protected|private|abstract|static|final|\\s)*(?:class|interface|enum|record)\\s+([a-zA-Z0-9_]+)");

    @Override
    public boolean supports(String language) {
        return "java".equalsIgnoreCase(language);
    }

    @Override
    public List<ChunkResult> chunk(String path, String content, String language) {
        List<ChunkResult> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }

        String[] lines = content.split("\r?\n", -1);
        int totalLines = lines.length;

        // Try method / class block identification
        int chunkIndex = 0;
        int currentStart = 0;
        String currentSymbol = getFileName(path);
        String currentType = "CLASS";
        int braceDepth = 0;
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < totalLines; i++) {
            String line = lines[i];
            currentChunk.append(line).append("\n");

            Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            Matcher classMatcher = CLASS_PATTERN.matcher(line);

            if (classMatcher.find() && braceDepth == 0) {
                currentSymbol = classMatcher.group(1);
                currentType = "CLASS";
            } else if (methodMatcher.find()) {
                currentSymbol = methodMatcher.group(1);
                currentType = "METHOD";
            }

            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // Emit chunk at method/class boundary or max chunk length
            if ((braceDepth == 1 && line.trim().equals("}")) || (i - currentStart >= 60)) {
                if (!currentChunk.toString().isBlank()) {
                    ChunkResult chunk = new ChunkResult();
                    chunk.setChunkIndex(chunkIndex++);
                    chunk.setContent(currentChunk.toString().trim());
                    chunk.setSymbolName(currentSymbol);
                    chunk.setChunkType(currentType);
                    chunk.setStartLine(currentStart + 1);
                    chunk.setEndLine(i + 1);
                    chunks.add(chunk);

                    currentChunk.setLength(0);
                    currentStart = i + 1;
                }
            }
        }

        if (currentChunk.length() > 0 && !currentChunk.toString().isBlank()) {
            ChunkResult chunk = new ChunkResult();
            chunk.setChunkIndex(chunkIndex);
            chunk.setContent(currentChunk.toString().trim());
            chunk.setSymbolName(currentSymbol);
            chunk.setChunkType(currentType);
            chunk.setStartLine(currentStart + 1);
            chunk.setEndLine(totalLines);
            chunks.add(chunk);
        }

        return chunks.isEmpty() ? new SlidingWindowCodeChunker().chunk(path, content, language) : chunks;
    }

    private String getFileName(String path) {
        if (path == null) return "JavaFile";
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }
}
