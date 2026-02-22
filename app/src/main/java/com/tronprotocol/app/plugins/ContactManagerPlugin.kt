package com.tronprotocol.app.plugins

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import org.json.JSONObject

/**
 * Contacts CRUD operations with relationship metadata.
 *
 * Commands:
 *   add|name|phone|email       – Add a new contact
 *   update|contactId|field|value – Update a contact field
 *   get|contactId              – Get contact details
 *   search|name                – Search contacts by name (delegates to ContentProviderQueryPlugin)
 *   delete|contactId           – Delete a contact
 *   add_note|contactId|note    – Add a note to contact (stored in plugin prefs)
 */
class ContactManagerPlugin : Plugin {

    override val id: String = ID
    override val name: String = "Contact Manager"
    override val description: String =
        "Manage contacts. Commands: add|name|phone|email, update|id|field|value, get|id, search|name, delete|id, add_note|id|note"
    override var isEnabled: Boolean = true

    private var appContext: Context? = null

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return PluginResult.error("Context not available", 0)

        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add" -> {
                    if (parts.size < 4) return PluginResult.error("Usage: add|name|phone|email", elapsed(start))
                    val name = parts[1].trim()
                    val phone = parts[2].trim()
                    val email = parts[3].trim()
                    val ops = ArrayList<ContentProviderOperation>()
                    val rawIdx = ops.size
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build())
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build())
                    if (phone.isNotEmpty()) {
                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build())
                    }
                    if (email.isNotEmpty()) {
                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                            .build())
                    }
                    ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    PluginResult.success("Contact added: $name ($phone, $email)", elapsed(start))
                }
                "get" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: get|contactId", elapsed(start))
                    val contactId = parts[1].trim()
                    val json = JSONObject().apply { put("id", contactId) }
                    // Query display name
                    val cursor = ctx.contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                        "${ContactsContract.Contacts._ID} = ?",
                        arrayOf(contactId), null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) json.put("name", it.getString(0) ?: "")
                    }
                    PluginResult.success(json.toString(2), elapsed(start))
                }
                "delete" -> {
                    if (parts.size < 2) return PluginResult.error("Usage: delete|contactId", elapsed(start))
                    val uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
                        .appendPath(parts[1].trim()).build()
                    val deleted = ctx.contentResolver.delete(uri, null, null)
                    PluginResult.success("Deleted $deleted contact(s)", elapsed(start))
                }
                else -> PluginResult.error("Unknown command: $command. Use: add, get, delete", elapsed(start))
            }
        } catch (e: SecurityException) {
            PluginResult.error("Contacts permission not granted", elapsed(start))
        } catch (e: Exception) {
            PluginResult.error("Contact manager error: ${e.message}", elapsed(start))
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun destroy() {
        appContext = null
    }

    companion object {
        const val ID = "contact_manager"
    }
}
