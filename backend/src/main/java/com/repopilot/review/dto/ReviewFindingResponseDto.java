package com.repopilot.review.dto;

import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingSide;
import com.repopilot.review.entity.FindingType;

import com.repopilot.review.entity.FindingStatus;

import java.util.UUID;

public class ReviewFindingResponseDto {

    private UUID id;
    private FindingStatus status = FindingStatus.OPEN;
    private FindingType findingType = FindingType.AI_FINDING;
    private String category;
    private String cweId;
    private FindingSeverity severity;
    private FindingConfidence confidence;
    private String title;
    private String description;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private FindingSide side = FindingSide.PROPOSED;
    private String evidence;
    private String remediation;

    public ReviewFindingResponseDto() {
    }

    public ReviewFindingResponseDto(FindingType findingType, String category, String cweId, FindingSeverity severity,
                                   FindingConfidence confidence, String title, String description, String filePath,
                                   Integer startLine, Integer endLine, FindingSide side, String evidence, String remediation) {
        this.findingType = findingType;
        this.category = category;
        this.cweId = cweId;
        this.severity = severity;
        this.confidence = confidence;
        this.title = title;
        this.description = description;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.side = side;
        this.evidence = evidence;
        this.remediation = remediation;
    }

    public FindingType getFindingType() {
        return findingType;
    }

    public void setFindingType(FindingType findingType) {
        this.findingType = findingType;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCweId() {
        return cweId;
    }

    public void setCweId(String cweId) {
        this.cweId = cweId;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(FindingSeverity severity) {
        this.severity = severity;
    }

    public FindingConfidence getConfidence() {
        return confidence;
    }

    public void setConfidence(FindingConfidence confidence) {
        this.confidence = confidence;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
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

    public FindingSide getSide() {
        return side;
    }

    public void setSide(FindingSide side) {
        this.side = side;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getRemediation() {
        return remediation;
    }

    public void setRemediation(String remediation) {
        this.remediation = remediation;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public void setStatus(FindingStatus status) {
        this.status = status;
    }
}
