package com.repopilot.repository.dto;

import java.util.UUID;

public class SearchResultItemDto {

    private UUID chunkId;
    private String filePath;
    private String fileLanguage;
    private String symbolName;
    private String chunkType;
    private int startLine;
    private int endLine;
    private String content;
    private double distance;
    private double similarityScore;

    public SearchResultItemDto() {
    }

    public SearchResultItemDto(UUID chunkId, String filePath, String fileLanguage, String symbolName,
                               String chunkType, int startLine, int endLine, String content,
                               double distance, double similarityScore) {
        this.chunkId = chunkId;
        this.filePath = filePath;
        this.fileLanguage = fileLanguage;
        this.symbolName = symbolName;
        this.chunkType = chunkType;
        this.startLine = startLine;
        this.endLine = endLine;
        this.content = content;
        this.distance = distance;
        this.similarityScore = similarityScore;
    }

    public UUID getChunkId() {
        return chunkId;
    }

    public void setChunkId(UUID chunkId) {
        this.chunkId = chunkId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileLanguage() {
        return fileLanguage;
    }

    public void setFileLanguage(String fileLanguage) {
        this.fileLanguage = fileLanguage;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public double getSimilarityScore() {
        return similarityScore;
    }

    public void setSimilarityScore(double similarityScore) {
        this.similarityScore = similarityScore;
    }
}
