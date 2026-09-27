package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PythonTestResultParser implements TestResultParser {

    private static final Pattern RAN_TESTS_PATTERN = Pattern.compile("Ran\\s+(\\d+)\\s+test[s]?");
    private static final Pattern FAILURES_PATTERN = Pattern.compile("failures=(\\d+)");
    private static final Pattern ERRORS_PATTERN = Pattern.compile("errors=(\\d+)");
    private static final Pattern SKIPPED_PATTERN = Pattern.compile("skipped=(\\d+)");

    @Override
    public boolean supports(Ecosystem ecosystem) {
        return ecosystem == Ecosystem.PYTHON;
    }

    @Override
    public ParsedTestEvidence parse(String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr);
        Matcher ranMatcher = RAN_TESTS_PATTERN.matcher(combined);

        if (!ranMatcher.find()) {
            return ParsedTestEvidence.unparsed();
        }

        try {
            int testsRun = Integer.parseInt(ranMatcher.group(1));

            int failures = 0;
            Matcher failMatcher = FAILURES_PATTERN.matcher(combined);
            if (failMatcher.find()) {
                failures = Integer.parseInt(failMatcher.group(1));
            }

            int errors = 0;
            Matcher errMatcher = ERRORS_PATTERN.matcher(combined);
            if (errMatcher.find()) {
                errors = Integer.parseInt(errMatcher.group(1));
            }

            int skipped = 0;
            Matcher skipMatcher = SKIPPED_PATTERN.matcher(combined);
            if (skipMatcher.find()) {
                skipped = Integer.parseInt(skipMatcher.group(1));
            }

            int passed = Math.max(0, testsRun - failures - errors - skipped);

            return new ParsedTestEvidence("PARSED", testsRun, failures, errors, skipped, passed,
                    Map.of("runner", "unittest"));
        } catch (Exception e) {
            return ParsedTestEvidence.unparsed();
        }
    }
}
