package com.tronprotocol.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedTelemetryTest {

    @Before
    fun reset() {
        SharedTelemetry.resetForTests()
    }

    @Test
    fun exportDashboard_containsSharedSchemaFields() {
        SharedTelemetry.record(
            TelemetryEvent(
                operationId = "op-1",
                requestId = "req-1",
                subsystem = "inference_router",
                latencyMs = 42,
                status = SharedTelemetry.STATUS_SUCCESS,
                errorClass = null
            )
        )

        val dashboard = SharedTelemetry.exportDashboardJson()
        val events = dashboard.getJSONArray("recent_events")
        val first = events.getJSONObject(0)

        assertEquals("op-1", first.getString("operation_id"))
        assertEquals("req-1", first.getString("request_id"))
        assertEquals("inference_router", first.getString("subsystem"))
        assertEquals(42, first.getLong("latency_ms"))
        assertEquals("success", first.getString("status"))
        assertTrue(first.has("error_class"))
    }

    @Test
    fun fatalSnapshots_keepOnlyLastNEntries() {
        repeat(30) { idx ->
            SharedTelemetry.recordFailureSnapshot(
                FatalPathSnapshot(
                    operationId = "op-$idx",
                    requestId = "req-$idx",
                    subsystem = "plugin_execution_manager",
                    errorClass = "IllegalStateException",
                    latencyMs = idx.toLong(),
                    message = "failure-$idx"
                )
            )
        }

        val dashboard = SharedTelemetry.exportDashboardJson()
        val snapshots = dashboard.getJSONArray("fatal_path_snapshots")

        assertEquals(25, snapshots.length())
        assertEquals("op-5", snapshots.getJSONObject(0).getString("operation_id"))
        assertEquals("op-29", snapshots.getJSONObject(24).getString("operation_id"))
    }
}
