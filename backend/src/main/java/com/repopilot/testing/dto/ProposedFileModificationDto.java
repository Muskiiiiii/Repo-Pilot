package com.repopilot.testing.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ProposedFileModificationDto {

    @JsonProperty("filePath")
    @JsonAlias({"path", "file_path"})
    private String filePath;

    private String operation = "REPLACE";

    private Integer startLine;
    private Integer endLine;

    @JsonProperty("targetContent")
    @JsonAlias({"search", "target_content"})
    private String targetContent;

    @JsonProperty("replacementContent")
    @JsonAlias({"replacement", "replacement_content"})
    private String replacementContent;

    public ProposedFileModificationDto() {
    }

    public ProposedFileModificationDto(String filePath, Integer startLine, Integer endLine, String targetContent, String replacementContent) {
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.targetContent = targetContent;
        this.replacementContent = replacementContent;
    }

    public ProposedFileModificationDto(String filePath, String operation, String targetContent, String replacementContent) {
        this.filePath = filePath;
        this.operation = operation;
        this.targetContent = targetContent;
        this.replacementContent = replacementContent;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getPath() {
        return filePath;
    }

    public void setPath(String path) {
        this.filePath = path;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }

    public String getTargetContent() {
        return targetContent;
    }

    public void setTargetContent(String targetContent) {
        this.targetContent = targetContent;
    }

    public String getSearch() {
        return targetContent;
    }

    public void setSearch(String search) {
        this.targetContent = search;
    }

    public String getReplacementContent() {
        return replacementContent;
    }

    public void setReplacementContent(String replacementContent) {
        this.replacementContent = replacementContent;
    }

    public String getReplacement() {
        return replacementContent;
    }

    public void setReplacement(String replacement) {
        this.replacementContent = replacement;
    }
}
