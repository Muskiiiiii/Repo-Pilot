package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RustTestResultParser implements TestResultParser {

    private static final Pattern RUST_PATTERN = Pattern.compile("test result:\\s*\\w+\\.\\s*(\\d+)\\s+passed;\\s*(\\d+)\\s+failed;\\s*(\\d+)\\s+ignored");

    @Override
    public boolean supports(Ecosystem ecosystem) {
        return ecosystem == Ecosystem.RUST;
    }

    @Override
    public ParsedTestEvidence parse(String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr);
        Matcher matcher = RUST_PATTERN.matcher(combined);

        if (!matcher.find()) {
            return ParsedTestEvidence.unparsed();
        }

        try {
            int passed = Integer.parseInt(matcher.group(1));
            int failed = Integer.parseInt(matcher.group(2));
            int ignored = Integer.parseInt(matcher.group(3));
            int total = passed + failed + ignored;

            return new ParsedTestEvidence("PARSED", total, failed, 0, ignored, passed,
                    Map.of("runner", "cargo test"));
        } catch (Exception e) {
            return ParsedTestEvidence.unparsed();
        }
    }
}
