package com.repopilot.repository.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SearchRequestDto {

    @NotBlank(message = "Search query cannot be blank")
    @Size(max = 1000, message = "Search query cannot exceed 1000 characters")
    private String query;

    @Min(value = 1, message = "topK must be at least 1")
    @Max(value = 100, message = "topK cannot exceed 100")
    private Integer topK = 10;

    public SearchRequestDto() {
    }

    public SearchRequestDto(String query, Integer topK) {
        this.query = query;
        this.topK = topK;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK != null ? topK : 10;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
