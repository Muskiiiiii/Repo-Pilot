package com.repopilot.ai.client;

import com.repopilot.report.dto.ReportSynthesisInputDto;
import com.repopilot.report.dto.ReportSynthesisOutputDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewResponseDto;

import java.util.List;
import java.util.Map;

public interface AiServiceClient {
    List<String> generateEmbeddings(List<String> texts);
    List<String> generateQueryEmbeddings(List<String> queries);
    String generateQueryEmbedding(String query);

    Map<String, Object> analyzeIssue(String title, String description);
    Map<String, Object> generatePlan(Map<String, Object> payload);
    Map<String, Object> generateCode(Map<String, Object> payload);
    Map<String, Object> diagnoseFailure(Map<String, Object> payload);
    Map<String, Object> generateRepairProposal(Map<String, Object> payload);

    ReviewResponseDto executeCodeReview(ReviewContext context);
    ReportSynthesisOutputDto synthesizeReport(ReportSynthesisInputDto inputDto);
}
