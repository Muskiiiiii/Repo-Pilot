package com.repopilot.report.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.report.dto.ReportSynthesisResponseDto;
import com.repopilot.report.service.ReportSynthesizerService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ReportSynthesisController {

    private final ReportSynthesizerService reportSynthesizerService;

    @Autowired
    public ReportSynthesisController(ReportSynthesizerService reportSynthesizerService) {
        this.reportSynthesizerService = reportSynthesizerService;
    }

    @PostMapping("/executions/{executionId}/report/synthesis")
    public ResponseEntity<ReportSynthesisResponseDto> synthesizeReport(
            @PathVariable UUID executionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        User currentUser = (principal != null) ? principal.getUser() : null;
        ReportSynthesisResponseDto dto = reportSynthesizerService.synthesizeReport(executionId, currentUser);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/executions/{executionId}/report/synthesis")
    public ResponseEntity<ReportSynthesisResponseDto> getSynthesisByExecutionId(
            @PathVariable UUID executionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        User currentUser = (principal != null) ? principal.getUser() : null;
        ReportSynthesisResponseDto dto = reportSynthesizerService.getSynthesisByExecutionId(executionId, currentUser);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/reports/{reportId}/synthesis")
    public ResponseEntity<ReportSynthesisResponseDto> getSynthesisByReportId(
            @PathVariable UUID reportId,
            @AuthenticationPrincipal UserPrincipal principal) {
        User currentUser = (principal != null) ? principal.getUser() : null;
        ReportSynthesisResponseDto dto = reportSynthesizerService.getSynthesisByReportId(reportId, currentUser);
        return ResponseEntity.ok(dto);
    }
}
