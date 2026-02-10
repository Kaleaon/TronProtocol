package com.tronprotocol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tronprotocol.app.rag.MemoryConsolidationManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import com.tronprotocol.app.security.SecureStorage
import com.tronprotocol.app.selfmod.CodeModificationManager
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TronProtocolService : Service() {

    // -- Wake lock & threads -------------------------------------------------

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatThread: Thread? = null
    private var consolidationThread: Thread? = null

    @Volatile
    private var isRunning = false

    // -- Heavy dependencies (initialised asynchronously) ---------------------

    private var secureStorage: SecureStorage? = null
    private var ragStore: RAGStore? = null
    private var codeModManager: CodeModificationManager? = null
    private var consolidationManager: MemoryConsolidationManager? = null

    // -- Atomic flags --------------------------------------------------------

    private val dependenciesReady = AtomicBoolean(false)
    private val initializationInProgress = AtomicBoolean(false)
    private val heartbeatStarted = AtomicBoolean(false)
    private val consolidationStarted = AtomicBoolean(false)

    // -- Executors -----------------------------------------------------------

    private lateinit var initExecutor: ExecutorService
    private lateinit var initRetryScheduler: ScheduledExecutorService

    // -- Counters & metrics --------------------------------------------------

    private var initAttempt = 0
    private var heartbeatCount = 0
    private var totalProcessingTime = 0L
    private var lastConsolidation = 0L

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.recordMilestone(this, "service_oncreate_invoked")
        createNotificationChannel()
        acquireWakeLock()
        initExecutor = Executors.newSingleThreadExecutor()
        initRetryScheduler = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preflight = runStartupPreflight()

        // If we cannot even post the foreground notification, bail out.
        if (!preflight.canStartForeground) {
            publishStartupDiagnostic(preflight.state, preflight.reason, warn = true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground as early as possible.
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            StartupDiagnostics.recordMilestone(
                this, "service_foregrounded", "Service moved to foreground"
            )
        } catch (t: Exception) {
            StartupDiagnostics.recordError(this, "service_start_foreground_failed", t)
            publishStartupDiagnostic(
                STATE_DEFERRED,
                "Foreground notification failed: ${t.javaClass.simpleName}",
                warn = true
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground is live but loops may be gated (e.g. degraded mode).
        if (!preflight.canStartLoops) {
            publishStartupDiagnostic(preflight.state, preflight.reason, warn = true)
            return START_STICKY
        }

        if (preflight.state == STATE_DEGRADED) {
            publishStartupDiagnostic(preflight.state, preflight.reason, warn = false)
        } else {
            publishStartupDiagnostic(
                STATE_RUNNING, "Foreground service and loops active", warn = false
            )
        }

        // Kick off heavy init on a background thread, then start loops.
        initializeDependenciesAsync()
        startHeartbeat()
        startConsolidationLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        heartbeatThread?.interrupt()
        consolidationThread?.interrupt()

        if (::initExecutor.isInitialized) initExecutor.shutdownNow()
        if (::initRetryScheduler.isInitialized) initRetryScheduler.shutdownNow()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================================================================
    // Dependency initialisation
    // ========================================================================

    private fun initializeDependenciesAsync() {
        if (dependenciesReady.get() || !initializationInProgress.compareAndSet(false, true)) {
            return
        }

        initExecutor.execute {
            try {
                initializeDependencies()
                dependenciesReady.set(true)
                initAttempt = 0
                Log.d(TAG, "All heavy dependencies initialized")
                ensureLoopsStartedIfReady()
            } catch (e: Exception) {
                dependenciesReady.set(false)
                scheduleInitializationRetry(e)
            } finally {
                initializationInProgress.set(false)
            }
        }
    }

    private fun initializeDependencies() {
        // --- SecureStorage --------------------------------------------------
        try {
            if (secureStorage == null) {
                secureStorage = SecureStorage(this)
            }
            StartupDiagnostics.recordMilestone(this, "secure_storage_initialized")
            Log.d(TAG, "Secure storage initialized")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "secure_storage_init_failed", e)
            Log.e(TAG, "Failed to initialize secure storage", e)
        }

        // --- RAGStore -------------------------------------------------------
        try {
            if (ragStore == null) {
                ragStore = RAGStore(this, AI_ID).also { store ->
                    store.addKnowledge(
                        "TronProtocol monitors cellular device access and AI heartbeat",
                        "system"
                    )
                    store.addKnowledge(
                        "Background service runs continuously with battery optimization override",
                        "system"
                    )
                }
            }
            StartupDiagnostics.recordMilestone(this, "rag_store_initialized")
            Log.d(TAG, "RAG store initialized with MemRL")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "rag_store_init_failed", e)
            Log.e(TAG, "Failed to initialize RAG store", e)
        }

        // --- CodeModificationManager ----------------------------------------
        try {
            if (codeModManager == null) {
                codeModManager = CodeModificationManager(this)
            }
            StartupDiagnostics.recordMilestone(this, "code_mod_manager_initialized")
            Log.d(TAG, "Code modification manager initialized")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "code_mod_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize code modification manager", e)
        }

        // --- MemoryConsolidationManager -------------------------------------
        try {
            if (consolidationManager == null) {
                consolidationManager = MemoryConsolidationManager(this)
            }
            StartupDiagnostics.recordMilestone(this, "consolidation_manager_initialized")
            Log.d(TAG, "Memory consolidation manager initialized")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "consolidation_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize consolidation manager", e)
        }
    }

    private fun scheduleInitializationRetry(cause: Exception) {
        initAttempt++
        val delaySeconds = (INIT_BASE_RETRY_SECONDS * (1 shl (initAttempt - 1).coerceAtMost(10)))
            .coerceAtMost(INIT_MAX_RETRY_SECONDS)
        Log.e(
            TAG,
            "Dependency initialization failed; service continuing in degraded mode. " +
                "Retrying in ${delaySeconds}s (attempt $initAttempt)",
            cause
        )
        initRetryScheduler.schedule(
            { initializeDependenciesAsync() },
            delaySeconds.toLong(),
            TimeUnit.SECONDS
        )
    }

    private fun ensureLoopsStartedIfReady() {
        if (!dependenciesReady.get()) {
            Log.d(TAG, "Dependencies not ready yet; loops will start later")
            return
        }
        startHeartbeat()
        startConsolidationLoop()
    }

    // ========================================================================
    // Preflight & diagnostics
    // ========================================================================

    private fun runStartupPreflight(): StartupPreflightResult {
        // Android 13+ requires POST_NOTIFICATIONS at runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return StartupPreflightResult.blocked(
                STATE_BLOCKED_BY_PERMISSION,
                "POST_NOTIFICATIONS missing. Open app and grant notification permission."
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
                ?: return StartupPreflightResult.blocked(
                    STATE_DEFERRED,
                    "NotificationManager unavailable. Retry service start once system services stabilize."
                )

            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                return StartupPreflightResult.blocked(
                    STATE_DEFERRED,
                    "Notification channel missing. Reopen app to recreate foreground channel."
                )
            }

            if (!manager.areNotificationsEnabled()) {
                return StartupPreflightResult.degraded(
                    "Notifications disabled at app level. " +
                        "Service loops are active but user alerts are suppressed."
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager != null && !manager.areNotificationsEnabled()) {
                return StartupPreflightResult.degraded(
                    "Notifications disabled at app level. " +
                        "Service loops are active but user alerts are suppressed."
                )
            }
        }

        return StartupPreflightResult.running()
    }

    private fun publishStartupDiagnostic(state: String, reason: String, warn: Boolean) {
        getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(SERVICE_STARTUP_STATE_KEY, state)
            .putString(SERVICE_STARTUP_REASON_KEY, reason)
            .apply()

        val message = "Startup state=$state reason=$reason"
        if (warn) Log.w(TAG, message) else Log.i(TAG, message)
    }

    // ========================================================================
    // Notification helpers
    // ========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tron Protocol Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(channel)
            } else {
                Log.w(TAG, "Unable to create notification channel: manager unavailable")
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tron Protocol")
            .setContentText("AI heartbeat and cellular monitoring active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ========================================================================
    // Wake lock
    // ========================================================================

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.let { pm ->
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TronProtocol::HeartbeatWakeLock"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    // ========================================================================
    // Heartbeat loop
    // ========================================================================

    private fun startHeartbeat() {
        if (!dependenciesReady.get()) {
            Log.d(TAG, "Heartbeat loop waiting for dependency readiness")
            return
        }
        if (!heartbeatStarted.compareAndSet(false, true)) return

        isRunning = true
        heartbeatThread = Thread {
            while (isRunning) {
                try {
                    performHeartbeat()
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }.also { it.start() }
    }

    private fun performHeartbeat() {
        val startTime = System.currentTimeMillis()
        heartbeatCount++

        try {
            // 1. Store heartbeat event as memory
            ragStore?.addMemory("Heartbeat #$heartbeatCount at ${Date()}", 0.5f)

            // 2. Retrieve relevant context using MemRL every 10th heartbeat
            if (heartbeatCount % 10 == 0) {
                ragStore?.let { store ->
                    val results = store.retrieve(
                        "system status and heartbeat history",
                        RetrievalStrategy.MEMRL,
                        5
                    )
                    Log.d(TAG, "Retrieved ${results.size} relevant memories using MemRL")

                    if (results.isNotEmpty()) {
                        val chunkIds = results.map { it.chunk.chunkId }
                        store.provideFeedback(chunkIds, true)
                    }
                }
            }

            // 3. Self-reflection and improvement every 50 heartbeats
            if (heartbeatCount % 50 == 0) {
                codeModManager?.let { manager ->
                    val metrics = mapOf<String, Any>(
                        "heartbeat_count" to heartbeatCount,
                        "avg_processing_time" to
                            if (heartbeatCount > 0) totalProcessingTime / heartbeatCount else 0L,
                        "error_rate" to 0.0
                    )
                    val reflection = manager.reflect(metrics)
                    if (reflection.hasInsights()) {
                        Log.d(TAG, "Self-reflection insights: ${reflection.getInsights()}")
                    }
                }
            }

            // 4. Store secure heartbeat timestamp
            secureStorage?.let { storage ->
                val timestamp = System.currentTimeMillis()
                storage.store("last_heartbeat", timestamp.toString())
                storage.store("heartbeat_count", heartbeatCount.toString())
            }

            // 5. Log MemRL and consolidation statistics every 100 heartbeats
            if (heartbeatCount % 100 == 0) {
                ragStore?.let { store ->
                    Log.d(TAG, "MemRL Stats: ${store.getMemRLStats()}")

                    consolidationManager?.let { manager ->
                        Log.d(TAG, "Consolidation Stats: ${manager.getStats()}")
                    }
                }
            }

            val processingTime = System.currentTimeMillis() - startTime
            totalProcessingTime += processingTime
            Log.d(TAG, "Heartbeat #$heartbeatCount complete (processing time: ${processingTime}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in heartbeat processing", e)
        }
    }

    // ========================================================================
    // Consolidation loop
    // ========================================================================

    /**
     * Starts the memory consolidation loop.
     * Runs during idle / rest periods to optimise memories (similar to sleep).
     */
    private fun startConsolidationLoop() {
        if (!dependenciesReady.get()) {
            Log.d(TAG, "Consolidation loop waiting for dependency readiness")
            return
        }
        if (!consolidationStarted.compareAndSet(false, true)) return

        consolidationThread = Thread {
            while (isRunning) {
                try {
                    Thread.sleep(CONSOLIDATION_CHECK_INTERVAL.toLong())

                    val manager = consolidationManager
                    if (manager != null && manager.isConsolidationTime()) {
                        Log.d(TAG, "Starting memory consolidation (rest period)...")

                        ragStore?.let { store ->
                            val result = manager.consolidate(store)
                            Log.d(TAG, "Consolidation result: $result")

                            if (result.success) {
                                store.addMemory(
                                    "Memory consolidation completed: $result",
                                    0.8f
                                )
                                lastConsolidation = System.currentTimeMillis()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in consolidation loop", e)
                }
            }
        }.also { it.start() }

        Log.d(TAG, "Memory consolidation loop started")
    }

    // ========================================================================
    // Inner class – startup preflight result
    // ========================================================================

    private data class StartupPreflightResult(
        val canStartForeground: Boolean,
        val canStartLoops: Boolean,
        val state: String,
        val reason: String
    ) {
        companion object {
            fun blocked(state: String, reason: String) = StartupPreflightResult(
                canStartForeground = false,
                canStartLoops = false,
                state = state,
                reason = reason
            )

            fun degraded(reason: String) = StartupPreflightResult(
                canStartForeground = true,
                canStartLoops = true,
                state = STATE_DEGRADED,
                reason = reason
            )

            fun running() = StartupPreflightResult(
                canStartForeground = true,
                canStartLoops = true,
                state = STATE_RUNNING,
                reason = "All notification prerequisites satisfied."
            )
        }
    }

    // ========================================================================
    // Constants & public state keys
    // ========================================================================

    companion object {
        private val TAG: String = TronProtocolService::class.java.simpleName

        private const val CHANNEL_ID = "TronProtocolServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val AI_ID = "tronprotocol_ai"
        private const val CONSOLIDATION_CHECK_INTERVAL = 3_600_000   // 1 hour in ms
        private const val INIT_BASE_RETRY_SECONDS = 5
        private const val INIT_MAX_RETRY_SECONDS = 300
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L     // 10 minutes

        const val SERVICE_STARTUP_STATE_KEY = "service_startup_state"
        const val SERVICE_STARTUP_REASON_KEY = "service_startup_reason"
        const val STATE_RUNNING = "running"
        const val STATE_DEFERRED = "deferred"
        const val STATE_BLOCKED_BY_PERMISSION = "blocked-by-permission"
        const val STATE_DEGRADED = "degraded"
    }
}
