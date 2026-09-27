package com.repopilot.testing.parser;

import com.repopilot.testing.entity.Ecosystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TestResultParserCompositeService {

    private final List<TestResultParser> parsers;

    @Autowired
    public TestResultParserCompositeService(List<TestResultParser> parsers) {
        this.parsers = parsers;
    }

    public ParsedTestEvidence parse(Ecosystem ecosystem, String stdout, String stderr) {
        if (ecosystem == null) return ParsedTestEvidence.unparsed();

        for (TestResultParser parser : parsers) {
            if (parser.supports(ecosystem)) {
                try {
                    return parser.parse(stdout, stderr);
                } catch (Exception e) {
                    return ParsedTestEvidence.unparsed();
                }
            }
        }
        return ParsedTestEvidence.unparsed();
    }
}
