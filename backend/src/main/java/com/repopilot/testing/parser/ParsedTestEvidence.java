package com.repopilot.testing.parser;

import java.util.Map;

public class ParsedTestEvidence {

    private final String parserStatus;
    private final int testsRun;
    private final int failuresCount;
    private final int errorsCount;
    private final int skippedCount;
    private final int passedCount;
    private final Map<String, Object> metadata;

    public ParsedTestEvidence(String parserStatus, int testsRun, int failuresCount, int errorsCount, int skippedCount, int passedCount, Map<String, Object> metadata) {
        this.parserStatus = parserStatus != null ? parserStatus : "UNPARSED";
        this.testsRun = testsRun;
        this.failuresCount = failuresCount;
        this.errorsCount = errorsCount;
        this.skippedCount = skippedCount;
        this.passedCount = passedCount;
        this.metadata = metadata != null ? metadata : Map.of();
    }

    public static ParsedTestEvidence unparsed() {
        return new ParsedTestEvidence("UNPARSED", 0, 0, 0, 0, 0, Map.of());
    }

    public String getParserStatus() { return parserStatus; }
    public int getTestsRun() { return testsRun; }
    public int getFailuresCount() { return failuresCount; }
    public int getErrorsCount() { return errorsCount; }
    public int getSkippedCount() { return skippedCount; }
    public int getPassedCount() { return passedCount; }
    public Map<String, Object> getMetadata() { return metadata; }
}
