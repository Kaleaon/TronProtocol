package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Progressive Plugin Loader - NanoBot-inspired lazy loading with lightweight summaries.
 *
 * Inspired by NanoBot's progressive skill loading:
 * - At startup, only lightweight PluginSummary objects are created (id, name, description)
 * - Actual plugin initialization is deferred until first use
 * - Priority plugins can be pre-warmed in background
 * - Reduces startup time and memory usage for large plugin sets
 * - Tracks loading metrics (cold vs warm loads, init times)
 *
 * Flow:
 * 1. registerSummaries() - fast: just stores metadata from PluginRegistry
 * 2. warmPriorityPlugins() - background: initializes high-priority plugins
 * 3. getPlugin() - on-demand: initializes plugin on first access if not warmed
 */
class ProgressivePluginLoader(private val context: Context) {

    /** Lightweight plugin summary - loaded instantly, no actual initialization. */
    data class PluginSummary(
        val id: String,
        val name: String,
        val description: String,
        val priority: Int,
        val defaultEnabled: Boolean,
        val className: String
    )

    /** Loading metrics for a single plugin. */
    data class LoadMetrics(
        val pluginId: String,
        val loadTimeMs: Long,
        val wasWarm: Boolean,
        val loadedAt: Long = System.currentTimeMillis()
    )

    // Summaries (always available, lightweight)
    private val summaries = ConcurrentHashMap<String, PluginSummary>()

    // Factory functions by ID
    private val factories = ConcurrentHashMap<String, () -> Plugin>()

    // Loaded plugins (initialized, ready to use)
    private val loadedPlugins = ConcurrentHashMap<String, Plugin>()

    // Loading metrics
    private val loadMetrics = ConcurrentHashMap<String, LoadMetrics>()

    // Counters
    private val coldLoads = AtomicInteger(0)
    private val warmLoads = AtomicInteger(0)

    /**
     * Phase 1: Register all plugin summaries from the registry.
     * This is fast - no actual plugin instantiation happens.
     */
    fun registerSummaries(configs: List<PluginRegistry.PluginConfig>) {
        val startTime = System.currentTimeMillis()

        for (config in configs) {
            summaries[config.id] = PluginSummary(
                id = config.id,
                name = config.id.replace("_", " ").replaceFirstChar { it.uppercase() },
                description = "Plugin: ${config.id}",
                priority = config.startupPriority,
                defaultEnabled = config.defaultEnabled,
                className = config.pluginClass.simpleName
            )
            factories[config.id] = config.factory
        }

        Log.d(TAG, "Registered ${configs.size} plugin summaries in ${System.currentTimeMillis() - startTime}ms")
    }

    /**
     * Phase 2: Pre-warm high-priority plugins in background.
     * Only initializes plugins with priority <= threshold.
     *
     * @param maxPriority Only warm plugins with priority <= this value
     * @return Number of plugins warmed
     */
    fun warmPriorityPlugins(maxPriority: Int = DEFAULT_WARM_PRIORITY_THRESHOLD): Int {
        val toWarm = summaries.values
            .filter { it.priority <= maxPriority }
            .sortedBy { it.priority }

        var warmed = 0
        for (summary in toWarm) {
            try {
                loadPlugin(summary.id, warm = true)
                warmed++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to warm plugin '${summary.id}'", e)
            }
        }

        Log.d(TAG, "Warmed $warmed/$${toWarm.size} priority plugins (threshold=$maxPriority)")
        return warmed
    }

    /**
     * Phase 3: Get a plugin, initializing on-demand if not yet loaded.
     * Returns null if plugin ID is unknown.
     */
    fun getPlugin(pluginId: String): Plugin? {
        // Fast path: already loaded
        loadedPlugins[pluginId]?.let {
            warmLoads.incrementAndGet()
            return it
        }

        // Check if we know about this plugin
        if (pluginId !in summaries) return null

        // Cold load: initialize now
        return loadPlugin(pluginId, warm = false)
    }

