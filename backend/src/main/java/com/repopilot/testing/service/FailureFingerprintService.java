package com.repopilot.testing.service;

import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.policy.FailureFingerprintResult;

import java.util.List;

public interface FailureFingerprintService {

    String generateFingerprint(Ecosystem ecosystem, FailureClassification classification, ParsedTestEvidence evidence, String stdout, String stderr);

    FailureFingerprintResult analyzeFingerprintSequence(Ecosystem ecosystem, FailureClassification classification, ParsedTestEvidence evidence, String stdout, String stderr, List<String> previousFingerprints);

    String sanitizeEvidence(String rawContent);
}
