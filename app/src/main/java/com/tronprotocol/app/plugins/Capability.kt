package com.tronprotocol.app.plugins

/**
 * Scoped capabilities that can be granted to plugins.
 */
enum class Capability {
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    NETWORK_OUTBOUND,
    CONTACTS_READ,
    SMS_SEND,
    MODEL_EXECUTION,
    DEVICE_INFO_READ,
    MEMORY_READ,
    MEMORY_WRITE,
    TASK_AUTOMATION,
    CODE_EXECUTION
}
