package com.repopilot.testing.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.TestRun;

import java.util.List;

public interface FailureClassifierService {

    FailureClassification classify(Ecosystem ecosystem, DockerExecutionResult execResult, List<CodeChange> codeChanges);

    FailureClassification classifyTestRun(TestRun testRun, List<CodeChange> codeChanges);

    boolean isFailureRelatedToCodeChanges(String stdout, String stderr, List<CodeChange> codeChanges);

    String sanitize(String content);
}
