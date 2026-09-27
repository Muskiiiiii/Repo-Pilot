package com.repopilot.review.dto;

import java.util.ArrayList;
import java.util.List;

public class ReviewResponseDto {

    private String summary;
    private String overallRiskScore = "LOW";
    private String reviewContextHash;
    private List<ReviewFindingResponseDto> findings = new ArrayList<>();

    public ReviewResponseDto() {
    }

    public ReviewResponseDto(String summary, String overallRiskScore, String reviewContextHash, List<ReviewFindingResponseDto> findings) {
        this.summary = summary;
        this.overallRiskScore = overallRiskScore;
        this.reviewContextHash = reviewContextHash;
        this.findings = findings != null ? findings : new ArrayList<>();
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getOverallRiskScore() {
        return overallRiskScore;
    }

    public void setOverallRiskScore(String overallRiskScore) {
        this.overallRiskScore = overallRiskScore;
    }

    public String getReviewContextHash() {
        return reviewContextHash;
    }

    public void setReviewContextHash(String reviewContextHash) {
        this.reviewContextHash = reviewContextHash;
    }

    public List<ReviewFindingResponseDto> getFindings() {
        return findings;
    }

    public void setFindings(List<ReviewFindingResponseDto> findings) {
        this.findings = findings;
    }
}
