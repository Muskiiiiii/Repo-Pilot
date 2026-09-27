package com.repopilot.repository.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PythonCodeChunker implements CodeChunker {

    private static final Pattern DEF_PATTERN = Pattern.compile("^\\s*(?:async\\s+)?def\\s+([a-zA-Z0-9_]+)\\s*\\(");
    private static final Pattern CLASS_PATTERN = Pattern.compile("^\\s*class\\s+([a-zA-Z0-9_]+)");

    @Override
    public boolean supports(String language) {
        return "python".equalsIgnoreCase(language);
    }

    @Override
    public List<ChunkResult> chunk(String path, String content, String language) {
        List<ChunkResult> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }

        String[] lines = content.split("\r?\n", -1);
        int totalLines = lines.length;

        int chunkIndex = 0;
        int currentStart = 0;
        String currentSymbol = getFileName(path);
        String currentType = "MODULE";
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < totalLines; i++) {
            String line = lines[i];

            Matcher classMatcher = CLASS_PATTERN.matcher(line);
            Matcher defMatcher = DEF_PATTERN.matcher(line);

            boolean isNewBlock = (classMatcher.find() || defMatcher.find()) && (i > currentStart + 5);

            if (isNewBlock && currentChunk.length() > 0) {
                ChunkResult chunk = new ChunkResult();
                chunk.setChunkIndex(chunkIndex++);
                chunk.setContent(currentChunk.toString().trim());
                chunk.setSymbolName(currentSymbol);
                chunk.setChunkType(currentType);
                chunk.setStartLine(currentStart + 1);
                chunk.setEndLine(i);
                chunks.add(chunk);

                currentChunk.setLength(0);
                currentStart = i;
            }

            if (classMatcher.reset(line).find()) {
                currentSymbol = classMatcher.group(1);
                currentType = "CLASS";
            } else if (defMatcher.reset(line).find()) {
                currentSymbol = defMatcher.group(1);
                currentType = "FUNCTION";
            }

            currentChunk.append(line).append("\n");
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
        if (path == null) return "PythonModule";
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }
}
