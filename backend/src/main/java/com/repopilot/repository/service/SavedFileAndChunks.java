package com.repopilot.repository.service;

import com.repopilot.repository.entity.CodeChunk;
import com.repopilot.repository.entity.RepositoryFile;

import java.util.List;

public class SavedFileAndChunks {
    private final RepositoryFile file;
    private final List<CodeChunk> chunks;

    public SavedFileAndChunks(RepositoryFile file, List<CodeChunk> chunks) {
        this.file = file;
        this.chunks = chunks;
    }

    public RepositoryFile getFile() {
        return file;
    }

    public List<CodeChunk> getChunks() {
        return chunks;
    }
}
