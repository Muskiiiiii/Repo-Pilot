package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.parser.*;
import com.repopilot.testing.service.FailureClassifierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Phase10_3FailureClassificationTest {

    private FailureClassifierServiceImpl classifierService;
    private TestResultParserCompositeService parserCompositeService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        parserCompositeService = new TestResultParserCompositeService(List.of(
                new PythonTestResultParser(),
                new NodeTestResultParser(),
                new MavenTestResultParser(),
                new GradleTestResultParser(),
                new GoTestResultParser(),
                new RustTestResultParser()
        ));
        objectMapper = new ObjectMapper();
        classifierService = new FailureClassifierServiceImpl(parserCompositeService, objectMapper);
    }

    @Test
    @DisplayName("1. Classify PASS cleanly when exit code is 0 and no test failures")
    void testClassify_Pass() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                0, "Ran 5 tests in 0.01s\nOK\n", "", 100L, "c1", FailureType.PASS, false, false, "pytest"
        );
        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.PASS, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
        assertTrue(classification.isRelatedToCodeChanges());
    }

    @Test
    @DisplayName("2. Classify TEST_FAILURE when parsed test failure count > 0")
    void testClassify_TestFailure() {
        String stdout = "Ran 1 test in 0.01s\n" +
                        "FAILED (failures=1)\n" +
                        "AssertionError: assert 2 == 3 in test_calc.py\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, stdout, "", 150L, "c1", FailureType.TEST_FAILURE, false, false, "pytest"
        );

        CodeChange change = new CodeChange();
        change.setFilePath("test_calc.py");

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, List.of(change));

        assertEquals(FailureType.TEST_FAILURE, classification.getFailureCategory());
        assertEquals(Repairability.REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
        assertTrue(classification.isRelatedToCodeChanges());
    }

    @Test
    @DisplayName("3. Classify COMPILATION_FAILURE on syntax/compilation error")
    void testClassify_CompilationFailure() {
        String stderr = "File \"main.py\", line 3\n    def foo(\n           ^\nSyntaxError: unexpected EOF while parsing\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.COMPILATION_FAILURE, false, false, "python main.py"
        );

        CodeChange change = new CodeChange();
        change.setFilePath("main.py");

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, List.of(change));

        assertEquals(FailureType.COMPILATION_FAILURE, classification.getFailureCategory());
        assertEquals(Repairability.REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
        assertTrue(classification.isRelatedToCodeChanges());
    }

    @Test
    @DisplayName("4. Classify CODE_FAILURE on uncaught runtime exception")
    void testClassify_CodeFailure() {
        String stderr = "Traceback (most recent call last):\n" +
                        "  File \"app.py\", line 10, in <module>\n" +
                        "    res = 1 / 0\n" +
                        "ZeroDivisionError: division by zero\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 120L, "c1", FailureType.CODE_FAILURE, false, false, "python app.py"
        );

        CodeChange change = new CodeChange();
        change.setFilePath("app.py");

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, List.of(change));

        assertEquals(FailureType.CODE_FAILURE, classification.getFailureCategory());
        assertEquals(Repairability.REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.MEDIUM, classification.getConfidence());
        assertTrue(classification.isRelatedToCodeChanges());
    }

    @Test
    @DisplayName("5. Classify DEPENDENCY_UNAVAILABLE on missing package")
    void testClassify_DependencyUnavailable() {
        String stderr = "ModuleNotFoundError: No module named 'non_existent_pkg'\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.DEPENDENCY_UNAVAILABLE, false, false, "python main.py"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.DEPENDENCY_UNAVAILABLE, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("6. Classify NETWORK_REQUIRED on network failure")
    void testClassify_NetworkRequired() {
        String stderr = "urllib.error.URLError: <urlopen error [Errno -3] Could not resolve host: api.github.com>\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.NETWORK_REQUIRED, false, false, "python fetch.py"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.NETWORK_REQUIRED, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("7. Classify TIMEOUT when execution times out")
    void testClassify_Timeout() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                124, "", "Execution timed out after 30 seconds", 30000L, "c1", FailureType.TIMEOUT, false, false, "pytest", TerminationReason.TIMEOUT, 0L, 100L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.TIMEOUT, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("8. Classify OOM on container OOM kill")
    void testClassify_Oom() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                137, "", "java.lang.OutOfMemoryError: Java heap space", 5000L, "c1", FailureType.OOM, false, false, "mvn test", TerminationReason.OOM, 0L, 100L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.MAVEN, execResult, Collections.emptyList());

        assertEquals(FailureType.OOM, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("9. Classify RESOURCE_LIMIT on PID/pids limit reached")
    void testClassify_ResourceLimit() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", "pids limit reached; cannot fork", 2000L, "c1", FailureType.RESOURCE_LIMIT, false, false, "pytest", TerminationReason.RESOURCE_LIMIT, 0L, 50L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.RESOURCE_LIMIT, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("10. Classify EXECUTION_INFRA_FAILURE on container creation failure")
    void testClassify_InfraFailure() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", "docker daemon unavailable", 500L, "c1", FailureType.EXECUTION_INFRA_FAILURE, false, false, "pytest", TerminationReason.EXECUTION_INFRA_FAILURE, 0L, 50L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.EXECUTION_INFRA_FAILURE, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("11. Classify CANCELLED when execution is cancelled")
    void testClassify_Cancelled() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                130, "", "Interrupted", 100L, "c1", FailureType.CANCELLED, false, false, "pytest", TerminationReason.CANCELLED, 0L, 20L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.CANCELLED, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
        assertEquals(ConfidenceLevel.HIGH, classification.getConfidence());
    }

    @Test
    @DisplayName("12. Classify UNKNOWN / AMBIGUOUS on unrecognized non-zero output")
    void testClassify_Unknown() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                2, "some random text", "something happened", 100L, "c1", FailureType.UNKNOWN, false, false, "custom_tool"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.UNKNOWN, classification.getFailureCategory());
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability());
        assertEquals(ConfidenceLevel.LOW, classification.getConfidence());
    }

    @Test
    @DisplayName("13. Classify Ambiguous on null execution result")
    void testClassify_Ambiguous() {
        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, null, Collections.emptyList());

        assertEquals(FailureType.UNKNOWN, classification.getFailureCategory());
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability());
        assertEquals(ConfidenceLevel.LOW, classification.getConfidence());
    }

    @Test
    @DisplayName("14. Exit code 1 alone NEVER infers CODE_FAILURE or TEST_FAILURE")
    void testClassify_ExitCodeAlone() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", "", 100L, "c1", FailureType.UNKNOWN, false, false, "custom"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.UNKNOWN, classification.getFailureCategory());
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability());
        assertEquals(ConfidenceLevel.LOW, classification.getConfidence());
    }

    @Test
    @DisplayName("15. Generic error text without pattern does NOT trigger false category")
    void testClassify_GenericError() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "processing data...", "error logged at info level", 100L, "c1", FailureType.UNKNOWN, false, false, "custom"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.UNKNOWN, classification.getFailureCategory());
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability());
        assertEquals(ConfidenceLevel.LOW, classification.getConfidence());
    }

    @Test
    @DisplayName("16. Parser failure fallback gracefully handles unparsed output")
    void testClassify_ParserFailureFallback() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "unparseable stdout", "unparseable stderr", 100L, "c1", FailureType.UNKNOWN, false, false, "custom"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertNotNull(classification);
        assertEquals(FailureType.UNKNOWN, classification.getFailureCategory());
    }

    @Test
    @DisplayName("17. Misleading failure word in comments does not override PASS when exit code is 0")
    void testClassify_MisleadingFailureWord() {
        String stdout = "# Note: Fixed syntaxerror in previous iteration\nRan 1 test in 0.01s\nOK\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                0, stdout, "", 100L, "c1", FailureType.PASS, false, false, "pytest"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.PASS, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
    }

    @Test
    @DisplayName("18. Missing dependency takes precedence over compilation failure")
    void testClassify_DependencyPlusBuild() {
        String stderr = "[ERROR] Cannot find symbol Foo\nModuleNotFoundError: No module named 'dep_xyz'\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.DEPENDENCY_UNAVAILABLE, false, false, "pytest"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.DEPENDENCY_UNAVAILABLE, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
    }

    @Test
    @DisplayName("19. OOM signal takes precedence over assertion error text")
    void testClassify_OomPlusAssertion() {
        String stdout = "E AssertionError: failed assertion\njava.lang.OutOfMemoryError: Java heap space";
        DockerExecutionResult execResult = new DockerExecutionResult(
                137, stdout, "", 1000L, "c1", FailureType.OOM, false, false, "mvn test", TerminationReason.OOM, 0L, 100L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.MAVEN, execResult, Collections.emptyList());

        assertEquals(FailureType.OOM, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
    }

    @Test
    @DisplayName("20. TIMEOUT signal takes precedence over test failure text")
    void testClassify_TimeoutPlusTestFailureText() {
        String stdout = "E AssertionError: failed assertion\nExecution timed out after 30 seconds";
        DockerExecutionResult execResult = new DockerExecutionResult(
                124, stdout, "", 30000L, "c1", FailureType.TIMEOUT, false, false, "pytest", TerminationReason.TIMEOUT, 0L, 100L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertEquals(FailureType.TIMEOUT, classification.getFailureCategory());
        assertEquals(Repairability.NOT_REPAIRABLE, classification.getRepairability());
    }

    @Test
    @DisplayName("21. CodeChange relatedness is TRUE when modified file is in failure stack trace")
    void testClassify_CodeChangeRelatedness_Related() {
        String stderr = "Traceback (most recent call last):\n" +
                        "  File \"service.py\", line 12, in process\n" +
                        "ZeroDivisionError: division by zero\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.CODE_FAILURE, false, false, "python service.py"
        );

        CodeChange change = new CodeChange();
        change.setFilePath("src/service.py");

        boolean related = classifierService.isFailureRelatedToCodeChanges("", stderr, List.of(change));
        assertTrue(related, "Failure mentioning service.py must be marked related to src/service.py CodeChange");
    }

    @Test
    @DisplayName("22. CodeChange relatedness yields AMBIGUOUS repairability when failure is unrelated")
    void testClassify_CodeChangeRelatedness_Unrelated() {
        String stderr = "Traceback (most recent call last):\n" +
                        "  File \"other.py\", line 12, in process\n" +
                        "ZeroDivisionError: division by zero\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.CODE_FAILURE, false, false, "python main.py"
        );

        CodeChange change = new CodeChange();
        change.setFilePath("src/service.py");

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, List.of(change));

        assertFalse(classification.isRelatedToCodeChanges());
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability());
    }

    @Test
    @DisplayName("23. Determinism test: classification is 100% reproducible over 100 runs")
    void testClassify_Determinism() {
        String stderr = "ModuleNotFoundError: No module named 'foo'\n";
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", stderr, 100L, "c1", FailureType.DEPENDENCY_UNAVAILABLE, false, false, "python main.py"
        );

        FailureClassification first = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        for (int i = 0; i < 100; i++) {
            FailureClassification repeat = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());
            assertEquals(first.getFailureCategory(), repeat.getFailureCategory());
            assertEquals(first.getRepairability(), repeat.getRepairability());
            assertEquals(first.getConfidence(), repeat.getConfidence());
            assertEquals(first.getClassifierVersion(), repeat.getClassifierVersion());
        }
    }

    @Test
    @DisplayName("24. Evidence preservation: classification evidence contains details map")
    void testClassify_EvidencePreservation() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                124, "", "timeout", 30000L, "c1", FailureType.TIMEOUT, false, false, "pytest", TerminationReason.TIMEOUT, 0L, 50L
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertNotNull(classification.getEvidence());
        assertTrue(classification.getEvidence().containsKey("terminationReason"));
    }

    @Test
    @DisplayName("25. Secret sanitization redacts sensitive keys, LLM tokens, DB URLs, and GitHub PATs")
    void testClassify_SecretSanitization() {
        String rawStderr = "Error connecting with AWS Key: AKIAIOSFODNN7EXAMPLE, GitHub: ghp_1234567890abcdefghijklmnopqrstuvwxyz, OpenAI: sk-proj-1234567890abcdefghijklmnopqrstuvwxyz, Voyage: vy-1234567890abcdefghijklmnopqrstuvwxyz, DB: postgres://user:secretpass@localhost:5432/mydb";
        String sanitized = classifierService.sanitize(rawStderr);

        assertFalse(sanitized.contains("AKIAIOSFODNN7EXAMPLE"), "AWS key must be redacted");
        assertFalse(sanitized.contains("ghp_1234567890abcdefghijklmnopqrstuvwxyz"), "GitHub token must be redacted");
        assertFalse(sanitized.contains("sk-proj-1234567890abcdefghijklmnopqrstuvwxyz"), "LLM key must be redacted");
        assertFalse(sanitized.contains("vy-1234567890abcdefghijklmnopqrstuvwxyz"), "Voyage key must be redacted");
        assertFalse(sanitized.contains("postgres://user:secretpass"), "DB connection string must be redacted");
        assertTrue(sanitized.contains("[REDACTED_AWS_KEY]"));
        assertTrue(sanitized.contains("[REDACTED_GITHUB_TOKEN]"));
        assertTrue(sanitized.contains("[REDACTED_LLM_KEY]"));
        assertTrue(sanitized.contains("[REDACTED_VOYAGE_KEY]"));
        assertTrue(sanitized.contains("postgres://[REDACTED_DB_CREDENTIALS]"));
    }

    @Test
    @DisplayName("26. CodeChange Relatedness Scenario A: Direct changed source file in stack trace")
    void testRelatedness_ScenarioA_DirectSourceFile() {
        CodeChange change = new CodeChange();
        change.setFilePath("com/repopilot/service/UserService.java");
        String stderr = "Exception in thread main java.lang.NullPointerException at com.repopilot.service.UserService.process(UserService.java:42)";

        boolean related = classifierService.isFailureRelatedToCodeChanges("", stderr, List.of(change));
        assertTrue(related, "Direct modified file in trace must evaluate to related=true");
    }

    @Test
    @DisplayName("27. CodeChange Relatedness Scenario B: Changed method called indirectly via test runner")
    void testRelatedness_ScenarioB_IndirectCall() {
        CodeChange change = new CodeChange();
        change.setFilePath("src/calculator.py");
        String stderr = "Traceback (most recent call last):\n  File \"test_runner.py\", line 10, in run\n    test_calc()\n  File \"test_suite.py\", line 25, in test_calc\n    Calculator.add(1, 2)\n  File \"calculator.py\", line 5, in add\n    return 1 / 0";

        boolean related = classifierService.isFailureRelatedToCodeChanges("", stderr, List.of(change));
        assertTrue(related, "Indirect call tracing through calculator.py must evaluate to related=true");
    }

    @Test
    @DisplayName("28. CodeChange Relatedness Scenario C: Framework stack trace with changed source earlier in trace")
    void testRelatedness_ScenarioC_FrameworkTraceWithChangedSource() {
        CodeChange change = new CodeChange();
        change.setFilePath("com/repopilot/controller/OrderController.java");
        String stderr = "org.springframework.web.util.NestedServletException: Request processing failed\n" +
                        "  at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014)\n" +
                        "  at com.repopilot.controller.OrderController.submitOrder(OrderController.java:88)\n" +
                        "  at java.base/java.lang.Thread.run(Thread.java:833)";

        boolean related = classifierService.isFailureRelatedToCodeChanges("", stderr, List.of(change));
        assertTrue(related, "Framework trace containing OrderController.java must evaluate to related=true");
    }

    @Test
    @DisplayName("29. CodeChange Relatedness Scenario D: Failure only in unrelated file yields AMBIGUOUS repairability")
    void testRelatedness_ScenarioD_UnrelatedFile() {
        CodeChange change = new CodeChange();
        change.setFilePath("src/auth.py");
        String stderr = "Traceback (most recent call last):\n  File \"src/payment.py\", line 15, in pay\n    raise ValueError('invalid card')";

        DockerExecutionResult execResult = new DockerExecutionResult(1, "", stderr, 100L, "c1", FailureType.CODE_FAILURE, false, false, "python payment.py");
        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, List.of(change));

        assertFalse(classification.isRelatedToCodeChanges(), "Unrelated failure file must evaluate to related=false");
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability(), "Unrelated failure must yield AMBIGUOUS repairability");
    }

    @Test
    @DisplayName("30. CodeChange Relatedness Scenario E: No file information in failure log defaults safely")
    void testRelatedness_ScenarioE_NoFileInformation() {
        CodeChange change = new CodeChange();
        change.setFilePath("src/auth.py");
        String stderr = "Segmentation fault (core dumped)";

        DockerExecutionResult execResult = new DockerExecutionResult(139, "", stderr, 100L, "c1", FailureType.UNKNOWN, false, false, "app");
        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, List.of(change));

        assertFalse(classification.isRelatedToCodeChanges(), "No file info should evaluate to related=false");
        assertEquals(Repairability.AMBIGUOUS, classification.getRepairability(), "No file info must yield AMBIGUOUS repairability");
    }

    @Test
    @DisplayName("31. Classifier version is non-null and structured")
    void testClassify_ClassifierVersion() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                0, "OK", "", 50L, "c1", FailureType.PASS, false, false, "pytest"
        );

        FailureClassification classification = classifierService.classify(Ecosystem.PYTHON, execResult, Collections.emptyList());

        assertNotNull(classification.getClassifierVersion());
        assertTrue(classification.getClassifierVersion().startsWith("10.3"), "Version must start with 10.3");
    }
}
