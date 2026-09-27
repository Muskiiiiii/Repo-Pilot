package com.repopilot.review.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.ReviewFindingResponseDto;
import com.repopilot.review.dto.UpdateFindingDispositionRequestDto;
import com.repopilot.review.service.ReviewFindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}")
public class ReviewFindingController {

    private final ReviewFindingService reviewFindingService;

    @Autowired
    public ReviewFindingController(ReviewFindingService reviewFindingService) {
        this.reviewFindingService = reviewFindingService;
    }

    @PatchMapping("/reviews/{reviewId}/findings/{findingId}/disposition")
    public ResponseEntity<ReviewFindingResponseDto> updateFindingDisposition(
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @PathVariable UUID findingId,
            @RequestBody UpdateFindingDispositionRequestDto request,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        ReviewFindingResponseDto response = reviewFindingService.updateFindingDisposition(
                projectId,
                taskId,
                reviewId,
                findingId,
                request,
                currentUserPrincipal.getUser()
        );

        return ResponseEntity.ok(response);
    }
}
