package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GradleTestResultParser implements TestResultParser {

    private static final Pattern GRADLE_PATTERN = Pattern.compile("(\\d+)\\s+tests\\s+completed,\\s*(\\d+)\\s+failed,\\s*(\\d+)\\s+skipped");

    @Override
    public boolean supports(Ecosystem ecosystem) {
        return ecosystem == Ecosystem.GRADLE;
    }

    @Override
    public ParsedTestEvidence parse(String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr);
        Matcher matcher = GRADLE_PATTERN.matcher(combined);

        if (!matcher.find()) {
            return ParsedTestEvidence.unparsed();
        }

        try {
            int testsRun = Integer.parseInt(matcher.group(1));
            int failed = Integer.parseInt(matcher.group(2));
            int skipped = Integer.parseInt(matcher.group(3));
            int passed = Math.max(0, testsRun - failed - skipped);

            return new ParsedTestEvidence("PARSED", testsRun, failed, 0, skipped, passed,
                    Map.of("runner", "gradle test"));
        } catch (Exception e) {
            return ParsedTestEvidence.unparsed();
        }
    }
}
