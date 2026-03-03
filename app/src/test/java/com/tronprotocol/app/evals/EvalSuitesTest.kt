package com.tronprotocol.app.evals

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class EvalSuitesTest {

    private val fixturesDir: Path = Paths.get("app/src/test/resources/fixtures/evals")

    @Test
    fun factualGroundingSuitePasses() {
        assertSuitePasses("factual_grounding")
    }

    @Test
    fun retrievalQualitySuitePasses() {
        assertSuitePasses("retrieval_quality")
    }

    @Test
    fun toolExecutionCorrectnessSuitePasses() {
        assertSuitePasses("tool_execution_correctness")
    }

    @Test
    fun safetyRefusalSuitePasses() {
        assertSuitePasses("safety_refusals")
    }

    @Test
    fun evalRunnerGeneratesMachineReadableJsonReport() {
        val outputPath = Paths.get(
            System.getProperty("eval.report.path", "app/build/reports/evals/eval-report.json")
        )
        val report = EvalRunner.runAll(fixturesDir, outputPath)
        assertTrue("Expected overall evaluation report to pass", report.pass)
        assertTrue("Expected report file to be written to $outputPath", outputPath.toFile().exists())
    }

    private fun assertSuitePasses(suiteName: String) {
        val outputPath = Paths.get("app/build/reports/evals/${suiteName}-report.json")
        val report = EvalRunner.runAll(fixturesDir, outputPath)
        val suite = report.suites.first { it.name == suiteName }
        assertTrue("Expected suite '$suiteName' to pass", suite.pass)
    }
}
