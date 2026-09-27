package com.repopilot.task.dto;

import java.util.UUID;

public class EvidenceReferenceDto {

    private UUID chunkId;
    private String filePath;
    private String fileLanguage;
    private String symbolName;
    private String chunkType;
    private Integer startLine;
    private Integer endLine;
    private String contentSnippet;
    private Double similarityScore;
    private Double distance;
    private String reason;

    public EvidenceReferenceDto() {
    }

    public EvidenceReferenceDto(UUID chunkId, String filePath, String fileLanguage, String symbolName,
                                String chunkType, Integer startLine, Integer endLine, String contentSnippet,
                                Double similarityScore, Double distance, String reason) {
        this.chunkId = chunkId;
        this.filePath = filePath;
        this.fileLanguage = fileLanguage;
        this.symbolName = symbolName;
        this.chunkType = chunkType;
        this.startLine = startLine;
        this.endLine = endLine;
        this.contentSnippet = contentSnippet;
        this.similarityScore = similarityScore;
        this.distance = distance;
        this.reason = reason;
    }

    public UUID getChunkId() { return chunkId; }
    public void setChunkId(UUID chunkId) { this.chunkId = chunkId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileLanguage() { return fileLanguage; }
    public void setFileLanguage(String fileLanguage) { this.fileLanguage = fileLanguage; }

    public String getSymbolName() { return symbolName; }
    public void setSymbolName(String symbolName) { this.symbolName = symbolName; }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }

    public Integer getStartLine() { return startLine; }
    public void setStartLine(Integer startLine) { this.startLine = startLine; }

    public Integer getEndLine() { return endLine; }
    public void setEndLine(Integer endLine) { this.endLine = endLine; }

    public String getContentSnippet() { return contentSnippet; }
    public void setContentSnippet(String contentSnippet) { this.contentSnippet = contentSnippet; }

    public Double getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }

    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
