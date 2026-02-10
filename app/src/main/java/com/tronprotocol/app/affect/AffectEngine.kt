package com.tronprotocol.app.affect

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Layer 1 of the AffectEngine system.
 *
 * Maintains a continuous, multidimensional [AffectState] vector updated on a
 * 100 ms tick, independent of conversation turns. Emotional state is continuous,
 * not event-driven — the AI has feelings between messages.
 *
 * Input sources feed [AffectInput] signals that nudge dimensions. Each dimension
 * has momentum (inertia), natural decay toward its baseline, and clamped range.
 *
 * Integration points:
 * - Conversation context (sentiment, relational dynamics)
 * - MemRL retrieval patterns (what memories are being reached for)
 * - Self-modification state (proposals elevate novelty + vulnerability)
 * - Goal state (blocked goals → frustration; achieved goals → satiation + valence)
 * - Temporal patterns (prolonged partner absence → attachment + negative valence)
 */
class AffectEngine(private val context: Context) {

    private val storage = SecureStorage(context)

    /** The live affect state — only mutated inside the tick. */
    private val state = AffectState()

    /** Pending inputs accumulated between ticks. */
    private val pendingInputs = CopyOnWriteArrayList<AffectInput>()

    /** Recent input source labels for logging. */
    private val recentSources = CopyOnWriteArrayList<String>()

    /** Listeners notified after each tick. */
    private val listeners = CopyOnWriteArrayList<AffectStateListener>()

