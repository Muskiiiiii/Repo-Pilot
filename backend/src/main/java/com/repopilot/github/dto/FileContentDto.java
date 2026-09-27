package com.repopilot.github.dto;

public class FileContentDto {

    private String path;
    private String branch;
    private String content;
    private String encoding;
    private Long size;
    private boolean isBinary;

    public FileContentDto() {
    }

    public FileContentDto(String path, String branch, String content, String encoding, Long size, boolean isBinary) {
        this.path = path;
        this.branch = branch;
        this.content = content;
        this.encoding = encoding;
        this.size = size;
        this.isBinary = isBinary;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public boolean isBinary() {
        return isBinary;
    }

    public boolean getIsBinary() {
        return isBinary;
    }

    public void setBinary(boolean isBinary) {
        this.isBinary = isBinary;
    }

    public void setIsBinary(boolean isBinary) {
        this.isBinary = isBinary;
    }
}
