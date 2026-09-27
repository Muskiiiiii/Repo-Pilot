package com.repopilot.testing.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class AIRepairProposalDto {

    private String rationale;

    @JsonProperty("fileModifications")
    @JsonAlias({"changes", "file_modifications"})
    private List<ProposedFileModificationDto> fileModifications;

    public AIRepairProposalDto() {
    }

    public AIRepairProposalDto(String rationale, List<ProposedFileModificationDto> fileModifications) {
        this.rationale = rationale;
        this.fileModifications = fileModifications;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public List<ProposedFileModificationDto> getFileModifications() {
        return fileModifications;
    }

    public void setFileModifications(List<ProposedFileModificationDto> fileModifications) {
        this.fileModifications = fileModifications;
    }

    public List<ProposedFileModificationDto> getChanges() {
        return fileModifications;
    }

    public void setChanges(List<ProposedFileModificationDto> changes) {
        this.fileModifications = changes;
    }
}
