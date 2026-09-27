package com.repopilot.report.dto;

import java.util.ArrayList;
import java.util.List;

public class ReportSynthesisOutputDto {

    private String executiveSummary;
    private String implementationExplanation;
    private String testingExplanation;
    private String securityExplanation;
    private List<String> limitations = new ArrayList<>();
    private List<String> developerNotes = new ArrayList<>();

    public ReportSynthesisOutputDto() {
    }

    public String getExecutiveSummary() {
        return executiveSummary;
    }

    public void setExecutiveSummary(String executiveSummary) {
        this.executiveSummary = executiveSummary;
    }

    public String getImplementationExplanation() {
        return implementationExplanation;
    }

    public void setImplementationExplanation(String implementationExplanation) {
        this.implementationExplanation = implementationExplanation;
    }

    public String getTestingExplanation() {
        return testingExplanation;
    }

    public void setTestingExplanation(String testingExplanation) {
        this.testingExplanation = testingExplanation;
    }

    public String getSecurityExplanation() {
        return securityExplanation;
    }

    public void setSecurityExplanation(String securityExplanation) {
        this.securityExplanation = securityExplanation;
    }

    public List<String> getLimitations() {
        return limitations;
    }

    public void setLimitations(List<String> limitations) {
        this.limitations = (limitations != null) ? limitations : new ArrayList<>();
    }

    public List<String> getDeveloperNotes() {
        return developerNotes;
    }

    public void setDeveloperNotes(List<String> developerNotes) {
        this.developerNotes = (developerNotes != null) ? developerNotes : new ArrayList<>();
    }
}
