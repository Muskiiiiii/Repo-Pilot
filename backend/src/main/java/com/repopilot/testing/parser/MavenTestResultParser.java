package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MavenTestResultParser implements TestResultParser {

    private static final Pattern SUREFIRE_PATTERN = Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    @Override
    public boolean supports(Ecosystem ecosystem) {
        return ecosystem == Ecosystem.MAVEN;
    }

    @Override
    public ParsedTestEvidence parse(String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr);
        Matcher matcher = SUREFIRE_PATTERN.matcher(combined);

        if (!matcher.find()) {
            return ParsedTestEvidence.unparsed();
        }

        try {
            int testsRun = Integer.parseInt(matcher.group(1));
            int failures = Integer.parseInt(matcher.group(2));
            int errors = Integer.parseInt(matcher.group(3));
            int skipped = Integer.parseInt(matcher.group(4));
            int passed = Math.max(0, testsRun - failures - errors - skipped);

            return new ParsedTestEvidence("PARSED", testsRun, failures, errors, skipped, passed,
                    Map.of("runner", "mvn surefire"));
        } catch (Exception e) {
            return ParsedTestEvidence.unparsed();
        }
    }
}