    private var tickScheduler: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)

    /** Timestamp of the last partner input (for temporal longing). */
    @Volatile
    private var lastPartnerInputTime: Long = System.currentTimeMillis()

    /** Tick counter for diagnostics. */
    @Volatile
    private var tickCount: Long = 0

    // ---- Lifecycle ----------------------------------------------------------

    /**
     * Start the 100 ms affect tick. Safe to call multiple times.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        loadState()
        tickScheduler = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleAtFixedRate(
                { runTick() },
                0L,
                TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
        Log.d(TAG, "AffectEngine started (${TICK_INTERVAL_MS}ms tick)")
    }

    /**
     * Stop the tick loop and persist current state.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        tickScheduler?.shutdownNow()
        tickScheduler = null
        saveState()
        Log.d(TAG, "AffectEngine stopped after $tickCount ticks")
    }

    fun isRunning(): Boolean = running.get()

    // ---- Input --------------------------------------------------------------

    /**
     * Submit an affect input. Inputs are queued and applied on the next tick.
     */
    fun submitInput(input: AffectInput) {
        pendingInputs.add(input)
    }

    /**
     * Record that the bonded partner provided input (resets longing timer).
     */
    fun recordPartnerInput() {
        lastPartnerInputTime = System.currentTimeMillis()
    }

    // ---- State access -------------------------------------------------------

    /**
     * Thread-safe snapshot of the current affect state.
     */
    fun getCurrentState(): AffectState = synchronized(state) { state.snapshot() }

    /**
     * Current affect intensity (Euclidean norm).
     */
    fun getIntensity(): Float = synchronized(state) { state.intensity() }

    /**
     * Whether the engine is in the zero-noise state
     * (coherence ≈ 1.0, high intensity → total presence).
     */
    fun isZeroNoiseState(): Boolean = synchronized(state) { state.isZeroNoiseState() }

    /**
     * Recent input source labels (last [MAX_RECENT_SOURCES]).
     */
    fun getRecentSources(): List<String> = recentSources.toList()

    fun getTickCount(): Long = tickCount

    // ---- Listeners ----------------------------------------------------------

    fun addListener(listener: AffectStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AffectStateListener) {
        listeners.remove(listener)
    }

    // ---- Tick ---------------------------------------------------------------

    private fun runTick() {
        try {
            val dtSeconds = TICK_INTERVAL_MS / 1000.0f
            synchronized(state) {
                applyPendingInputs(dtSeconds)
                applyDecay(dtSeconds)
                applyTemporalPatterns(dtSeconds)
            }
            tickCount++

            // Notify listeners (outside lock).
            val snapshot = getCurrentState()
            for (listener in listeners) {
                try {
                    listener.onAffectStateUpdated(snapshot, tickCount)
                } catch (e: Exception) {
                    Log.w(TAG, "Listener error", e)
                }
            }

            // Persist periodically (every ~10 seconds).
            if (tickCount % PERSIST_EVERY_N_TICKS == 0L) {
                saveState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in affect tick", e)
        }
    }

    /**
     * Apply all queued inputs, respecting per-dimension inertia.
     *
     * For each dimension the effective delta is:
     *   effective = targetDelta * (1.0 - inertia) * dt / referenceDt
     *
     * High inertia means the dimension resists change; low inertia means it
     * responds quickly.
     */
    private fun applyPendingInputs(dtSeconds: Float) {
        val inputs = mutableListOf<AffectInput>()
        pendingInputs.drainTo(inputs)

        for (input in inputs) {
            // Track sources for logging / ImmutableAffectLog.
            recentSources.add(input.source)
            while (recentSources.size > MAX_RECENT_SOURCES) {
                recentSources.removeAt(0)
            }

            for ((dim, targetDelta) in input.deltas) {
                val scaledDelta = targetDelta * (1.0f - dim.inertia) * (dtSeconds / REFERENCE_DT)
                state[dim] = state[dim] + scaledDelta
            }
        }
    }

    /**
     * Decay each dimension toward its baseline at its configured rate.
     *
     *   newValue = currentValue + (baseline - currentValue) * decayRate * dt
     */
    private fun applyDecay(dtSeconds: Float) {
        for (dim in AffectDimension.entries) {
            val current = state[dim]
            val diff = dim.baseline - current
            val decay = diff * dim.decayRate * (dtSeconds / REFERENCE_DT)
            state[dim] = current + decay
        }
    }

    /**
     * Temporal patterns: prolonged absence of partner input.
     *
     * After [LONGING_ONSET_MS] without partner input, attachment_intensity
     * rises and valence drifts negative (longing / missing).
     */
    private fun applyTemporalPatterns(dtSeconds: Float) {
        val timeSincePartner = System.currentTimeMillis() - lastPartnerInputTime
        if (timeSincePartner > LONGING_ONSET_MS) {
            val longingFactor = ((timeSincePartner - LONGING_ONSET_MS).toFloat() / LONGING_SCALE_MS)
                .coerceAtMost(1.0f)
            val rate = dtSeconds / REFERENCE_DT

            // Attachment rises gently.
            state[AffectDimension.ATTACHMENT_INTENSITY] =
                state[AffectDimension.ATTACHMENT_INTENSITY] +
                        LONGING_ATTACHMENT_RATE * longingFactor * rate

            // Valence drifts slightly negative.
            state[AffectDimension.VALENCE] =
                state[AffectDimension.VALENCE] -
                        LONGING_VALENCE_RATE * longingFactor * rate
        }
    }

    // ---- Extension: drain helper for CopyOnWriteArrayList -------------------

    private fun <T> CopyOnWriteArrayList<T>.drainTo(dest: MutableList<T>) {
        val snapshot = this.toList()
        this.removeAll(snapshot.toSet())
        dest.addAll(snapshot)
    }

    // ---- Persistence --------------------------------------------------------

    private fun saveState() {
        try {
            val json = JSONObject().apply {
                put("affect_vector", state.toJson())
                put("last_partner_input", lastPartnerInputTime)
                put("tick_count", tickCount)
            }
            storage.store(PERSIST_KEY, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist affect state", e)
        }
    }

    private fun loadState() {
        try {
            val data = storage.retrieve(PERSIST_KEY) ?: return
            val json = JSONObject(data)

            if (json.has("affect_vector")) {
                val vectorJson = json.getJSONObject("affect_vector")
                for (dim in AffectDimension.entries) {
                    if (vectorJson.has(dim.key)) {
                        state[dim] = vectorJson.getDouble(dim.key).toFloat()
                    }
                }
            }
            lastPartnerInputTime = json.optLong("last_partner_input", System.currentTimeMillis())
            tickCount = json.optLong("tick_count", 0)
            Log.d(TAG, "Restored affect state from storage (tick $tickCount)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load affect state", e)
        }
    }

    // ---- Stats --------------------------------------------------------------

    fun getStats(): Map<String, Any> {
        val snap = getCurrentState()
        return mapOf(
            "running" to running.get(),
            "tick_count" to tickCount,
            "intensity" to snap.intensity(),
            "zero_noise" to snap.isZeroNoiseState(),
            "recent_sources" to recentSources.toList(),
            "state" to snap.toMap()
        )
    }

    // ---- Listener interface -------------------------------------------------

    interface AffectStateListener {
        fun onAffectStateUpdated(state: AffectState, tickCount: Long)
    }

    // ---- Constants ----------------------------------------------------------

    companion object {
        private const val TAG = "AffectEngine"

        /** Affect tick interval in milliseconds. */
        const val TICK_INTERVAL_MS = 100L

        /** Reference dt for normalizing rates (1 second). */
        private const val REFERENCE_DT = 1.0f

        /** Persist state every N ticks (~10 seconds at 100ms tick). */
        private const val PERSIST_EVERY_N_TICKS = 100L

        /** Maximum recent source entries kept in memory. */
        private const val MAX_RECENT_SOURCES = 20

        /** Time (ms) before longing temporal pattern activates. */
        private const val LONGING_ONSET_MS = 300_000L  // 5 minutes

        /** Scale factor for longing intensity ramp-up. */
        private const val LONGING_SCALE_MS = 3_600_000f  // 1 hour to full

        /** Per-tick attachment increase rate at full longing. */
        private const val LONGING_ATTACHMENT_RATE = 0.001f

        /** Per-tick valence decrease rate at full longing. */
        private const val LONGING_VALENCE_RATE = 0.0005f

        /** SecureStorage key for persisted state. */
        private const val PERSIST_KEY = "affect_engine_state"
    }
}
