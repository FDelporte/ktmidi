package dev.atsushieno.ktmidi

import kotlin.math.min

typealias SmfMetaEventWriter = (Boolean, Midi1Event, MutableList<Byte>) -> Int

fun Midi1Music.write(
    stream: MutableList<Byte>,
    metaEventWriter: SmfMetaEventWriter = Midi1WriterExtension.defaultMetaEventWriter,
    disableRunningStatus: Boolean = false
) {
    val w = Midi1Writer(stream, metaEventWriter)
    w.disableRunningStatus = disableRunningStatus
    w.writeMusic(this)
}

internal class Midi1Writer(
    private val stream: MutableList<Byte>,
    private var metaEventWriter: SmfMetaEventWriter = Midi1WriterExtension.defaultMetaEventWriter
) {
    var disableRunningStatus: Boolean = false

    private fun writeShort(v: Short) {
        stream.add((v / 0x100).toByte())
        stream.add((v % 0x100).toByte())
    }

    private fun writeInt(v: Int) {
        stream.add((v / 0x1000000).toByte())
        stream.add((v / 0x10000 and 0xFF).toByte())
        stream.add((v / 0x100 and 0xFF).toByte())
        stream.add((v % 0x100).toByte())
    }

    fun writeMusic(music: Midi1Music) {
        writeHeader(music.format.toShort(), music.tracks.size.toShort(), music.deltaTimeSpec.toShort())
        for (track in music.tracks)
            writeTrack(track)
    }

    fun writeHeader(format: Short, tracks: Short, deltaTimeSpec: Short) {
        stream.add('M'.code.toByte())
        stream.add('T'.code.toByte())
        stream.add('h'.code.toByte())
        stream.add('d'.code.toByte())
        writeShort(0)
        writeShort(6)
        writeShort(format)
        writeShort(tracks)
        writeShort(deltaTimeSpec)
    }

    fun writeTrack(track: Midi1Track) {
        stream.add('M'.code.toByte())
        stream.add('T'.code.toByte())
        stream.add('r'.code.toByte())
        stream.add('k'.code.toByte())
        writeInt(getTrackDataSize(track))

        var runningStatus: Byte = 0
        var wroteEndOfTrack = false

        for (e in track.events) {
            write7bitEncodedInt(e.deltaTime)
            when (e.message.statusCode.toUnsigned()) {
                Midi1Status.META -> {
                    metaEventWriter(false, e, stream)
                    if (e.message.metaType == MidiMetaType.END_OF_TRACK.toByte())
                        wroteEndOfTrack = true
                }
                Midi1Status.SYSEX, Midi1Status.SYSEX_END -> {
                    val s = e.message as Midi1CompoundMessage
                    stream.add(e.message.statusCode)
                    val seBytes = s.extraData
                    if (seBytes == null)
                        write7bitEncodedInt(0)
                    else {
                        write7bitEncodedInt(s.extraDataLength)
                        if (s.extraDataLength > 0)
                            stream.addAll(seBytes.drop(s.extraDataOffset).take(s.extraDataLength))
                    }
                }
                else -> {
                    if (disableRunningStatus || e.message.statusByte != runningStatus)
                        stream.add(e.message.statusByte)
                    val len = Midi1Message.fixedDataSize(e.message.statusCode)
                    stream.add(e.message.msb)
                    if (len > 1)
                        stream.add(e.message.lsb)
                    if (len > 2)
                        throw Exception("Unexpected data size: $len")
                }
            }
            runningStatus = e.message.statusByte
        }
        if (!wroteEndOfTrack)
        // deltaTime, meta status = 0xFF, size-of-meta = 0, end-of-track = 0x2F
            stream.addAll(sequenceOf(0, 0xFF.toByte(), 0x2F, 0))
    }

    private fun get7BitEncodedLength(value: Int): Int {
        if (value < 0)
            throw IllegalArgumentException("Length must be non-negative integer: $value")
        if (value == 0)
            return 1
        var ret = 0
        var x: Int = value
        while (x != 0) {
            ret++
            x = x shr 7
        }
        return ret
    }

    private fun getTrackDataSize(track: Midi1Track): Int {
        var size = 0
        var runningStatus: Byte = 0
        var wroteEndOfTrack = false
        for (e in track.events) {
            // delta time
            size += get7BitEncodedLength(e.deltaTime)

            // arguments
            when (e.message.statusCode.toUnsigned()) {
                Midi1Status.META -> {
                    size += metaEventWriter(true, e, mutableListOf())
                    if (e.message.metaType == MidiMetaType.END_OF_TRACK.toByte())
                        wroteEndOfTrack = true
                }
                Midi1Status.SYSEX, Midi1Status.SYSEX_END -> {
                    size++
                    val s = e.message as Midi1CompoundMessage
                    if (s.extraData != null) {
                        size += get7BitEncodedLength(s.extraDataLength)
                        size += s.extraDataLength
                    }
                }
                else -> {
                    // message type & channel
                    if (disableRunningStatus || runningStatus != e.message.statusByte)
                        size++
                    size += Midi1Message.fixedDataSize(e.message.statusCode)
                }
            }

            runningStatus = e.message.statusByte
        }
        if (!wroteEndOfTrack)
            size += 4
        return size
    }

    private fun write7bitEncodedInt(value: Int) {
        write7bitEncodedInt(value, false)
    }

    private fun write7bitEncodedInt(value: Int, shifted: Boolean) {
        if (value == 0) {
            stream.add((if (shifted) 0x80 else 0).toByte())
            return
        }
        if (value >= 0x80)
            write7bitEncodedInt(value shr 7, true)
        stream.add(((value and 0x7F) + if (shifted) 0x80 else 0).toByte())
    }
}

