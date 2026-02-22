package com.tronprotocol.app.plugins

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured queries against Android content providers: contacts, call log, SMS history.
 *
 * Commands:
 *   contacts|count         – List contacts (default 50)
 *   contact_search|name    – Search contacts by name
 *   call_log|count         – Recent call log entries (default 20)
 *   sms_inbox|count        – Recent SMS inbox (default 20)
 *   sms_search|keyword     – Search SMS messages
 */
class ContentProviderQueryPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Content Provider Query"
    override val description: String =
        "Query contacts, call log, SMS. Commands: contacts|count, contact_search|name, call_log|count, sms_inbox|count, sms_search|keyword"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return PluginResult.error("Context not available", 0)

        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "contacts" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 50
                    PluginResult.success(queryContacts(ctx, count).toString(2), elapsed(start))
                }
                "contact_search" -> {
                    val name = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: contact_search|name", elapsed(start))
                    PluginResult.success(searchContacts(ctx, name).toString(2), elapsed(start))
                }
                "call_log" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    PluginResult.success(queryCallLog(ctx, count).toString(2), elapsed(start))
                }
                "sms_inbox" -> {
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
                    PluginResult.success(querySmsInbox(ctx, count).toString(2), elapsed(start))
                }
                "sms_search" -> {
                    val keyword = parts.getOrNull(1)?.trim()
                        ?: return PluginResult.error("Usage: sms_search|keyword", elapsed(start))
                    PluginResult.success(searchSms(ctx, keyword).toString(2), elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: SecurityException) {
            PluginResult.error("Permission not granted for this query", elapsed(start))
        } catch (e: Exception) {
            PluginResult.error("Content provider error: ${e.message}", elapsed(start))
        }
    }

    private fun queryContacts(ctx: Context, limit: Int): JSONArray {
        val contacts = JSONArray()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.STARRED
        )
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection,
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )
            var count = 0
            cursor?.let {
                while (it.moveToNext() && count < limit) {
                    contacts.put(JSONObject().apply {
                        put("id", it.getLong(0))
                        put("name", it.getString(1) ?: "")
                        put("has_phone", it.getInt(2) > 0)
                        put("starred", it.getInt(3) > 0)
                    })
                    count++
                }
            }
        } finally {
            cursor?.close()
        }
        return contacts
    }

    private fun searchContacts(ctx: Context, name: String): JSONArray {
        val contacts = JSONArray()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection, selection, selectionArgs, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    contacts.put(JSONObject().apply {
                        put("id", it.getLong(0))
                        put("name", it.getString(1) ?: "")
                        put("has_phone", it.getInt(2) > 0)
                    })
                }
            }
        } finally {
            cursor?.close()
        }
        return contacts
    }

    private fun queryCallLog(ctx: Context, limit: Int): JSONArray {
        val calls = JSONArray()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection,
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            var count = 0
            cursor?.let {
                while (it.moveToNext() && count < limit) {
                    calls.put(JSONObject().apply {
                        put("number", it.getString(1) ?: "")
                        put("name", it.getString(2) ?: "")
                        put("type", when (it.getInt(3)) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            else -> "unknown"
                        })
                        put("date", dateFormat.format(Date(it.getLong(4))))
                        put("duration_sec", it.getLong(5))
                    })
                    count++
                }
            }
        } finally {
            cursor?.close()
        }
        return calls
    }

    private fun querySmsInbox(ctx: Context, limit: Int): JSONArray {
        val messages = JSONArray()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection,
                null, null,
                "${Telephony.Sms.DATE} DESC"
            )
            var count = 0
            cursor?.let {
                while (it.moveToNext() && count < limit) {
                    messages.put(JSONObject().apply {
                        put("address", it.getString(1) ?: "")
                        put("body", (it.getString(2) ?: "").take(500))
                        put("date", dateFormat.format(Date(it.getLong(3))))
                        put("read", it.getInt(4) == 1)
                    })
                    count++
                }
            }
        } finally {
            cursor?.close()
        }
        return messages
    }

    private fun searchSms(ctx: Context, keyword: String): JSONArray {
        val messages = JSONArray()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val selection = "${Telephony.Sms.BODY} LIKE ?"
        val selectionArgs = arrayOf("%$keyword%")
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs,
                "${Telephony.Sms.DATE} DESC"
            )
            var count = 0
            cursor?.let {
                while (it.moveToNext() && count < 50) {
                    messages.put(JSONObject().apply {
                        put("address", it.getString(1) ?: "")
                        put("body", (it.getString(2) ?: "").take(500))
                        put("date", dateFormat.format(Date(it.getLong(3))))
                    })
                    count++
                }
            }
        } finally {
            cursor?.close()
        }
        return messages
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun destroy() {
        appContext = null
    }

    companion object {
        const val ID = "content_provider_query"
    }
}