    /**
     * Get all currently loaded plugins (initialized only).
     */
    fun getLoadedPlugins(): List<Plugin> = loadedPlugins.values.toList()

    /**
     * Get all summaries (available instantly, no initialization required).
     */
    fun getAllSummaries(): List<PluginSummary> = summaries.values.toList()

    /**
     * Get summaries for plugins not yet loaded (still deferred).
     */
    fun getDeferredSummaries(): List<PluginSummary> {
        return summaries.values.filter { it.id !in loadedPlugins }
    }

    /**
     * Check if a specific plugin is loaded (initialized).
     */
    fun isLoaded(pluginId: String): Boolean = pluginId in loadedPlugins

    /**
     * Unload a plugin, freeing its resources.
     * The summary remains available for re-loading.
     */
    fun unloadPlugin(pluginId: String): Boolean {
        val plugin = loadedPlugins.remove(pluginId) ?: return false
        try {
            plugin.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying plugin '$pluginId'", e)
        }
        Log.d(TAG, "Unloaded plugin '$pluginId'")
        return true
    }

    /**
     * Load all remaining unloaded plugins.
     * Used when full initialization is acceptable (e.g., after app is idle).
     */
    fun loadAll(): Int {
        var loaded = 0
        for (id in summaries.keys) {
            if (id !in loadedPlugins) {
                try {
                    loadPlugin(id, warm = false)
                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load plugin '$id'", e)
                }
            }
        }
        return loaded
    }

    /**
     * Register all loaded plugins with the PluginManager.
     */
    fun registerWithPluginManager(pluginManager: PluginManager) {
        for ((_, plugin) in loadedPlugins) {
            pluginManager.registerPlugin(plugin)
        }
        Log.d(TAG, "Registered ${loadedPlugins.size} plugins with PluginManager")
    }

    /**
     * Get loading statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_summaries" to summaries.size,
        "loaded_plugins" to loadedPlugins.size,
        "deferred_plugins" to (summaries.size - loadedPlugins.size),
        "cold_loads" to coldLoads.get(),
        "warm_loads" to warmLoads.get(),
        "avg_cold_load_ms" to if (coldLoads.get() > 0) {
            loadMetrics.values.filter { !it.wasWarm }.map { it.loadTimeMs }.average().toLong()
        } else 0L,
        "avg_warm_load_ms" to if (warmLoads.get() > 0) {
            loadMetrics.values.filter { it.wasWarm }.map { it.loadTimeMs }.average().toLong()
        } else 0L,
        "load_details" to loadMetrics.values.map {
            "${it.pluginId}: ${it.loadTimeMs}ms (${if (it.wasWarm) "warm" else "cold"})"
        }
    )

    /**
     * Destroy all loaded plugins and clear state.
     */
    fun destroy() {
        for ((id, plugin) in loadedPlugins) {
            try {
                plugin.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying plugin '$id'", e)
            }
        }
        loadedPlugins.clear()
        Log.d(TAG, "ProgressivePluginLoader destroyed")
    }

    // -- Internal --

    private fun loadPlugin(pluginId: String, warm: Boolean): Plugin? {
        val factory = factories[pluginId] ?: return null

        val startTime = System.currentTimeMillis()
        return try {
            val plugin = factory()
            plugin.initialize(context)
            loadedPlugins[pluginId] = plugin

            val loadTime = System.currentTimeMillis() - startTime
            loadMetrics[pluginId] = LoadMetrics(pluginId, loadTime, warm)

            if (warm) {
                warmLoads.incrementAndGet()
            } else {
                coldLoads.incrementAndGet()
            }

            Log.d(TAG, "Loaded plugin '$pluginId' in ${loadTime}ms (${if (warm) "warm" else "cold"})")
            plugin
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin '$pluginId'", e)
            null
        }
    }

    companion object {
        private const val TAG = "ProgressivePluginLoader"
        private const val DEFAULT_WARM_PRIORITY_THRESHOLD = 50  // Warm priorities <= 50
    }
}
