package com.repopilot.task.dto;

public class ProposedChangeDto {

    private String filePath;
    private String symbolName;
    private Integer startLine;
    private Integer endLine;
    private String reason;
    private String changeDescription;

    public ProposedChangeDto() {
    }

    public ProposedChangeDto(String filePath, String symbolName, Integer startLine, Integer endLine, String reason, String changeDescription) {
        this.filePath = filePath;
        this.symbolName = symbolName;
        this.startLine = startLine;
        this.endLine = endLine;
        this.reason = reason;
        this.changeDescription = changeDescription;
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getSymbolName() { return symbolName; }
    public void setSymbolName(String symbolName) { this.symbolName = symbolName; }

    public Integer getStartLine() { return startLine; }
    public void setStartLine(Integer startLine) { this.startLine = startLine; }

    public Integer getEndLine() { return endLine; }
    public void setEndLine(Integer endLine) { this.endLine = endLine; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getChangeDescription() { return changeDescription; }
    public void setChangeDescription(String changeDescription) { this.changeDescription = changeDescription; }
}
