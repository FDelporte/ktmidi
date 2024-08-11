package dev.atsushieno.ktmidi

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

class Midi2Track(val messages: MutableList<Ump> = mutableListOf())

@OptIn(ExperimentalJsExport::class)
class Midi2Music {
    @OptIn(ExperimentalJsExport::class)
    @JsExport.Ignore
    internal class UmpDeltaTimeComputer: DeltaTimeComputer<Ump>() {
        // Note that this does not mention any "unit".
        // Therefore, in UMP Delta Clockstamps and JR Timestamps must be used *exclusively*.
        override fun messageToDeltaTime(message: Ump) =
            if (message.isDeltaClockstamp) message.deltaClockstamp else if (message.isJRTimestamp) message.jrTimestamp else 0

        override fun isTempoMessage(message: Ump) = message.isTempo

        // 3 bytes in Sysex8 pseudo meta message
        override fun getTempoValue(message: Ump) = Midi2Music.getTempoValue(message)
    }

    companion object {
        private val calc = UmpDeltaTimeComputer()

        fun filterEvents(messages: Iterable<Ump>, filter: (Ump) -> Boolean) =
            calc.filterEvents(messages, filter)

        fun getTotalPlayTimeMilliseconds(messages: Iterable<Ump>, deltaTimeSpec: Int) =
            if (deltaTimeSpec > 0)
                calc.getTotalPlayTimeMilliseconds(messages, deltaTimeSpec)
            else
                messages.filter { m -> m.isJRTimestamp }.sumOf { m -> m.jrTimestamp } / 31250

        fun getPlayTimeMillisecondsAtTick(messages: Iterable<Ump>, ticks: Int, deltaTimeSpec: Int) =
            calc.getPlayTimeMillisecondsAtTick(messages, ticks, deltaTimeSpec)

        // In our UMP format, they are stored as sysex8 messages which start with the following 8 bytes:
        // - `0` manufacture ID byte
        // - `0` device ID byte
        // - `0` sub ID byte
        // - `0` sub ID 2 byte
        // - 0xFF 0xFF 0xFF indicate our META event
        // - A meta event type byte
        // See https://github.com/atsushieno/ktmidi/blob/main/docs/MIDI2_FORMATS.md for details regarding meta events.
        fun isMetaEventMessageStarter(message: Ump) =
            message.messageType == MidiMessageType.SYSEX8_MDS &&
                when (message.statusCode) {
                    Midi2BinaryChunkStatus.COMPLETE_PACKET, Midi2BinaryChunkStatus.START ->
                        message.int1 % 0x100 == 0 &&
                                message.int2 shr 8 == 0 &&
                                message.int2 and 0xFF == 0xFF &&
                                (message.int3 shr 16) and 0xFFFF == 0xFFFF
                    else -> false // not a starter
                }

        // returns meta event type if and only if the argument message is a META event within our own specification.
        @Deprecated("It is going to be impossible to support in SMF2 so we will remove it")
        fun getMetaEventType(message: Ump) : Int = if (isMetaEventMessageStarter(message)) message.int3 shr 8 % 0x100 else 0

        @Deprecated("It is going to be impossible to support in SMF2 so we will remove it")
        fun isMetaEventMessage(message: Ump, metaType: Int) = isMetaEventMessageStarter(message) && ((message.int3 and 0xFF00) shr 8) == metaType
        fun isTempoMessage(message: Ump) = calc.isTempoMessage(message)

        fun getTempoValue(message: Ump) =
            if (isTempoMessage(message))
                ((message.int3 and 0xFF) shl 16) + ((message.int4 shr 16) and 0xFFFF)
            else throw IllegalArgumentException("Attempt to calculate tempo from non-meta UMP")
    }

    val tracks: MutableList<Midi2Track> = mutableListOf()

    // This brings in kind of hack in the UMP content.
    // When a positive value is explicitly given, then it is interpreted as the same qutantization as what SMF does
    // and the actual "ticks" in JR Timestamp messages are delta time (i.e. fake), not 1/31250 msec.
    var deltaTimeSpec: Int = 480 // Now that it must be a valid DCTPQ, give this default value.

    @Deprecated("It had never been useful. Now it only reflects isSingleTrack property value. No effect on setter.")
    var format: Int
        get() = if (isSingleTrack) 0 else 1
        set(_) {}

    val isSingleTrack: Boolean
        get() = tracks.size == 1

    fun addTrack(track: Midi2Track) {
        this.tracks.add(track)
    }

