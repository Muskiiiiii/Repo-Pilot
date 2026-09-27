package com.repopilot.repository.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SlidingWindowCodeChunker implements CodeChunker {

    private static final int WINDOW_SIZE = 60;
    private static final int OVERLAP = 15;

    @Override
    public boolean supports(String language) {
        // Fallback chunker supports any language
        return true;
    }

    @Override
    public List<ChunkResult> chunk(String path, String content, String language) {
        List<ChunkResult> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }

        String[] lines = content.split("\r?\n", -1);
        int totalLines = lines.length;

        if (totalLines <= WINDOW_SIZE) {
            ChunkResult chunk = new ChunkResult();
            chunk.setChunkIndex(0);
            chunk.setContent(content);
            chunk.setSymbolName(getFileName(path));
            chunk.setChunkType("WINDOW");
            chunk.setStartLine(1);
            chunk.setEndLine(totalLines);
            chunks.add(chunk);
            return chunks;
        }

        int index = 0;
        int start = 0;
        while (start < totalLines) {
            int end = Math.min(start + WINDOW_SIZE, totalLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines[i]);
                if (i < end - 1) {
                    sb.append("\n");
                }
            }

            ChunkResult chunk = new ChunkResult();
            chunk.setChunkIndex(index++);
            chunk.setContent(sb.toString());
            chunk.setSymbolName(getFileName(path) + ":" + (start + 1) + "-" + end);
            chunk.setChunkType("WINDOW");
            chunk.setStartLine(start + 1);
            chunk.setEndLine(end);
            chunks.add(chunk);

            if (end >= totalLines) {
                break;
            }
            start += (WINDOW_SIZE - OVERLAP);
        }

        return chunks;
    }

    private String getFileName(String path) {
        if (path == null) return "file";
        return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    }
}
