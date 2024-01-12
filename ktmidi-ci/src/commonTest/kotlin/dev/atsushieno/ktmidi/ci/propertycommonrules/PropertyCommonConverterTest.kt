package dev.atsushieno.ktmidi.ci.propertycommonrules

import io.ktor.utils.io.core.*
import kotlin.test.Test
import kotlin.test.assertContentEquals

class PropertyCommonConverterTest {
    @Test
    fun encodeToMcoded7() {
        val input = "{\"foo\": [1,2,3,4,5], \"bar\": [6,7,8,9,0]}\n".toByteArray().toList()
        val expected = listOf(
            0x00, 0x7b, 0x22, 0x66, 0x6f, 0x6f, 0x22, 0x3a,
            0x00, 0x20, 0x5b, 0x31, 0x2c, 0x32, 0x2c, 0x33,
            0x00, 0x2c, 0x34, 0x2c, 0x35, 0x5d, 0x2c, 0x20,
            0x00, 0x22, 0x62, 0x61, 0x72, 0x22, 0x3a, 0x20,
            0x00, 0x5b, 0x36, 0x2c, 0x37, 0x2c, 0x38, 0x2c,
            0x00, 0x39, 0x2c, 0x30, 0x5d, 0x7d, 0x0a).map { it.toByte() }
        val actual = PropertyCommonConverter.encodeToMcoded7(input)
        assertContentEquals(expected, actual)
    }

    @Test
    fun decodeMcoded7() {
        val expected = "{\"foo\": [1,2,3,4,5], \"bar\": [6,7,8,9,0]}\n".toByteArray().toList()
        val input = listOf(
            0x00, 0x7b, 0x22, 0x66, 0x6f, 0x6f, 0x22, 0x3a,
            0x00, 0x20, 0x5b, 0x31, 0x2c, 0x32, 0x2c, 0x33,
            0x00, 0x2c, 0x34, 0x2c, 0x35, 0x5d, 0x2c, 0x20,
            0x00, 0x22, 0x62, 0x61, 0x72, 0x22, 0x3a, 0x20,
            0x00, 0x5b, 0x36, 0x2c, 0x37, 0x2c, 0x38, 0x2c,
            0x00, 0x39, 0x2c, 0x30, 0x5d, 0x7d, 0x0a).map { it.toByte() }
        val actual = PropertyCommonConverter.decodeMcoded7(input)
        assertContentEquals(expected, actual)
    }

    val zlibTestVector1 = listOf(
        // python3:
        //    import zlib
        //    s = "{\"foo\": [1,2,3,4,5], \"bar\": [6,7,8,9,0]}\n"
        //    b = bytes(s, "utf-8")
        //    z = zlib.compress(b)
        //    z.hex(sep=',')
        0x78, 0x9c, 0xab, 0x56, 0x4a, 0xcb, 0xcf, 0x57,
        0xb2, 0x52, 0x88, 0x36, 0xd4, 0x31, 0xd2, 0x31,
        0xd6, 0x31, 0xd1, 0x31, 0x8d, 0xd5, 0x51, 0x50,
        0x4a, 0x4a, 0x2c, 0x02, 0x89, 0x99, 0xe9, 0x98,
        0xeb, 0x58, 0xe8, 0x58, 0xea, 0x18, 0xc4, 0xd6,
        0x72, 0x01, 0x00, 0xd8, 0x15, 0x09, 0xe1).map { it.toByte() }

    val zlibTestVector2 = listOf(
        // It is not very reliable as it is only pasting binary from Mcoded7-decoded chunk of a juce_midi_ci zlib+Mcoded7 encoded blob.
        0xf8, 0x1c, 0x2b, 0x56, 0x4a, 0x4b, 0x4f, 0x57,
        0x32, 0x52, 0x08, 0x36, 0x54, 0x31, 0xd2, 0x31,
        0x56, 0x31, 0x51, 0x31, 0x0d, 0x55, 0x51, 0x50,
        0x4a, 0x4a, 0x2c, 0x02, 0x89, 0x19, 0x69, 0x18,
        0x6b, 0x58, 0x68, 0x58, 0x6a, 0x18, 0x44, 0x56,
        0x72, 0x01, 0x00, 0x58, 0x15, 0x09, 0x61).map { it.toByte() }

    // FIXME: not working
    //@Test
    fun decodeZlib() {
        val expected = "{\"foo\": [1,2,3,4,5], \"bar\": [6,7,8,9,0]}\n".toByteArray().toList()
        val input = zlibTestVector1
        val actual = PropertyCommonConverter.decodeZlib(input.toByteArray())
        assertContentEquals(expected, actual)
    }

    // FIXME: not working
    //@Test
    fun encodeZlib() {
        val input = "{\"foo\": [1,2,3,4,5], \"bar\": [6,7,8,9,0]}\n".toByteArray().toList()
        val expected = zlibTestVector1
        val actual = PropertyCommonConverter.encodeZlib(input.toByteArray())
        assertContentEquals(expected, actual)
    }
}