    fun filterEvents(filter: (Ump) -> Boolean): Iterable<Timed<Ump>> {
        if (tracks.size > 1)
            return mergeTracks().filterEvents(filter)
        return filterEvents(tracks[0].messages, filter).asIterable()
    }

    fun getTotalTicks(): Int {
        if (!isSingleTrack)
            return mergeTracks().getTotalTicks()
        return tracks[0].messages.filter { it.isDeltaClockstamp}.sumOf { it.deltaClockstamp }
    }

    fun getTotalPlayTimeMilliseconds(): Int {
        if (!isSingleTrack)
            return mergeTracks().getTotalPlayTimeMilliseconds()
        return getTotalPlayTimeMilliseconds(tracks[0].messages, deltaTimeSpec)
    }

    fun getTimePositionInMillisecondsForTick(ticks: Int): Int {
        if (!isSingleTrack)
            return mergeTracks().getTimePositionInMillisecondsForTick(ticks)
        return getPlayTimeMillisecondsAtTick(tracks[0].messages, ticks, deltaTimeSpec)
    }
}

fun Midi2Music.mergeTracks() : Midi2Music =
    Midi2TrackMerger(this).getMergedMessages()

internal class Midi2TrackMerger(private var source: Midi2Music) {
    internal fun getMergedMessages(): Midi2Music {
        var l = mutableListOf<Pair<Int,Ump>>()

        var jrTimestampShowedUp = false

        for (track in source.tracks) {
            var absTime = 0
            for (mev in track.messages) {
                if (mev.isDeltaClockstamp) {
                    if (jrTimestampShowedUp)
                        throw UnsupportedOperationException("The source contains both JR Timestamp and Delta Clockstamp, which is not supported.")
                    absTime += mev.deltaClockstamp
                } else if (mev.isJRTimestamp) {
                    absTime += mev.jrTimestamp
                    jrTimestampShowedUp = true
                }
                else
                    l.add(Pair(absTime, mev))
            }
        }

        if (l.size == 0)
            return Midi2Music().apply { addTrack(Midi2Track()) }

        // Simple sorter does not work as expected.
        // For example, it does not always preserve event orders on the same channels when the delta time
        // of event B after event A is 0. It could be sorted either as A->B or B->A, which is no-go for
        // MIDI messages. For example, "ProgramChange at Xmsec. -> NoteOn at Xmsec." must not be sorted as
        // "NoteOn at Xmsec. -> ProgramChange at Xmsec.".
        //
        // To resolve this ieeue, we have to sort "chunk"  of events, not all single events themselves, so
        // that order of events in the same chunk is preserved.
        // i.e. [AB] at 48 and [CDE] at 0 should be sorted as [CDE] [AB].

        val indexList = mutableListOf<Int>()
        var prev = -1
        var i = 0
        while (i < l.size) {
            if (l[i].first != prev) {
                indexList.add(i)
                prev = l[i].first
            }
            i++
        }
        val idxOrdered = indexList.sortedBy { n -> l[n].first }

        // now build a new event list based on the sorted blocks.
        val l2 = mutableListOf<Pair<Int,Ump>>()
        var idx: Int
        i = 0
        while (i < idxOrdered.size) {
            idx = idxOrdered[i]
            val absTime = l[idx].first
            while (idx < l.size && l[idx].first == absTime) {
                l2.add(l[idx])
                idx++
            }
            i++
        }
        l = l2

        // now messages should be sorted correctly.

        val l3 = mutableListOf<Ump>()
        var timeAbs = l[0].first
        i = 0
        while (i < l.size) {
            if (i + 1 < l.size) {
                val delta = l[i + 1].first - l[i].first
                if (delta > 0) {
                    if (jrTimestampShowedUp) {
                        l3.addAll(UmpFactory.jrTimestamps(delta.toLong()).map { Ump(it) })
                    } else {
                        l3.add(Ump(UmpFactory.deltaClockstamp(delta)))
                    }
                }
                timeAbs += delta
            }
            l3.add(l[i].second)
            i++
        }

        val m = Midi2Music()
        m.deltaTimeSpec = source.deltaTimeSpec
        m.tracks.add(Midi2Track(l3))
        return m
    }
}

fun Midi2Track.splitTracksByChannel() : Midi2Music =
    Midi2TrackSplitter(messages).split()

open class Midi2TrackSplitter(private val source: MutableList<Ump>) {
    companion object {
        fun split(source: MutableList<Ump>): Midi2Music {
            return Midi2TrackSplitter(source).split()
        }
    }

    private var tracks = HashMap<Int, SplitTrack>()

    internal class SplitTrack(val trackID: Int) {