object Midi1WriterExtension {
    val defaultMetaEventWriter: SmfMetaEventWriter =
        { b, m, o -> defaultMetaWriterFunc(b, m, o) }

    private fun defaultMetaWriterFunc(onlyCountLength: Boolean, e: Midi1Event, stream: MutableList<Byte>): Int {
        val msg = e.message
        if (msg !is Midi1CompoundMessage)
            return 0
        if (onlyCountLength) {
            // [0x00] 0xFF metaType size ... (note that for more than one meta event it requires step count of 0).
            val repeatCount: Int = msg.extraDataLength / 0x7F
            if (repeatCount == 0)
                return 3 + msg.extraDataLength
            val mod: Int = msg.extraDataLength % 0x7F
            return repeatCount * (4 + 0x7F) - 1 + if (mod > 0) 4 + mod else 0
        }

        var written = 0
        val total: Int = msg.extraDataLength
        var passed = false // manually rewritten do-while loop...
        while (!passed || written < total) {
            passed = true
            if (written > 0)
                stream.add(0.toByte()) // step
            stream.add(0xFF.toByte())
            stream.add(msg.metaType)
            val size = min(0x7F, total - written)
            stream.add(size.toByte())
            val offset = msg.extraDataOffset + written
            if (size > 0)
                stream.addAll(msg.extraData!!.slice(IntRange(offset, offset + size - 1)))
            written += size
        }
        return 0
    }
}

fun Midi1Music.read(stream: List<Byte>) {
    val r = Midi1Reader(this, stream)
    r.readMusic()
}

internal class Reader(private val stream: List<Byte>, private var index: Int) {
    fun canRead(): Boolean = index < stream.size
    fun read(dst: ByteArray, startOffset: Int, endOffsetInclusive: Int): Int {
        val len = min(stream.size - index, endOffsetInclusive - startOffset)
        if (len > 0) {
            stream.subList(index, index + len).toByteArray().copyInto(dst, startOffset, 0, len)
            index += len
        }
        return len
    }

    val position = index
    fun peekByte(): Byte = stream[index]
    fun readByte(): Byte = stream[index++]
}

internal class Midi1Reader(val music: Midi1Music, stream: List<Byte>) {

    private val reader: Reader = Reader(stream, 0)

    private val data = music

