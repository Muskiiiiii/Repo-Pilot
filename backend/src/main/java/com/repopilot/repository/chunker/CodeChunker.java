package com.repopilot.repository.chunker;

import java.util.List;

public interface CodeChunker {
    boolean supports(String language);
    List<ChunkResult> chunk(String path, String content, String language);
}
