package com.tronprotocol.app.mindnexus

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Low-level binary I/O primitives for the Mind Nexus format.
 *
 * All multi-byte values use big-endian byte order (network order).
 * Strings are length-prefixed (4-byte count + UTF-8 bytes).
 *
 * These utilities are used by [MnxCodec] and section serializers to
 * produce and consume the binary payload of each .mnx section.
 */

// =============================================================================
//  WRITER
// =============================================================================

/**
 * Wraps an [OutputStream] and provides typed write methods for every
 * primitive used in the .mnx format.
 */
class MnxWriter(output: OutputStream) {

    private val dos = DataOutputStream(output)

    /** Bytes written so far. */
    val bytesWritten: Long get() = dos.size().toLong()

    // ---- Primitives ---------------------------------------------------------

    fun writeByte(v: Byte) = dos.writeByte(v.toInt())
    fun writeBoolean(v: Boolean) = dos.writeByte(if (v) 1 else 0)
    fun writeShort(v: Short) = dos.writeShort(v.toInt())
    fun writeInt(v: Int) = dos.writeInt(v)
    fun writeLong(v: Long) = dos.writeLong(v)
    fun writeFloat(v: Float) = dos.writeFloat(v)
    fun writeDouble(v: Double) = dos.writeDouble(v)
    fun writeBytes(data: ByteArray) = dos.write(data)

    // ---- Length-prefixed String ---------------------------------------------

    fun writeString(s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    // ---- UUID ---------------------------------------------------------------

    fun writeUuid(uuid: UUID) {
        dos.writeLong(uuid.mostSignificantBits)
        dos.writeLong(uuid.leastSignificantBits)
    }

    // ---- Float array (embedding vectors) ------------------------------------

    fun writeFloatArray(arr: FloatArray) {
        dos.writeInt(arr.size)
        for (f in arr) dos.writeFloat(f)
    }

    fun writeNullableFloatArray(arr: FloatArray?) {
        if (arr == null) {
            dos.writeInt(-1) // sentinel for null
        } else {
            writeFloatArray(arr)
        }
    }

    // ---- Collections --------------------------------------------------------

    /**
     * Write a list using the provided element serializer.
     */
    inline fun <T> writeList(list: List<T>, writeElement: (T) -> Unit) {
        dos.writeInt(list.size)
        for (item in list) writeElement(item)
    }

    /**
     * Write a string list.
     */
    fun writeStringList(list: List<String>) {
        dos.writeInt(list.size)
        for (s in list) writeString(s)
    }

    /**
     * Write a Map<String, String>.
     */
    fun writeStringMap(map: Map<String, String>) {
        dos.writeInt(map.size)
        for ((k, v) in map) {
            writeString(k)
            writeString(v)
        }
    }

    /**
     * Write a Map<String, Float>.
     */
    fun writeStringFloatMap(map: Map<String, Float>) {
        dos.writeInt(map.size)
        for ((k, v) in map) {
            writeString(k)
            dos.writeFloat(v)
        }
    }

    /**
     * Write a Map<String, Any> where values are limited to:
     * String, Int, Long, Float, Double, Boolean.
     * Each value is prefixed with a 1-byte type tag.
     */
    fun writeMetadataMap(map: Map<String, Any>) {
        dos.writeInt(map.size)
        for ((k, v) in map) {
            writeString(k)
            writeTypedValue(v)
        }
    }

    private fun writeTypedValue(v: Any) {
        when (v) {
            is String -> { dos.writeByte(TYPE_STRING.toInt()); writeString(v) }
            is Int -> { dos.writeByte(TYPE_INT.toInt()); dos.writeInt(v) }
            is Long -> { dos.writeByte(TYPE_LONG.toInt()); dos.writeLong(v) }
            is Float -> { dos.writeByte(TYPE_FLOAT.toInt()); dos.writeFloat(v) }
            is Double -> { dos.writeByte(TYPE_DOUBLE.toInt()); dos.writeDouble(v) }
            is Boolean -> { dos.writeByte(TYPE_BOOLEAN.toInt()); dos.writeByte(if (v) 1 else 0) }
            else -> {
                // Fallback: serialize as string
                dos.writeByte(TYPE_STRING.toInt())
                writeString(v.toString())
            }
        }
    }

    fun flush() = dos.flush()
    fun close() = dos.close()

    companion object {
        // Type tags for polymorphic metadata values
        const val TYPE_STRING: Byte = 0x01
        const val TYPE_INT: Byte = 0x02
        const val TYPE_LONG: Byte = 0x03
        const val TYPE_FLOAT: Byte = 0x04
        const val TYPE_DOUBLE: Byte = 0x05
        const val TYPE_BOOLEAN: Byte = 0x06
    }
}

// =============================================================================
//  READER
// =============================================================================

/**
 * Wraps an [InputStream] and provides typed read methods that mirror [MnxWriter].
 */
class MnxReader(input: InputStream) {

    private val dis = DataInputStream(input)