    fun readMusic() {
        if (readByte() != 'M'.code.toByte()
            || readByte() != 'T'.code.toByte()
            || readByte() != 'h'.code.toByte()
            || readByte() != 'd'.code.toByte()
        )
            throw parseError("MThd is expected")
        if (readInt32() != 6)
            throw parseError("Unexpected data size (should be 6)")
        data.format = readInt16().toByte()
        val tracks = readInt16()
        data.deltaTimeSpec = readInt16().toInt()
        for (i in 0 until tracks)
            data.tracks.add(readTrack())
    }

    private fun readTrack(): Midi1Track {
        val tr = Midi1Track()
        if (
            readByte() != 'M'.code.toByte()
            || readByte() != 'T'.code.toByte()
            || readByte() != 'r'.code.toByte()
            || readByte() != 'k'.code.toByte()
        )
            throw parseError("MTrk is expected")
        val trackSize = readInt32()
        currentTrackSize = 0
        var total = 0
        while (currentTrackSize < trackSize) {
            val delta = readVariableLength()
            tr.events.add(readEvent(delta))
            total += delta
        }
        if (currentTrackSize != trackSize)
            throw parseError("Size information mismatch")
        return tr
    }

    private var currentTrackSize: Int = 0
    private var runningStatus: Int = 0

    private fun readEvent(deltaTime: Int): Midi1Event {
        val b = peekByte().toUnsigned()
        runningStatus = if (b < 0x80) runningStatus else readByte().toUnsigned()
        val len: Int
        when (runningStatus) {
            Midi1Status.SYSEX, Midi1Status.SYSEX_END -> {
                len = readVariableLength()
                val args = ByteArray(len)
                if (len > 0)
                    readBytes(args)
                return Midi1Event(deltaTime, Midi1CompoundMessage(runningStatus, 0, 0, args, 0, len))
            }
            Midi1Status.META -> {
                val metaType = readByte()
                len = readVariableLength()
                val args = ByteArray(len)
                if (len > 0)
                    readBytes(args)
                return Midi1Event(deltaTime, Midi1CompoundMessage(runningStatus, metaType.toUnsigned(), 0, args, 0, len))
            }
            else -> {
                var value = runningStatus
                value += readByte().toUnsigned() shl 8
                if (Midi1Message.fixedDataSize(runningStatus.toByte()) == 2.toByte())
                    value += readByte().toUnsigned() shl 16
                return Midi1Event(deltaTime, Midi1SimpleMessage(value))
            }
        }
    }

    private fun readBytes(args: ByteArray) {
        currentTrackSize += args.size
        var start = 0
        val len = reader.read(args, start, start + args.size)
        if (len < args.size - start)
            throw parseError("The stream is insufficient to read ${args.size} bytes specified in the SMF message. Only $len bytes read.")
    }

    private fun readVariableLength(): Int {
        var v = 0
        var i = 0
        while (i < 4) {
            val b = readByte().toUnsigned()
            v = (v shl 7) + b
            if (b < 0x80)
                return v
            v -= 0x80
            i++
        }
        throw parseError("Delta time specification exceeds the 4-byte limitation.")
    }

    private fun peekByte(): Byte {
        if (!reader.canRead())
            throw parseError("Insufficient stream. Failed to read a byte.")
        return reader.peekByte()
    }

    private fun readByte(): Byte {
        currentTrackSize++
        if (!reader.canRead())
            throw parseError("Insufficient stream. Failed to read a byte.")
        return reader.readByte()
    }

    private fun readInt16(): Short {
        return ((readByte().toUnsigned() shl 8) + readByte().toUnsigned()).toShort()
    }

    private fun readInt32(): Int {
        return (((readByte().toUnsigned() shl 8) + readByte().toUnsigned() shl 8) + readByte().toUnsigned() shl 8) + readByte().toUnsigned()
    }

    private fun parseError(msg: String): Exception {
        return parseError(msg, null)
    }

    private fun parseError(msg: String, innerException: Exception?): Exception {
        if (innerException == null)
            throw SmfParserException("$msg (at ${reader.position})")
        else
            throw SmfParserException("$msg (at ${reader.position})", innerException)
    }
}

class SmfParserException : Exception {
    constructor () : this("SMF parser error")
    constructor (message: String) : super(message)
    constructor (message: String, innerException: Exception) : super(message, innerException)
}

