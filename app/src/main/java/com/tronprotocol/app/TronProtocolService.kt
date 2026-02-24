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
import com.tronprotocol.app.affect.AffectInput
import com.tronprotocol.app.affect.AffectOrchestrator
import com.tronprotocol.app.frontier.FrontierDynamicsManager
import com.tronprotocol.app.frontier.FrontierDynamicsPlugin
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.plugins.LaneQueueExecutor
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginSafetyScanner
import com.tronprotocol.app.plugins.SubAgentManager
import com.tronprotocol.app.plugins.ToolPolicyEngine
import com.tronprotocol.app.rag.AutoCompactionManager
import com.tronprotocol.app.rag.MemoryConsolidationManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import com.tronprotocol.app.rag.SessionKeyManager
import com.tronprotocol.app.rag.SleepCycleOptimizer
import com.tronprotocol.app.rag.SleepCycleTakensTrainer
import com.tronprotocol.app.security.AuditLogger
import com.tronprotocol.app.security.ConstitutionalMemory
import com.tronprotocol.app.security.SecureStorage
import com.tronprotocol.app.selfmod.CodeModificationManager
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    // -- OpenClaw-inspired subsystems (initialised with dependencies) --------

    private var constitutionalMemory: ConstitutionalMemory? = null
    private var auditLogger: AuditLogger? = null
    private var laneQueueExecutor: LaneQueueExecutor? = null
    private var toolPolicyEngine: ToolPolicyEngine? = null
    private var safetyScanner: PluginSafetyScanner? = null
    private var subAgentManager: SubAgentManager? = null
    private var autoCompactionManager: AutoCompactionManager? = null
    private var sleepCycleOptimizer: SleepCycleOptimizer? = null
    private var sleepCycleTakensTrainer: SleepCycleTakensTrainer? = null
    private var sessionKeyManager: SessionKeyManager? = null
    private var onDeviceLLMManager: OnDeviceLLMManager? = null
    private var affectOrchestrator: AffectOrchestrator? = null
    private var frontierDynamicsManager: FrontierDynamicsManager? = null

    // -- Atomic flags --------------------------------------------------------

    private val dependenciesReady = AtomicBoolean(false)
    private val initializationInProgress = AtomicBoolean(false)
    private val heartbeatStarted = AtomicBoolean(false)
    private val consolidationStarted = AtomicBoolean(false)

    // -- Executors -----------------------------------------------------------

    private lateinit var initExecutor: ExecutorService
    private lateinit var initRetryScheduler: ScheduledExecutorService

    // -- Counters & metrics --------------------------------------------------

    private val initAttempt = AtomicInteger(0)
    private var heartbeatCount = 0
    private var totalProcessingTime = 0L
    private var lastConsolidation = 0L
    private var consecutiveHeartbeatErrors = 0

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

        // Signal threads to stop and wait briefly for graceful completion
        heartbeatThread?.interrupt()
        consolidationThread?.interrupt()
        try {
            heartbeatThread?.join(SHUTDOWN_TIMEOUT_MS)
            consolidationThread?.join(SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (::initExecutor.isInitialized) initExecutor.shutdownNow()
        if (::initRetryScheduler.isInitialized) initRetryScheduler.shutdownNow()

        // Shut down AffectEngine, Takens trainer, and OpenClaw-inspired subsystems
        affectOrchestrator?.stop()
        sleepCycleTakensTrainer?.shutdown()
        onDeviceLLMManager?.shutdown()
        laneQueueExecutor?.shutdown()
        subAgentManager?.shutdown()
        // Flush audit logger last so subsystem shutdown events are captured
        auditLogger?.shutdown()

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing wake lock", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================================================================
    // Dependency initialisation
    // ========================================================================

    private fun initializeDependenciesAsync() {
        if (dependenciesReady.get()) return
        if (!initializationInProgress.compareAndSet(false, true)) return

        initExecutor.execute {
            try {
                initializeDependencies()
                // Atomic transition: mark ready and reset attempt counter together
                initAttempt.set(0)
                dependenciesReady.set(true)
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

        // --- AuditLogger (OpenClaw audit system) ----------------------------
        try {
            if (auditLogger == null) {
                auditLogger = AuditLogger(this)
                auditLogger?.setSessionId("service_${System.currentTimeMillis()}")
                auditLogger?.logAsync(
                    AuditLogger.Severity.INFO,
                    AuditLogger.AuditCategory.SYSTEM_LIFECYCLE,
                    "service", "initialize", outcome = "started"
                )
            }
            StartupDiagnostics.recordMilestone(this, "audit_logger_initialized")
            Log.d(TAG, "Audit logger initialized (OpenClaw audit system)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "audit_logger_init_failed", e)
            Log.e(TAG, "Failed to initialize audit logger", e)
        }

        // --- ConstitutionalMemory (OpenClaw constitutional memory) ----------
        try {
            if (constitutionalMemory == null) {
                constitutionalMemory = ConstitutionalMemory(this)
            }
            StartupDiagnostics.recordMilestone(this, "constitutional_memory_initialized")
            Log.d(TAG, "Constitutional memory initialized with ${constitutionalMemory?.getDirectives()?.size} directives")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "constitutional_memory_init_failed", e)
            Log.e(TAG, "Failed to initialize constitutional memory", e)
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

            // Attach sleep-cycle self-optimizer for automatic hyperparameter tuning
            if (sleepCycleOptimizer == null) {
                sleepCycleOptimizer = SleepCycleOptimizer(this)
                consolidationManager?.optimizer = sleepCycleOptimizer
            }

            // Attach sleep-cycle Takens trainer for on-device model weight optimization
            if (sleepCycleTakensTrainer == null) {
                sleepCycleTakensTrainer = SleepCycleTakensTrainer(this)
                consolidationManager?.takensTrainer = sleepCycleTakensTrainer
            }

            StartupDiagnostics.recordMilestone(this, "consolidation_manager_initialized")
            Log.d(TAG, "Memory consolidation manager initialized (with self-optimizer + Takens trainer)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "consolidation_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize consolidation manager", e)
        }

        // --- SessionKeyManager (OpenClaw session key architecture) ----------
        try {
            if (sessionKeyManager == null) {
                sessionKeyManager = SessionKeyManager(this).also { mgr ->
                    mgr.createHeartbeatSession(AI_ID, "session_${System.currentTimeMillis()}")
                }
            }
            StartupDiagnostics.recordMilestone(this, "session_key_manager_initialized")
            Log.d(TAG, "Session key manager initialized (OpenClaw session architecture)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "session_key_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize session key manager", e)
        }

        // --- AutoCompactionManager (OpenClaw auto-compaction) ---------------
        try {
            if (autoCompactionManager == null) {
                autoCompactionManager = AutoCompactionManager()
            }
            StartupDiagnostics.recordMilestone(this, "auto_compaction_manager_initialized")
            Log.d(TAG, "Auto-compaction manager initialized (OpenClaw auto-compaction)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "auto_compaction_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize auto-compaction manager", e)
        }

        // --- ToolPolicyEngine (OpenClaw 6-level tool policy) ----------------
        try {
            if (toolPolicyEngine == null) {
                toolPolicyEngine = ToolPolicyEngine().also { it.initialize(this) }
            }
            StartupDiagnostics.recordMilestone(this, "tool_policy_engine_initialized")
            Log.d(TAG, "Tool policy engine initialized (OpenClaw 6-level policy)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "tool_policy_engine_init_failed", e)
            Log.e(TAG, "Failed to initialize tool policy engine", e)
        }

        // --- PluginSafetyScanner (OpenClaw skill scanner) -------------------
        try {
            if (safetyScanner == null) {
                safetyScanner = PluginSafetyScanner(constitutionalMemory)
            }
            StartupDiagnostics.recordMilestone(this, "safety_scanner_initialized")
            Log.d(TAG, "Plugin safety scanner initialized (OpenClaw skill scanner)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "safety_scanner_init_failed", e)
            Log.e(TAG, "Failed to initialize plugin safety scanner", e)
        }

        // --- LaneQueueExecutor (OpenClaw lane queue system) -----------------
        try {
            if (laneQueueExecutor == null) {
                laneQueueExecutor = LaneQueueExecutor().also {
                    it.setPluginManager(PluginManager.getInstance())
                }
            }
            StartupDiagnostics.recordMilestone(this, "lane_queue_executor_initialized")
            Log.d(TAG, "Lane queue executor initialized (OpenClaw lane queue system)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "lane_queue_executor_init_failed", e)
            Log.e(TAG, "Failed to initialize lane queue executor", e)
        }

        // --- SubAgentManager (OpenClaw sub-agent system) --------------------
        try {
            if (subAgentManager == null) {
                subAgentManager = SubAgentManager(PluginManager.getInstance())
            }
            StartupDiagnostics.recordMilestone(this, "sub_agent_manager_initialized")
            Log.d(TAG, "Sub-agent manager initialized (OpenClaw sub-agent system)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "sub_agent_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize sub-agent manager", e)
        }

        // --- OnDeviceLLMManager (MNN on-device inference) --------------------
        try {
            if (onDeviceLLMManager == null) {
                onDeviceLLMManager = OnDeviceLLMManager(this).also { manager ->
                    val capability = manager.assessDevice()
                    Log.d(TAG, "On-device LLM: canRun=${capability.canRunLLM}, " +
                            "recommended=${capability.recommendedModel}, " +
                            "ram=${capability.availableRamMb}MB, " +
                            "nativeAvailable=${OnDeviceLLMManager.isNativeAvailable()}")

                    // Auto-discover and load a model if native libs are available
                    if (OnDeviceLLMManager.isNativeAvailable() && capability.canRunLLM) {
                        val models = manager.discoverModels()
                        if (models.isNotEmpty()) {
                            val config = manager.createConfigFromDirectory(models[0])
                            val loaded = manager.loadModel(config)
                            if (loaded) {
                                Log.d(TAG, "Auto-loaded on-device LLM: ${config.modelName}")
                            }
                        }
                    }
                }
            }
            StartupDiagnostics.recordMilestone(this, "on_device_llm_manager_initialized")
            Log.d(TAG, "On-device LLM manager initialized (MNN framework)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "on_device_llm_manager_init_failed", e)
            Log.e(TAG, "Failed to initialize on-device LLM manager", e)
        }

        // --- AffectOrchestrator (AffectEngine emotional architecture) ---------
        try {
            if (affectOrchestrator == null) {
                affectOrchestrator = AffectOrchestrator(this).also { it.start() }
            }
            StartupDiagnostics.recordMilestone(this, "affect_orchestrator_initialized")
            Log.d(TAG, "AffectOrchestrator initialized (3-layer emotional architecture)")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "affect_orchestrator_init_failed", e)
            Log.e(TAG, "Failed to initialize AffectOrchestrator", e)
        }

        // --- FrontierDynamicsManager (STLE uncertainty framework) -----------
        try {
            if (frontierDynamicsManager == null) {
                frontierDynamicsManager = FrontierDynamicsManager(this).also { fdm ->
                    // Wire STLE into RAG store for frontier-aware retrieval
                    ragStore?.frontierDynamicsManager = fdm

                    // Train STLE from existing RAG embeddings if available
                    ragStore?.let { store ->
                        fdm.trainFromRAGStore(store)
                    }

                    // Wire STLE into the FrontierDynamicsPlugin if registered
                    val pluginManager = PluginManager.getInstance()
                    val fdPlugin = pluginManager.getAllPlugins()
                        .filterIsInstance<FrontierDynamicsPlugin>()
                        .firstOrNull()
                    if (fdPlugin != null) {
                        fdPlugin.manager = fdm
                        fdPlugin.ragStore = ragStore
                    }
                }
            }
            StartupDiagnostics.recordMilestone(this, "frontier_dynamics_initialized")
            Log.d(TAG, "FrontierDynamicsManager initialized (STLE framework, ready=${frontierDynamicsManager?.isReady})")
        } catch (e: Exception) {
            StartupDiagnostics.recordError(this, "frontier_dynamics_init_failed", e)
            Log.e(TAG, "Failed to initialize FrontierDynamicsManager", e)
        }

        // --- Wire OpenClaw subsystems into PluginManager --------------------
        try {
            val pluginManager = PluginManager.getInstance()
            safetyScanner?.let { pluginManager.attachSafetyScanner(it) }
            toolPolicyEngine?.let { pluginManager.attachToolPolicyEngine(it) }
            auditLogger?.let { pluginManager.attachAuditLogger(it) }
            StartupDiagnostics.recordMilestone(this, "openclaw_subsystems_wired")
            Log.d(TAG, "OpenClaw subsystems wired into PluginManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wire OpenClaw subsystems", e)
        }

        // Log successful initialization
        auditLogger?.logAsync(
            AuditLogger.Severity.INFO,
            AuditLogger.AuditCategory.SYSTEM_LIFECYCLE,
            "service", "dependencies_initialized",
            outcome = "success",
            details = mapOf(
                "constitutional_directives" to (constitutionalMemory?.getDirectives()?.size ?: 0),
                "session_count" to (sessionKeyManager?.getStats()?.get("total_sessions") ?: 0)
            )
        )
    }

    private fun scheduleInitializationRetry(cause: Exception) {
        val attempt = initAttempt.incrementAndGet()
        val baseDelay = (INIT_BASE_RETRY_SECONDS * (1 shl (attempt - 1).coerceAtMost(10)))
            .coerceAtMost(INIT_MAX_RETRY_SECONDS)
        // Add random jitter (up to 25%) to avoid thundering herd
        val jitter = (baseDelay * 0.25 * Math.random()).toLong()
        val delaySeconds = baseDelay + jitter
        Log.e(
            TAG,
            "Dependency initialization failed; service continuing in degraded mode. " +
                "Retrying in ${delaySeconds}s (attempt $attempt)",
            cause
        )
        initRetryScheduler.schedule(
            { initializeDependenciesAsync() },
            delaySeconds,
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

    /** Refresh the wake lock before it expires. Called periodically from the heartbeat loop. */
    private fun refreshWakeLockIfNeeded() {
        try {
            val wl = wakeLock ?: return
            // Release the old lock (if still held) then re-acquire with a fresh timeout.
            // This prevents expiry gaps when the refresh interval is shorter than the timeout.
            if (wl.isHeld) {
                wl.release()
            }
            wl.acquire(WAKELOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock refreshed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh wake lock", e)
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
                    consecutiveHeartbeatErrors = 0
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    consecutiveHeartbeatErrors++
                    Log.e(TAG, "Heartbeat error #$consecutiveHeartbeatErrors", e)
                    // Exponential backoff on repeated errors, capped at 5 minutes
                    val backoffMs = (HEARTBEAT_INTERVAL_MS *
                        (1L shl consecutiveHeartbeatErrors.coerceAtMost(4)))
                        .coerceAtMost(300_000L)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }.also { it.start() }
    }

    private fun performHeartbeat() {
        val startTime = System.currentTimeMillis()
        heartbeatCount++

        // Refresh wake lock every 15 heartbeats (≈ 7.5 min), well before the 10 min timeout
        if (heartbeatCount % 15 == 0) {
            refreshWakeLockIfNeeded()
        }

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

            // 3. Auto-compaction check every 25 heartbeats (OpenClaw-inspired)
            if (heartbeatCount % 25 == 0) {
                try {
                    ragStore?.let { store ->
                        autoCompactionManager?.let { compactor ->
                            val usage = compactor.checkUsage(store)
                            if (usage.needsCompaction) {
                                Log.d(TAG, "Auto-compaction triggered: ${usage.utilizationPercent}% utilization")
                                val result = compactor.compact(store)
                                if (result.success) {
                                    auditLogger?.logAsync(
                                        AuditLogger.Severity.INFO,
                                        AuditLogger.AuditCategory.MEMORY_OPERATION,
                                        "auto_compaction", "compact",
                                        outcome = "success",
                                        details = mapOf(
                                            "tokens_recovered" to result.tokensRecovered,
                                            "summaries_created" to result.summariesCreated,
                                            "compression_ratio" to result.compressionRatio
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-compaction failed", e)
                }
            }

            // 4. Self-reflection and improvement every 50 heartbeats
            if (heartbeatCount % 50 == 0) {
                codeModManager?.let { manager ->
                    val metrics = mutableMapOf<String, Any>(
                        "heartbeat_count" to heartbeatCount,
                        "avg_processing_time" to
                            if (heartbeatCount > 0) totalProcessingTime / heartbeatCount else 0L,
                        "error_rate" to 0.0
                    )
                    // Include affect state at time of reflection
                    affectOrchestrator?.let { affect ->
                        val snap = affect.getAffectSnapshot()
                        metrics["affect_intensity"] = snap["intensity"] ?: 0.0f
                        metrics["affect_zero_noise"] = snap["zero_noise_state"] ?: false
                    }
                    val reflection = manager.reflect(metrics)
                    if (reflection.hasInsights()) {
                        Log.d(TAG, "Self-reflection insights: ${reflection.getInsights()}")
                    }
                }
            }

            // 4b. Feed affect system with heartbeat context
            affectOrchestrator?.let { affect ->
                // MemRL retrieval patterns feed into affect
                if (heartbeatCount % 10 == 0) {
                    ragStore?.let { store ->
                        val stats = store.getMemRLStats()
                        val avgQ = (stats["avg_q_value"] as? Float) ?: 0.0f
                        affect.processMemRLRetrieval("knowledge", avgQ)
                    }
                }

                // Self-reflection affects novelty & vulnerability
                if (heartbeatCount % 50 == 0) {
                    affect.processSelfModProposal()
                }

                // Store affect snapshot as memory every 20 heartbeats (~10 min)
                if (heartbeatCount % 20 == 0) {
                    val snapshot = affect.getAffectSnapshot()
                    ragStore?.addMemory(
                        "Affect state: intensity=${"%.2f".format(snapshot["intensity"])} " +
                                "zero_noise=${snapshot["zero_noise_state"]}",
                        0.6f
                    )
                }
            }

            // 5. Store secure heartbeat timestamp
            secureStorage?.let { storage ->
                val timestamp = System.currentTimeMillis()
                storage.store("last_heartbeat", timestamp.toString())
                storage.store("heartbeat_count", heartbeatCount.toString())
            }

            // 6. Log MemRL, consolidation, and OpenClaw subsystem stats every 100 heartbeats
            if (heartbeatCount % 100 == 0) {
                ragStore?.let { store ->
                    Log.d(TAG, "MemRL Stats: ${store.getMemRLStats()}")

                    consolidationManager?.let { manager ->
                        Log.d(TAG, "Consolidation Stats: ${manager.getStats()}")
                    }
                }

                // OpenClaw subsystem stats
                laneQueueExecutor?.let { Log.d(TAG, "LaneQueue Stats: ${it.getStats()}") }
                autoCompactionManager?.let { Log.d(TAG, "AutoCompaction Stats: ${it.getStats()}") }
                sessionKeyManager?.let { Log.d(TAG, "Session Stats: ${it.getStats()}") }
                subAgentManager?.let { Log.d(TAG, "SubAgent Stats: ${it.getStats()}") }
                safetyScanner?.let { Log.d(TAG, "SafetyScanner Stats: ${it.getStats()}") }
                auditLogger?.let { Log.d(TAG, "Audit Stats: ${it.getStats()}") }
                onDeviceLLMManager?.let { Log.d(TAG, "OnDeviceLLM Stats: ${it.getStats()}") }
                affectOrchestrator?.let { Log.d(TAG, "AffectEngine Stats: ${it.getStats()}") }
            }

            // 7. Archive expired sessions every 200 heartbeats (OpenClaw-inspired)
            if (heartbeatCount % 200 == 0) {
                try {
                    sessionKeyManager?.let { mgr ->
                        val archived = mgr.archiveExpiredSessions()
                        if (archived > 0) {
                            Log.d(TAG, "Archived $archived expired sessions")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Session archiving failed", e)
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

                                // Log self-optimization result if present
                                result.optimizationResult?.let { opt ->
                                    Log.d(TAG, "Self-optimization: ${opt.reason}, " +
                                            "fitness=${"%.4f".format(opt.fitness)}, " +
                                            "cycle=${opt.cycle}")
                                }

                                // Log Takens training result if present
                                result.trainingResult?.let { tr ->
                                    Log.d(TAG, "Takens training: $tr")
                                }

                                // Consolidation success elevates satiation and coherence
                                affectOrchestrator?.submitInput(
                                    AffectInput.builder("consolidation:complete")
                                        .satiation(0.15f)
                                        .coherence(0.1f)
                                        .valence(0.05f)
                                        .build()
                                )

                                auditLogger?.logAsync(
                                    AuditLogger.Severity.INFO,
                                    AuditLogger.AuditCategory.MEMORY_OPERATION,
                                    "consolidation_manager", "consolidate",
                                    outcome = "success"
                                )
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
        private const val SHUTDOWN_TIMEOUT_MS = 3_000L              // 3 seconds for graceful thread join

        const val SERVICE_STARTUP_STATE_KEY = "service_startup_state"
        const val SERVICE_STARTUP_REASON_KEY = "service_startup_reason"
        const val STATE_RUNNING = "running"
        const val STATE_DEFERRED = "deferred"
        const val STATE_BLOCKED_BY_PERMISSION = "blocked-by-permission"
        const val STATE_DEGRADED = "degraded"
    }
}
