package com.repopilot.repository.dto;

import java.util.List;

public class SearchResponseDto {

    private String query;
    private int topK;
    private int totalResults;
    private List<SearchResultItemDto> results;

    public SearchResponseDto() {
    }

    public SearchResponseDto(String query, int topK, int totalResults, List<SearchResultItemDto> results) {
        this.query = query;
        this.topK = topK;
        this.totalResults = totalResults;
        this.results = results;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getTotalResults() {
        return totalResults;
    }

    public void setTotalResults(int totalResults) {
        this.totalResults = totalResults;
    }

    public List<SearchResultItemDto> getResults() {
        return results;
    }

    public void setResults(List<SearchResultItemDto> results) {
        this.results = results;
    }
}
