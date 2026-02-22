package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * General-purpose HTTP client for authenticated API requests.
 * HTTPS-only with domain allowlisting for safety.
 *
 * Commands:
 *   get|url                          – HTTP GET
 *   post|url|body                    – HTTP POST with JSON body
 *   put|url|body                     – HTTP PUT
 *   delete|url                       – HTTP DELETE
 *   set_header|name|value            – Set persistent header (e.g., Authorization)
 *   remove_header|name               – Remove persistent header
 *   list_headers                     – Show configured headers
 *   allow_domain|domain              – Add domain to allowlist
 *   list_domains                     – Show allowed domains
 */
class HttpClientPlugin : Plugin {

    override val id: String = ID
    override val name: String = "HTTP Client"
    override val description: String =
        "General HTTP client. Commands: get|url, post|url|body, put|url|body, delete|url, set_header|name|value, remove_header|name, list_headers, allow_domain|domain, list_domains"
    override var isEnabled: Boolean = true

    private lateinit var prefs: SharedPreferences

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 3)
            val command = parts[0].trim().lowercase()

            when (command) {
                "get" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: get|url", elapsed(start))
                    val url = parts[1].trim()
                    enforceGuardrails(url)
                    val resp = request("GET", url, null)
                    PluginResult.success(resp, elapsed(start))
                }
                "post" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: post|url|body", elapsed(start))
                    enforceGuardrails(parts[1].trim())
                    val resp = request("POST", parts[1].trim(), parts[2].trim())
                    PluginResult.success(resp, elapsed(start))
                }
                "put" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: put|url|body", elapsed(start))
                    enforceGuardrails(parts[1].trim())
                    val resp = request("PUT", parts[1].trim(), parts[2].trim())
                    PluginResult.success(resp, elapsed(start))
                }
                "delete" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: delete|url", elapsed(start))
                    enforceGuardrails(parts[1].trim())
                    val resp = request("DELETE", parts[1].trim(), null)
                    PluginResult.success(resp, elapsed(start))
                }
                "set_header" -> {
                    if (parts.size < 3) return PluginResult.error("Usage: set_header|name|value", elapsed(start))
                    prefs.edit().putString("header_${parts[1].trim()}", parts[2].trim()).apply()
                    PluginResult.success("Header set: ${parts[1].trim()}", elapsed(start))
                }
                "remove_header" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: remove_header|name", elapsed(start))
                    prefs.edit().remove("header_${parts[1].trim()}").apply()
                    PluginResult.success("Header removed: ${parts[1].trim()}", elapsed(start))
                }
                "list_headers" -> {
                    val headers = prefs.all.filter { it.key.startsWith("header_") }
                        .map { it.key.removePrefix("header_") to it.value }
                    PluginResult.success("Headers: ${headers.joinToString { "${it.first}: ${it.second}" }}", elapsed(start))
                }
                "allow_domain" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: allow_domain|domain", elapsed(start))
                    val domains = getAllowedDomains().toMutableSet()
                    domains.add(parts[1].trim().lowercase())
                    prefs.edit().putStringSet("allowed_domains", domains).apply()
                    PluginResult.success("Domain allowed: ${parts[1].trim()}", elapsed(start))
                }
                "list_domains" -> {
                    val domains = getAllowedDomains()
                    PluginResult.success("Allowed domains (${domains.size}): ${domains.joinToString()}", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: SecurityException) {
            PluginResult.error("HTTP security: ${e.message}", elapsed(start))
        } catch (e: Exception) {
            PluginResult.error("HTTP error: ${e.message}", elapsed(start))
        }
    }

    private fun request(method: String, urlStr: String, body: String?): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "TronProtocol/1.0")
        conn.setRequestProperty("Accept", "application/json")

        // Apply persistent headers
        prefs.all.filter { it.key.startsWith("header_") }.forEach { (key, value) ->
            conn.setRequestProperty(key.removePrefix("header_"), value.toString())
        }

        if (body != null && (method == "POST" || method == "PUT")) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        }

        val code = conn.responseCode
        val stream = if (code in 200 until 400) conn.inputStream else conn.errorStream
        val response = if (stream != null) {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } else ""

        return JSONObject().apply {
            put("status", code)
            put("body", response.take(10000))
            put("method", method)
            put("url", urlStr)
        }.toString(2)
    }

    private fun enforceGuardrails(urlStr: String) {
        val url = URL(urlStr)
        if (url.protocol != "https") throw SecurityException("HTTPS required")

        val host = url.host.lowercase()
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.startsWith("0.")) {
            throw SecurityException("Local/private addresses blocked")
        }

        val domains = getAllowedDomains()
        if (domains.isNotEmpty() && !domains.any { host == it || host.endsWith(".$it") }) {
            throw SecurityException("Domain not in allowlist: $host. Use allow_domain|$host first.")
        }
    }

    private fun getAllowedDomains(): Set<String> =
        prefs.getStringSet("allowed_domains", emptySet()) ?: emptySet()

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        prefs = context.getSharedPreferences("http_client_plugin", Context.MODE_PRIVATE)
    }

    override fun destroy() {}

    companion object {
        const val ID = "http_client"
    }
}
