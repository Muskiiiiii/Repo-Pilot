package com.repopilot.testing;

import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.parser.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestResultParsersTest {

    private final TestResultParserCompositeService compositeService = new TestResultParserCompositeService(List.of(
            new PythonTestResultParser(),
            new NodeTestResultParser(),
            new MavenTestResultParser(),
            new GradleTestResultParser(),
            new GoTestResultParser(),
            new RustTestResultParser()
    ));

    @Test
    @DisplayName("Python unittest output parser extracts pass and failure counts")
    void testPythonParser() {
        String stdout = "Ran 5 tests in 0.002s\n\nFAILED (failures=2, errors=1, skipped=1)";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.PYTHON, stdout, "");

        assertEquals("PARSED", evidence.getParserStatus());
        assertEquals(5, evidence.getTestsRun());
        assertEquals(2, evidence.getFailuresCount());
        assertEquals(1, evidence.getErrorsCount());
        assertEquals(1, evidence.getSkippedCount());
        assertEquals(1, evidence.getPassedCount());
    }

    @Test
    @DisplayName("Node --test output parser extracts TAP test counts")
    void testNodeParser() {
        String stdout = "# tests 10\n# pass 7\n# fail 2\n# cancelled 0\n# skipped 1\n# todo 0\n# duration_ms 45.2";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.NODE, stdout, "");

        assertEquals("PARSED", evidence.getParserStatus());
        assertEquals(10, evidence.getTestsRun());
        assertEquals(7, evidence.getPassedCount());
        assertEquals(2, evidence.getFailuresCount());
        assertEquals(1, evidence.getSkippedCount());
    }

    @Test
    @DisplayName("Maven Surefire output parser extracts test summary")
    void testMavenParser() {
        String stdout = "[INFO] Results:\n[INFO]\n[INFO] Tests run: 12, Failures: 1, Errors: 1, Skipped: 2\n[INFO]\n[INFO] BUILD FAILURE";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.MAVEN, stdout, "");

        assertEquals("PARSED", evidence.getParserStatus());
        assertEquals(12, evidence.getTestsRun());
        assertEquals(1, evidence.getFailuresCount());
        assertEquals(1, evidence.getErrorsCount());
        assertEquals(2, evidence.getSkippedCount());
        assertEquals(8, evidence.getPassedCount());
    }

    @Test
    @DisplayName("Gradle test output parser extracts test summary")
    void testGradleParser() {
        String stdout = "8 tests completed, 2 failed, 1 skipped";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.GRADLE, stdout, "");

        assertEquals("PARSED", evidence.getParserStatus());
        assertEquals(8, evidence.getTestsRun());
        assertEquals(2, evidence.getFailuresCount());
        assertEquals(1, evidence.getSkippedCount());
        assertEquals(5, evidence.getPassedCount());
    }

    @Test
    @DisplayName("Go test output parser extracts pass/fail/skip counts")
    void testGoParser() {
        String stdout = "--- PASS: TestOne (0.00s)\n--- FAIL: TestTwo (0.01s)\n--- SKIP: TestThree (0.00s)\nFAIL\nFAIL\tmain\t0.012s";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.GO, stdout, "");

        assertEquals("PARSED", evidence.getParserStatus());
        assertEquals(3, evidence.getTestsRun());
        assertEquals(1, evidence.getPassedCount());
        assertEquals(1, evidence.getFailuresCount());
        assertEquals(1, evidence.getSkippedCount());
    }

    @Test
    @DisplayName("Rust cargo test output parser extracts test result summary")
    void testRustParser() {
        String stdout = "test result: FAILED. 4 passed; 2 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.05s";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.RUST, stdout, "");

        assertEquals("PARSED", evidence.getParserStatus());
        assertEquals(7, evidence.getTestsRun());
        assertEquals(4, evidence.getPassedCount());
        assertEquals(2, evidence.getFailuresCount());
        assertEquals(1, evidence.getSkippedCount());
    }

    @Test
    @DisplayName("Malformed/Garbage output falls back to UNPARSED safely")
    void testMalformedOutputFallback() {
        String stdout = "Random unparseable garbage output with no structure whatsoever";
        ParsedTestEvidence evidence = compositeService.parse(Ecosystem.PYTHON, stdout, "");

        assertEquals("UNPARSED", evidence.getParserStatus());
        assertEquals(0, evidence.getTestsRun());
    }
}