    // ---- Primitives ---------------------------------------------------------

    fun readByte(): Byte = dis.readByte()
    fun readBoolean(): Boolean = dis.readByte().toInt() != 0
    fun readShort(): Short = dis.readShort()
    fun readInt(): Int = dis.readInt()
    fun readLong(): Long = dis.readLong()
    fun readFloat(): Float = dis.readFloat()
    fun readDouble(): Double = dis.readDouble()

    fun readBytes(count: Int): ByteArray {
        val buf = ByteArray(count)
        dis.readFully(buf)
        return buf
    }

    fun skipBytes(count: Int) {
        dis.skipBytes(count)
    }

    // ---- Length-prefixed String ---------------------------------------------

    fun readString(): String {
        val len = dis.readInt()
        if (len < 0 || len > MAX_STRING_LENGTH) {
            throw MnxFormatException("Invalid string length: $len")
        }
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    // ---- UUID ---------------------------------------------------------------

    fun readUuid(): UUID {
        val msb = dis.readLong()
        val lsb = dis.readLong()
        return UUID(msb, lsb)
    }

    // ---- Float array --------------------------------------------------------

    fun readFloatArray(): FloatArray {
        val count = dis.readInt()
        if (count < 0 || count > MAX_ARRAY_LENGTH) {
            throw MnxFormatException("Invalid float array length: $count")
        }
        val arr = FloatArray(count)
        for (i in 0 until count) arr[i] = dis.readFloat()
        return arr
    }

    fun readNullableFloatArray(): FloatArray? {
        val count = dis.readInt()
        if (count == -1) return null
        if (count < 0 || count > MAX_ARRAY_LENGTH) {
            throw MnxFormatException("Invalid float array length: $count")
        }
        val arr = FloatArray(count)
        for (i in 0 until count) arr[i] = dis.readFloat()
        return arr
    }

    // ---- Collections --------------------------------------------------------

    inline fun <T> readList(readElement: () -> T): List<T> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_LIST_LENGTH) {
            throw MnxFormatException("Invalid list length: $count")
        }
        return List(count) { readElement() }
    }

    fun readStringList(): List<String> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_LIST_LENGTH) {
            throw MnxFormatException("Invalid string list length: $count")
        }
        return List(count) { readString() }
    }

    fun readStringMap(): Map<String, String> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_MAP_LENGTH) {
            throw MnxFormatException("Invalid map size: $count")
        }
        val map = LinkedHashMap<String, String>(count)
        repeat(count) {
            val k = readString()
            val v = readString()
            map[k] = v
        }
        return map
    }

    fun readStringFloatMap(): Map<String, Float> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_MAP_LENGTH) {
            throw MnxFormatException("Invalid map size: $count")
        }
        val map = LinkedHashMap<String, Float>(count)
        repeat(count) {
            val k = readString()
            val v = dis.readFloat()
            map[k] = v
        }
        return map
    }

    fun readMetadataMap(): Map<String, Any> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_MAP_LENGTH) {
            throw MnxFormatException("Invalid metadata map size: $count")
        }
        val map = LinkedHashMap<String, Any>(count)
        repeat(count) {
            val k = readString()
            val v = readTypedValue()
            map[k] = v
        }
        return map
    }

    private fun readTypedValue(): Any {
        return when (val tag = dis.readByte()) {
            MnxWriter.TYPE_STRING -> readString()
            MnxWriter.TYPE_INT -> dis.readInt()
            MnxWriter.TYPE_LONG -> dis.readLong()
            MnxWriter.TYPE_FLOAT -> dis.readFloat()
            MnxWriter.TYPE_DOUBLE -> dis.readDouble()
            MnxWriter.TYPE_BOOLEAN -> dis.readByte().toInt() != 0
            else -> throw MnxFormatException("Unknown metadata type tag: $tag")
        }
    }

    fun close() = dis.close()

    companion object {
        const val MAX_STRING_LENGTH = 10 * 1024 * 1024   // 10 MB
        const val MAX_ARRAY_LENGTH = 1_000_000           // 1M floats = 4 MB
        const val MAX_LIST_LENGTH = 1_000_000
        const val MAX_MAP_LENGTH = 1_000_000
    }
}

// =============================================================================
//  HELPERS
// =============================================================================

/**
 * Serialize a block to a byte array using [MnxWriter].
 */
inline fun mnxSerialize(block: MnxWriter.() -> Unit): ByteArray {
    val baos = ByteArrayOutputStream()
    val writer = MnxWriter(baos)
    writer.block()
    writer.flush()
    return baos.toByteArray()
}

/**
 * Deserialize from a byte array using [MnxReader].
 */
inline fun <T> mnxDeserialize(data: ByteArray, block: MnxReader.() -> T): T {
    val reader = MnxReader(data.inputStream())
    return reader.block()
}

// =============================================================================
//  EXCEPTIONS
// =============================================================================

/**
 * Thrown when the .mnx binary stream contains invalid or unexpected data.
 */
class MnxFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
