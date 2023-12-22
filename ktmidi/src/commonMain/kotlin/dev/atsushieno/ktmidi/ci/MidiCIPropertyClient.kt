package dev.atsushieno.ktmidi.ci

/**
 * To make sense implementing Property Exchange, but to not limit to Common Rules for PE,
 * we have extra set of requirements to support property meta system:
 *
 * - Every property header byte array must be able to provide one single property ID
 * - The system can provide a list of property IDs. It is asynchronous
 */
interface MidiCIPropertyClient {

    fun getPropertyIdForHeader(header: List<Byte>): String

    fun getPropertyIds(): List<String>?

    suspend fun requestPropertyIds(destinationMUID: Int, requestId: Byte)

    fun onGetPropertyDataReply(msg: Message.GetPropertyDataReply)
}