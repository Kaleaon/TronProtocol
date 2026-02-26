package com.tronprotocol.app.mindnexus

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MnxBinaryStreamTest {

    // ---- writeInt/readInt round-trip ----

    @Test
    fun writeIntReadInt_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(42)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(42, reader.readInt())
    }

    @Test
    fun writeIntReadInt_zero() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(0)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(0, reader.readInt())
    }

    @Test
    fun writeIntReadInt_negative() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(-12345)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(-12345, reader.readInt())
    }

    @Test
    fun writeIntReadInt_maxValue() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(Int.MAX_VALUE)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(Int.MAX_VALUE, reader.readInt())
    }

    @Test
    fun writeIntReadInt_minValue() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(Int.MIN_VALUE)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(Int.MIN_VALUE, reader.readInt())
    }

    // ---- writeLong/readLong round-trip ----

    @Test
    fun writeLongReadLong_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeLong(123456789012345L)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(123456789012345L, reader.readLong())
    }

    @Test
    fun writeLongReadLong_zero() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeLong(0L)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(0L, reader.readLong())
    }

    @Test
    fun writeLongReadLong_negative() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeLong(-9999999999L)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(-9999999999L, reader.readLong())
    }

    // ---- writeFloat/readFloat round-trip ----

    @Test
    fun writeFloatReadFloat_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeFloat(3.14159f)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(3.14159f, reader.readFloat(), 0.00001f)
    }

    @Test
    fun writeFloatReadFloat_zero() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeFloat(0.0f)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(0.0f, reader.readFloat(), 0.0f)
    }

    @Test
    fun writeFloatReadFloat_negative() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeFloat(-1.5f)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(-1.5f, reader.readFloat(), 0.0f)
    }

    // ---- writeString/readString round-trip ----

    @Test
    fun writeStringReadString_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeString("Hello, World!")
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals("Hello, World!", reader.readString())
    }

    // ---- writeString/readString with empty string ----

    @Test
    fun writeStringReadString_emptyString() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeString("")
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals("", reader.readString())
    }

    // ---- writeString/readString with unicode characters ----

    @Test
    fun writeStringReadString_unicode() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val unicodeStr = "Hello \u4e16\u754c \ud83d\ude00 \u00e9\u00e8\u00ea"
        writer.writeString(unicodeStr)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(unicodeStr, reader.readString())
    }

    @Test
    fun writeStringReadString_japaneseCharacters() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val japaneseStr = "\u3053\u3093\u306b\u3061\u306f\u4e16\u754c"
        writer.writeString(japaneseStr)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(japaneseStr, reader.readString())
    }

    @Test
    fun writeStringReadString_emoji() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val emojiStr = "\uD83D\uDE80\uD83C\uDF1F\uD83E\uDD16"
        writer.writeString(emojiStr)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(emojiStr, reader.readString())
    }

    // ---- writeBytes/readBytes round-trip ----

    @Test
    fun writeBytesReadBytes_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x00)
        writer.writeBytes(data)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        val result = reader.readBytes(data.size)
        assertArrayEquals(data, result)
    }

    @Test
    fun writeBytesReadBytes_emptyArray() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val data = byteArrayOf()
        writer.writeBytes(data)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        val result = reader.readBytes(0)
        assertArrayEquals(data, result)
    }

    // ---- multiple values written and read in order ----

    @Test
    fun multipleValues_writtenAndReadInOrder() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(42)
        writer.writeLong(9876543210L)
        writer.writeFloat(2.718f)
        writer.writeString("test")
        writer.writeBytes(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(42, reader.readInt())
        assertEquals(9876543210L, reader.readLong())
        assertEquals(2.718f, reader.readFloat(), 0.001f)
        assertEquals("test", reader.readString())
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), reader.readBytes(2))
    }

    @Test
    fun multipleStrings_writtenAndReadInOrder() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeString("first")
        writer.writeString("second")
        writer.writeString("third")
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals("first", reader.readString())
        assertEquals("second", reader.readString())
        assertEquals("third", reader.readString())
    }

    // ---- big-endian byte order verification ----

    @Test
    fun bigEndian_intByteOrder() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeInt(0x01020304)
        writer.flush()

        val bytes = baos.toByteArray()
        assertEquals(4, bytes.size)
        // Big-endian: most significant byte first
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
        assertEquals(0x03.toByte(), bytes[2])
        assertEquals(0x04.toByte(), bytes[3])
    }

    @Test
    fun bigEndian_longByteOrder() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeLong(0x0102030405060708L)
        writer.flush()

        val bytes = baos.toByteArray()
        assertEquals(8, bytes.size)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
        assertEquals(0x03.toByte(), bytes[2])
        assertEquals(0x04.toByte(), bytes[3])
        assertEquals(0x05.toByte(), bytes[4])
        assertEquals(0x06.toByte(), bytes[5])
        assertEquals(0x07.toByte(), bytes[6])
        assertEquals(0x08.toByte(), bytes[7])
    }

    @Test
    fun bigEndian_shortByteOrder() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeShort(0x0102.toShort())
        writer.flush()

        val bytes = baos.toByteArray()
        assertEquals(2, bytes.size)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
    }

    // ---- String encoding: length prefix ----

    @Test
    fun string_lengthPrefix_isIntBigEndian() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeString("AB")
        writer.flush()

        val bytes = baos.toByteArray()
        // 4-byte length prefix (big-endian) for "AB" which is 2 bytes in UTF-8
        assertEquals(6, bytes.size) // 4 (length) + 2 (data)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x02.toByte(), bytes[3])
        assertEquals('A'.code.toByte(), bytes[4])
        assertEquals('B'.code.toByte(), bytes[5])
    }

    // ---- Boolean round-trip ----

    @Test
    fun writeBoolean_readBoolean_true() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeBoolean(true)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(reader.readBoolean())
    }

    @Test
    fun writeBoolean_readBoolean_false() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeBoolean(false)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertFalse(reader.readBoolean())
    }

    // ---- Float array round-trip ----

    @Test
    fun writeFloatArray_readFloatArray_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val arr = floatArrayOf(1.0f, 2.5f, -3.7f, 0.0f)
        writer.writeFloatArray(arr)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        val result = reader.readFloatArray()
        assertArrayEquals(arr, result, 0.001f)
    }

    @Test
    fun writeFloatArray_readFloatArray_empty() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        writer.writeFloatArray(floatArrayOf())
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        val result = reader.readFloatArray()
        assertEquals(0, result.size)
    }

    // ---- String list round-trip ----

    @Test
    fun writeStringList_readStringList_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val list = listOf("alpha", "beta", "gamma")
        writer.writeStringList(list)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(list, reader.readStringList())
    }

    // ---- String map round-trip ----

    @Test
    fun writeStringMap_readStringMap_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val map = mapOf("key1" to "value1", "key2" to "value2")
        writer.writeStringMap(map)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(map, reader.readStringMap())
    }

    // ---- String-Float map round-trip ----

    @Test
    fun writeStringFloatMap_readStringFloatMap_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val map = mapOf("dim1" to 0.5f, "dim2" to 0.8f)
        writer.writeStringFloatMap(map)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        val result = reader.readStringFloatMap()
        assertEquals(2, result.size)
        assertEquals(0.5f, result["dim1"]!!, 0.001f)
        assertEquals(0.8f, result["dim2"]!!, 0.001f)
    }

    // ---- UUID round-trip ----

    @Test
    fun writeUuid_readUuid_roundTrip() {
        val baos = ByteArrayOutputStream()
        val writer = MnxWriter(baos)
        val uuid = java.util.UUID.randomUUID()
        writer.writeUuid(uuid)
        writer.flush()

        val reader = MnxReader(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(uuid, reader.readUuid())
    }

    // ---- mnxSerialize / mnxDeserialize helpers ----

    @Test
    fun mnxSerialize_producesBytes() {
        val bytes = mnxSerialize {
            writeInt(100)
            writeString("test")
        }
        assertTrue(bytes.isNotEmpty())

        val result = mnxDeserialize(bytes) {
            val i = readInt()
            val s = readString()
            Pair(i, s)
        }
        assertEquals(100, result.first)
        assertEquals("test", result.second)
    }
}
