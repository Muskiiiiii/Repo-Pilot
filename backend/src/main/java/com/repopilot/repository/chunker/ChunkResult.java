package com.repopilot.repository.chunker;

public class ChunkResult {
    private int chunkIndex;
    private String content;
    private String symbolName;
    private String chunkType;
    private int startLine;
    private int endLine;
    private String contentHash;
    private boolean isSensitive;

    public ChunkResult() {
    }

    public ChunkResult(int chunkIndex, String content, String symbolName, String chunkType, int startLine, int endLine, String contentHash, boolean isSensitive) {
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.symbolName = symbolName;
        this.chunkType = chunkType;
        this.startLine = startLine;
        this.endLine = endLine;
        this.contentHash = contentHash;
        this.isSensitive = isSensitive;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public void setSymbolName(String symbolName) {
        this.symbolName = symbolName;
    }

    public String getChunkType() {
        return chunkType;
    }

    public void setChunkType(String chunkType) {
        this.chunkType = chunkType;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isSensitive() {
        return isSensitive;
    }

    public void setSensitive(boolean sensitive) {
        isSensitive = sensitive;
    }
}
