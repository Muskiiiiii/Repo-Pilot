package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NodeTestResultParser implements TestResultParser {

    private static final Pattern TESTS_PATTERN = Pattern.compile("(?:ℹ|#)\\s*tests\\s+(\\d+)");
    private static final Pattern PASS_PATTERN = Pattern.compile("(?:ℹ|#)\\s*pass\\s+(\\d+)");
    private static final Pattern FAIL_PATTERN = Pattern.compile("(?:ℹ|#)\\s*fail\\s+(\\d+)");
    private static final Pattern SKIP_PATTERN = Pattern.compile("(?:ℹ|#)\\s*skipped\\s+(\\d+)");

    @Override
    public boolean supports(Ecosystem ecosystem) {
        return ecosystem == Ecosystem.NODE;
    }

    @Override
    public ParsedTestEvidence parse(String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr);
        Matcher testsMatcher = TESTS_PATTERN.matcher(combined);

        if (!testsMatcher.find()) {
            return ParsedTestEvidence.unparsed();
        }

        try {
            int testsRun = Integer.parseInt(testsMatcher.group(1));

            int passed = 0;
            Matcher passMatcher = PASS_PATTERN.matcher(combined);
            if (passMatcher.find()) {
                passed = Integer.parseInt(passMatcher.group(1));
            }

            int failed = 0;
            Matcher failMatcher = FAIL_PATTERN.matcher(combined);
            if (failMatcher.find()) {
                failed = Integer.parseInt(failMatcher.group(1));
            }

            int skipped = 0;
            Matcher skipMatcher = SKIP_PATTERN.matcher(combined);
            if (skipMatcher.find()) {
                skipped = Integer.parseInt(skipMatcher.group(1));
            }

            return new ParsedTestEvidence("PARSED", testsRun, failed, 0, skipped, passed,
                    Map.of("runner", "node --test"));
        } catch (Exception e) {
            return ParsedTestEvidence.unparsed();
        }
    }
}
