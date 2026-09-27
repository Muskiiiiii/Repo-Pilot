package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;

public interface TestResultParser {

    boolean supports(Ecosystem ecosystem);

    ParsedTestEvidence parse(String stdout, String stderr);
}