        private var currentTimestamp: Int
        val track = Midi2Track()

        fun addMessage(timestampInsertAt: Int, e: Ump) {
            if (currentTimestamp != timestampInsertAt) {
                val delta = timestampInsertAt - currentTimestamp
                track.messages.add(Ump(UmpFactory.deltaClockstamp(delta)))
            }
            track.messages.add(e)
            currentTimestamp = timestampInsertAt
        }

        init {
            currentTimestamp = 0
        }
    }

    private fun getTrack(track: Int): SplitTrack {
        var t = tracks[track]
        if (t == null) {
            t = SplitTrack(track)
            tracks[track] = t
        }
        return t
    }

    // Override it to customize track dispatcher. It would be
    // useful to split note messages out from non-note ones,
    // to ease data reading.
    open fun getTrackId(e: Ump) = e.groupAndChannel

    fun split(): Midi2Music {
        var totalDeltaTime = 0
        var jrTimestampShowedUp = false
        for (e in source) {
            if (e.isDeltaClockstamp) {
                if (jrTimestampShowedUp)
                    throw UnsupportedOperationException("The source contains both JR Timestamp and Delta Clockstamp, which is not supported.")
                totalDeltaTime += e.deltaClockstamp
            } else if (e.isJRTimestamp) {
                jrTimestampShowedUp = true
                totalDeltaTime += e.jrTimestamp
            }
            val id: Int = getTrackId(e)
            getTrack(id).addMessage(totalDeltaTime, e)
        }

        val m = Midi2Music()
        for (t in tracks.values)
            m.tracks.add(t.track)
        return m
    }

    init {
        val mtr = SplitTrack(-1)
        tracks[-1] = mtr
    }
}

@Deprecated("We do not emit JR Timestamp for deltaTime anymore, so it is unnecessary")
fun Midi2Music.convertDeltaTimesToJRTimestamps() : Midi2Music =
    if (deltaTimeSpec > 0) Midi2DeltaTimeConverter(this).deltaTimetoJRTimestamp() else this

internal class Midi2DeltaTimeConverter internal constructor(private val source: Midi2Music) {
    @Deprecated("We do not emit JR Timestamp for deltaTime anymore, so it is unnecessary")
    fun deltaTimetoJRTimestamp() : Midi2Music {
        val result = Midi2Music().apply {
            deltaTimeSpec = 0
        }

        val dtc = Midi2Music.UmpDeltaTimeComputer()

        // first, get all meta events to detect tempo changes for later uses.
        val allTempoChanges = source.filterEvents { it.isTempo }.toList()

        for (srcTrack in source.tracks) {
            val dstTrack = Midi2Track()
            result.addTrack(dstTrack)

            var currentTicks = 0
            var currentTempo = Midi1Music.DEFAULT_TEMPO
            var nextTempoChangeIndex = 0

            for (ump in srcTrack.messages) {
                if (ump.isJRTimestamp) {
                    // There may be tempo changes in other tracks, which affects the total length of a
                    // timestamp message when it is converted to milliseconds. We have to calculate it
                    // taking those tempo changes into consideration.
                    var remainingTicks = ump.jrTimestamp
                    var durationMs = 0.0
                    while (nextTempoChangeIndex < allTempoChanges.size && allTempoChanges[nextTempoChangeIndex].duration.value < currentTicks + remainingTicks) {
                        val ticksUntilNextTempoChange = allTempoChanges[nextTempoChangeIndex].duration.value - currentTicks
                        durationMs += getContextDeltaTimeInMilliseconds(ticksUntilNextTempoChange, currentTempo, source.deltaTimeSpec)
                        currentTempo = dtc.getTempoValue(allTempoChanges[nextTempoChangeIndex].value)
                        nextTempoChangeIndex++
                        remainingTicks -= ticksUntilNextTempoChange
                        currentTicks += ticksUntilNextTempoChange
                    }
                    durationMs += getContextDeltaTimeInMilliseconds(remainingTicks, currentTempo, source.deltaTimeSpec)
                    currentTicks += remainingTicks
                    dstTrack.messages.addAll(UmpFactory.jrTimestamps(durationMs / 1000.0).map { i -> Ump(i)})
                }
                else
                    dstTrack.messages.add(ump)
            }
        }

        return result
    }

    private fun getContextDeltaTimeInMilliseconds(ticksUntilNextTempoChange: Int, currentTempo: Int, deltaTimeSpec: Int) : Double =
        currentTempo.toDouble() / 1000 * ticksUntilNextTempoChange / deltaTimeSpec
}
