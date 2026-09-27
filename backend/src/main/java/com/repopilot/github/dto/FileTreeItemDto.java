package com.repopilot.github.dto;

public class FileTreeItemDto {

    private String path;
    private String type; // "blob" or "tree"
    private Long size;
    private String sha;

    public FileTreeItemDto() {
    }

    public FileTreeItemDto(String path, String type, Long size, String sha) {
        this.path = path;
        this.type = type;
        this.size = size;
        this.sha = sha;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getSha() {
        return sha;
    }

    public void setSha(String sha) {
        this.sha = sha;
    }
}
