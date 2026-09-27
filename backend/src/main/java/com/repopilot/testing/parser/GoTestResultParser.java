package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GoTestResultParser implements TestResultParser {

    @Override
    public boolean supports(Ecosystem ecosystem) {
        return ecosystem == Ecosystem.GO;
    }

    @Override
    public ParsedTestEvidence parse(String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr);

        int passCount = countOccurrences(combined, "--- PASS:");
        int failCount = countOccurrences(combined, "--- FAIL:");
        int skipCount = countOccurrences(combined, "--- SKIP:");

        int total = passCount + failCount + skipCount;

        if (total > 0) {
            return new ParsedTestEvidence("PARSED", total, failCount, 0, skipCount, passCount, Map.of("runner", "go test"));
        }

        if (combined.contains("PASS") || combined.contains("ok")) {
            return new ParsedTestEvidence("PARSED", 1, 0, 0, 0, 1, Map.of("runner", "go test"));
        } else if (combined.contains("FAIL")) {
            return new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("runner", "go test"));
        }
        return ParsedTestEvidence.unparsed();
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
