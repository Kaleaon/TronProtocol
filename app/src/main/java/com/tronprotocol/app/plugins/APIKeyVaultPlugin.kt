package com.tronprotocol.app.plugins

import android.content.Context
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Secure storage of third-party API credentials with per-plugin access control.
 * Uses hardware-backed encryption via SecureStorage.
 *
 * Commands:
 *   store|service|key             – Store an API key securely
 *   get|service                   – Retrieve a stored key
 *   list                          – List stored service names (not keys)
 *   delete|service                – Delete a stored key
 *   grant|service|pluginId        – Grant a plugin access to a service key
 *   revoke|service|pluginId       – Revoke a plugin's access
 *   check|service|pluginId        – Check if plugin has access
 */
class APIKeyVaultPlugin : Plugin {

    override val id: String = ID
    override val name: String = "API Key Vault"
    override val description: String =
        "Secure API key storage. Commands: store|service|key, get|service, list, delete|service, grant|service|pluginId, revoke|service|pluginId, check|service|pluginId"
    override var isEnabled: Boolean = true

    private var storage: SecureStorage? = null

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val store = storage ?: return PluginResult.error("Storage not available", 0)

        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "store" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: store|service|key", elapsed(start))
                    val service = parts[1].trim()
                    store.store("vault_key_$service", parts[2].trim())
                    // Track service names
                    val services = getServiceNames(store).toMutableSet()
                    services.add(service)
                    store.store("vault_services", services.joinToString(","))
                    PluginResult.success("Key stored for: $service", elapsed(start))
                }
                "get" -> {
                    val service = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: get|service", elapsed(start))
                    val key = store.retrieve("vault_key_$service")
                    if (key != null) {
                        PluginResult.success("Key for $service: ${key.take(4)}${"*".repeat((key.length - 4).coerceAtLeast(0))}", elapsed(start))
                    } else {
                        PluginResult.error("No key stored for: $service", elapsed(start))
                    }
                }
                "list" -> {
                    val services = getServiceNames(store)
                    PluginResult.success("Stored services: ${services.joinToString(", ")}", elapsed(start))
                }
                "delete" -> {
                    val service = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: delete|service", elapsed(start))
                    store.delete("vault_key_$service")
                    store.delete("vault_acl_$service")
                    val services = getServiceNames(store).toMutableSet()
                    services.remove(service)
                    store.store("vault_services", services.joinToString(","))
                    PluginResult.success("Deleted key for: $service", elapsed(start))
                }
                "grant" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: grant|service|pluginId", elapsed(start))
                    val service = parts[1].trim()
                    val pluginId = parts[2].trim()
                    val acl = getACL(store, service).toMutableSet()
                    acl.add(pluginId)
                    store.store("vault_acl_$service", acl.joinToString(","))
                    PluginResult.success("Granted $pluginId access to $service", elapsed(start))
                }
                "revoke" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: revoke|service|pluginId", elapsed(start))
                    val service = parts[1].trim()
                    val pluginId = parts[2].trim()
                    val acl = getACL(store, service).toMutableSet()
                    acl.remove(pluginId)
                    store.store("vault_acl_$service", acl.joinToString(","))
                    PluginResult.success("Revoked $pluginId access to $service", elapsed(start))
                }
                "check" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: check|service|pluginId", elapsed(start))
                    val service = parts[1].trim()
                    val pluginId = parts[2].trim()
                    val hasAccess = getACL(store, service).contains(pluginId)
                    PluginResult.success("$pluginId access to $service: $hasAccess", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Vault error: ${e.message}", elapsed(start))
        }
    }

    private fun getServiceNames(store: SecureStorage): Set<String> {
        val str = store.retrieve("vault_services") ?: return emptySet()
        return str.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun getACL(store: SecureStorage, service: String): Set<String> {
        val str = store.retrieve("vault_acl_$service") ?: return emptySet()
        return str.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        storage = SecureStorage(context)
    }

    override fun destroy() {
        storage = null
    }

    companion object {
        const val ID = "api_key_vault"
    }
}
