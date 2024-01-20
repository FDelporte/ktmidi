package dev.atsushieno.ktmidi.ci

import dev.atsushieno.ktmidi.ci.propertycommonrules.CommonRulesPropertyClient
import dev.atsushieno.ktmidi.ci.propertycommonrules.PropertyCommonHeaderKeys
import dev.atsushieno.ktmidi.ci.propertycommonrules.PropertyExchangeStatus
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/*
    Typical MIDI-CI processing flow

    - MidiCIInitiator.sendDiscovery()
    - MidiCIResponder.processInput() -> .processDiscovery
    - MidiCIInitiator.sendEndpointMessage(), .requestProfiles(), .requestPropertyExchangeCapabilities()
    - MidiCIResponder receives and processes each of the replies ...

 The argument `sendOutput` takes a sysex bytestream which is NOT specific to MIDI 1.0 bytestream (i.e. it should
 support sysex7 UMPs) and thus does NOT contain F0 and F7.
 Same goes for `processInput()` function.

*/

class MidiCIInitiator(
    val parent: MidiCIDevice,
    val config: MidiCIInitiatorConfiguration,
    private val sendOutput: (group: Byte, data: List<Byte>) -> Unit
) {
    val muid by parent::muid
    val device by parent::device
    val events by parent::events
    val logger by parent::logger

    enum class SubscriptionActionState {
        Subscribing,
        Subscribed,
        Unsubscribing,
        Unsubscribed
    }
    data class ClientSubscription(var pendingRequestId: Byte?, var subscriptionId: String?, val propertyId: String, var state: SubscriptionActionState)

    class ClientConnection(
        private val parent: MidiCIInitiator,
        val targetMUID: Int,
        val device: DeviceDetails,
        var maxSimultaneousPropertyRequests: Byte = 0,
        var productInstanceId: String = "",
        val propertyClient: MidiCIPropertyClient = CommonRulesPropertyClient(parent.logger, parent.muid) { msg -> parent.sendGetPropertyData(msg) }
    ) {

        val profiles = ObservableProfileList(mutableListOf())

        val properties = ClientObservablePropertyList(parent.logger, propertyClient)

        private val openRequests = mutableListOf<Message.GetPropertyData>()
        val subscriptions = mutableListOf<ClientSubscription>()
        val subscriptionUpdated = mutableListOf<(sub: ClientSubscription)->Unit>()

        val pendingChunkManager = PropertyChunkManager()

        fun updateProperty(msg: Message.GetPropertyDataReply) {
            val req = openRequests.firstOrNull { it.requestId == msg.requestId } ?: return
            openRequests.remove(req)
            val status = propertyClient.getHeaderFieldInteger(msg.header, PropertyCommonHeaderKeys.STATUS) ?: return

            if (status == PropertyExchangeStatus.OK) {
                propertyClient.onGetPropertyDataReply(req, msg)
                val propertyId = propertyClient.getPropertyIdForHeader(req.header)
                properties.updateValue(propertyId, msg)
            }
        }

        fun updateProperty(ourMUID: Int, msg: Message.SubscribeProperty): Pair<String?,Message.SubscribePropertyReply?> {
            val command = properties.updateValue(msg)
            return Pair(
                command,
                Message.SubscribePropertyReply(ourMUID, msg.sourceMUID, msg.requestId,
                    propertyClient.createStatusHeader(PropertyExchangeStatus.OK), listOf()
                )
            )
        }

        fun addPendingRequest(msg: Message.GetPropertyData) {
            openRequests.add(msg)
        }
        fun addPendingSubscription(requestId: Byte, subscriptionId: String?, propertyId: String) {
            val sub = ClientSubscription(requestId, subscriptionId, propertyId, SubscriptionActionState.Subscribing)
            subscriptions.add(sub)
            subscriptionUpdated.forEach { it(sub) }
        }

        fun promoteSubscriptionAsUnsubscribing(propertyId: String, newRequestId: Byte) {
            val sub = subscriptions.firstOrNull { it.propertyId == propertyId }
            if (sub == null) {
                parent.logger.logError("Cannot unsubscribe property as not found: $propertyId")
                return
            }
            if (sub.state == SubscriptionActionState.Unsubscribing) {
                parent.logger.logError("Unsubscription for the property is already underway (property: ${sub.propertyId}, subscriptionId: ${sub.subscriptionId}, state: ${sub.state})")
                return
            }
            sub.pendingRequestId = newRequestId
            sub.state = SubscriptionActionState.Unsubscribing
            subscriptionUpdated.forEach { it(sub) }
        }

        fun processPropertySubscriptionReply(msg: Message.SubscribePropertyReply) {
            val subscriptionId = propertyClient.getHeaderFieldString(msg.header, PropertyCommonHeaderKeys.SUBSCRIBE_ID)
            if (subscriptionId == null) {
                parent.logger.logError("Subscription ID is missing in the Reply to Subscription message. requestId: ${msg.requestId}")
                if (!ImplementationSettings.workaroundJUCEMissingSubscriptionIdIssue)
                    return
            }
            val sub = subscriptions.firstOrNull { subscriptionId == it.subscriptionId }
                ?: subscriptions.firstOrNull { it.pendingRequestId == msg.requestId }
            if (sub == null) {
                parent.logger.logError("There was no pending subscription that matches subscribeId ($subscriptionId) or requestId (${msg.requestId})")
                return
            }

            when (sub.state) {
                SubscriptionActionState.Subscribed,
                SubscriptionActionState.Unsubscribed -> {
                    parent.logger.logError("Received Subscription Reply, but it is unexpected (property: ${sub.propertyId}, subscriptionId: ${sub.subscriptionId}, state: ${sub.state})")
                    return
                }
                else -> {}
            }

            sub.subscriptionId = subscriptionId

            propertyClient.processPropertySubscriptionResult(sub, msg)

            if (sub.state == SubscriptionActionState.Unsubscribing) {
                // do unsubscribe
                sub.state = SubscriptionActionState.Unsubscribed
                subscriptions.remove(sub)
                subscriptionUpdated.forEach { it(sub) }
            } else {
                sub.state = SubscriptionActionState.Subscribed
                subscriptionUpdated.forEach { it(sub) }
            }
        }
    }

    val connections by parent::connections
    val connectionsChanged by parent::connectionsChanged

    // Initiator implementation

    fun sendEndpointMessage(targetMuid: Int, status: Byte = MidiCIConstants.ENDPOINT_STATUS_PRODUCT_INSTANCE_ID) =
        sendEndpointMessage(Message.EndpointInquiry(muid, targetMuid, status))

    fun sendEndpointMessage(msg: Message.EndpointInquiry) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIEndpointMessage(buf, MidiCIConstants.CI_VERSION_AND_FORMAT,
            msg.sourceMUID, msg.destinationMUID, msg.status))
    }

    // Profile Configuration

    fun requestProfiles(destinationChannelOr7F: Byte, destinationMUID: Int) =
        requestProfiles(Message.ProfileInquiry(destinationChannelOr7F, muid, destinationMUID))

    fun requestProfiles(msg: Message.ProfileInquiry) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIProfileInquiry(buf, msg.address, msg.sourceMUID, msg.destinationMUID))
    }

    fun setProfileOn(msg: Message.SetProfileOn) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIProfileSet(buf, msg.address, true, msg.sourceMUID, msg.destinationMUID, msg.profile, msg.numChannelsRequested))
    }

    fun setProfileOff(msg: Message.SetProfileOff) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIProfileSet(buf, msg.address, false, msg.sourceMUID, msg.destinationMUID, msg.profile, 0))
    }

    fun requestProfileDetails(address: Byte, muid: Int, profile: MidiCIProfileId, target: Byte) =
        requestProfileDetails(Message.ProfileDetailsInquiry(address, this.muid, muid, profile, target))

    fun requestProfileDetails(msg: Message.ProfileDetailsInquiry) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIProfileDetails(buf, msg.address, msg.sourceMUID, msg.destinationMUID, msg.profile, 0))
    }

    // Property Exchange

    fun requestPropertyExchangeCapabilities(address: Byte, destinationMUID: Int, maxSimultaneousPropertyRequests: Byte) =

        requestPropertyExchangeCapabilities(Message.PropertyGetCapabilities(address, muid, destinationMUID, maxSimultaneousPropertyRequests))

    fun requestPropertyExchangeCapabilities(msg: Message.PropertyGetCapabilities) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIPropertyGetCapabilities(
            buf,
            msg.address,
            false,
            msg.sourceMUID,
            msg.destinationMUID,
            msg.maxSimultaneousRequests
        ))
    }

    fun sendGetPropertyData(destinationMUID: Int, resource: String, encoding: String?) {
        val conn = connections[destinationMUID]
        if (conn != null) {
            val header = conn.propertyClient.createDataRequestHeader(resource, mapOf(
                PropertyCommonHeaderKeys.MUTUAL_ENCODING to encoding,
                PropertyCommonHeaderKeys.SET_PARTIAL to false))
            val msg = Message.GetPropertyData(muid, destinationMUID, requestIdSerial++, header)
            sendGetPropertyData(msg)
        }
    }

    fun sendGetPropertyData(msg: Message.GetPropertyData) {
        logger.logMessage(msg, MessageDirection.Out)
        val conn = connections[msg.destinationMUID]
        conn?.addPendingRequest(msg)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        CIFactory.midiCIPropertyChunks(buf, parent.config.maxPropertyChunkSize, CISubId2.PROPERTY_GET_DATA_INQUIRY,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, listOf()).forEach {
            sendOutput(msg.group, it)
        }
    }

    fun sendSetPropertyData(destinationMUID: Int, resource: String, data: List<Byte>, encoding: String? = null, isPartial: Boolean = false) {
        val conn = connections[destinationMUID]
        if (conn != null) {
            val header = conn.propertyClient.createDataRequestHeader(resource, mapOf(
                PropertyCommonHeaderKeys.MUTUAL_ENCODING to encoding,
                PropertyCommonHeaderKeys.SET_PARTIAL to isPartial))
            val encodedBody = conn.propertyClient.encodeBody(data, encoding)
            sendSetPropertyData(Message.SetPropertyData(muid, destinationMUID, requestIdSerial++, header, encodedBody))
        }
    }

    fun sendSetPropertyData(msg: Message.SetPropertyData) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        CIFactory.midiCIPropertyChunks(buf, parent.config.maxPropertyChunkSize, CISubId2.PROPERTY_SET_DATA_INQUIRY,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, msg.body).forEach {
            sendOutput(msg.group, it)
        }
    }

    fun sendSubscribeProperty(destinationMUID: Int, resource: String, mutualEncoding: String?, subscriptionId: String? = null) {
        val conn = connections[destinationMUID]
        if (conn != null) {
            val header = conn.propertyClient.createSubscriptionHeader(resource, mapOf(
                PropertyCommonHeaderKeys.COMMAND to MidiCISubscriptionCommand.START,
                PropertyCommonHeaderKeys.MUTUAL_ENCODING to mutualEncoding))
            val msg = Message.SubscribeProperty(muid, destinationMUID, requestIdSerial++, header, listOf())
            conn.addPendingSubscription(msg.requestId, subscriptionId, resource)
            sendSubscribeProperty(msg)
        }
    }

    fun sendUnsubscribeProperty(destinationMUID: Int, resource: String, mutualEncoding: String?, subscriptionId: String? = null) {
        val conn = connections[destinationMUID]
        if (conn != null) {
            val newRequestId = requestIdSerial++
            val header = conn.propertyClient.createSubscriptionHeader(resource, mapOf(
                PropertyCommonHeaderKeys.COMMAND to MidiCISubscriptionCommand.END,
                PropertyCommonHeaderKeys.MUTUAL_ENCODING to mutualEncoding))
            val msg = Message.SubscribeProperty(muid, destinationMUID, newRequestId, header, listOf())
            conn.promoteSubscriptionAsUnsubscribing(resource, newRequestId)
            sendSubscribeProperty(msg)
        }
    }

    fun sendSubscribeProperty(msg: Message.SubscribeProperty) {
        logger.logMessage(msg, MessageDirection.Out)
        val dst = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        CIFactory.midiCIPropertyChunks(
            dst, parent.config.maxPropertyChunkSize, CISubId2.PROPERTY_SUBSCRIBE,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, msg.body
        ).forEach {
            sendOutput(msg.group, it)
        }
    }

    // Process Inquiry
    fun sendProcessInquiry(destinationMUID: Int) =
        sendProcessInquiry(Message.ProcessInquiry(muid, destinationMUID))

    fun sendProcessInquiry(msg: Message.ProcessInquiry) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIProcessInquiryCapabilities(buf, msg.sourceMUID, msg.destinationMUID))
    }

    fun sendMidiMessageReportInquiry(address: Byte, destinationMUID: Int,
                                     messageDataControl: Byte,
                                     systemMessages: Byte,
                                     channelControllerMessages: Byte,
                                     noteDataMessages: Byte) =
        sendMidiMessageReportInquiry(Message.MidiMessageReportInquiry(
            address, muid, destinationMUID, messageDataControl, systemMessages, channelControllerMessages, noteDataMessages))

    fun sendMidiMessageReportInquiry(msg: Message.MidiMessageReportInquiry) {
        logger.logMessage(msg, MessageDirection.Out)
        val buf = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        sendOutput(msg.group, CIFactory.midiCIMidiMessageReport(buf, msg.address, msg.sourceMUID, msg.destinationMUID,
            msg.messageDataControl, msg.systemMessages, msg.channelControllerMessages, msg.noteDataMessages))
    }

    // Miscellaneous

    private var requestIdSerial: Byte = 1

    // Reply handler

    // Protocol Negotiation is deprecated. We do not send any of them anymore.

    // Profile Configuration
    val defaultProcessProfileReply = { msg: Message.ProfileReply ->
        val conn = connections[msg.sourceMUID]
        msg.enabledProfiles.forEach { conn?.profiles?.add(MidiCIProfile(it, msg.group, msg.address, true, if (msg.address >= 0x7E) 0 else 1)) }
        msg.disabledProfiles.forEach { conn?.profiles?.add(MidiCIProfile(it, msg.group, msg.address, false, if (msg.address >= 0x7E) 0 else 1)) }
    }
    var processProfileReply = { msg: Message.ProfileReply ->
        logger.logMessage(msg, MessageDirection.In)
        events.profileInquiryReplyReceived.forEach { it(msg) }
        defaultProcessProfileReply(msg)
    }

    val defaultProcessProfileAddedReport: (msg: Message.ProfileAdded) -> Unit = { msg ->
        val conn = connections[msg.sourceMUID]
        conn?.profiles?.add(MidiCIProfile(msg.profile, msg.group, msg.address, false, if (msg.address >= 0x7E) 0 else 1))
    }
    var processProfileAddedReport = { msg: Message.ProfileAdded ->
        logger.logMessage(msg, MessageDirection.In)
        events.profileAddedReceived.forEach { it(msg) }
        defaultProcessProfileAddedReport(msg)
    }

    val defaultProcessProfileRemovedReport: (msg: Message.ProfileRemoved) -> Unit = { msg ->
        val conn = connections[msg.sourceMUID]
        conn?.profiles?.remove(MidiCIProfile(msg.profile, msg.group, msg.address, false, 0))
    }
    var processProfileRemovedReport: (msg: Message.ProfileRemoved) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.profileRemovedReceived.forEach { it(msg) }
        defaultProcessProfileRemovedReport(msg)
    }

    fun defaultProcessProfileEnabledReport(msg: Message.ProfileEnabled) {
        val conn = connections[msg.sourceMUID]
        conn?.profiles?.setEnabled(true, msg.address, msg.profile, msg.numChannelsRequested)
    }
    fun defaultProcessProfileDisabledReport(msg: Message.ProfileDisabled) {
        val conn = connections[msg.sourceMUID]
        conn?.profiles?.setEnabled(false, msg.address, msg.profile, msg.numChannelsRequested)
    }
    var processProfileEnabledReport: (msg: Message.ProfileEnabled) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.profileEnabledReceived.forEach { it(msg) }
        defaultProcessProfileEnabledReport(msg)
    }
    var processProfileDisabledReport: (msg: Message.ProfileDisabled) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.profileDisabledReceived.forEach { it(msg) }
        defaultProcessProfileDisabledReport(msg)
    }

    var processProfileDetailsReply: (msg: Message.ProfileDetailsReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.profileDetailsReplyReceived.forEach { it(msg) }
        // nothing to perform - use events if you need anything further
    }

    // Property Exchange
    @OptIn(DelicateCoroutinesApi::class)
    val defaultProcessPropertyCapabilitiesReply: (msg: Message.PropertyGetCapabilitiesReply) -> Unit = { msg ->
        val conn = connections[msg.sourceMUID]
        if (conn != null) {
            conn.maxSimultaneousPropertyRequests = msg.maxSimultaneousRequests

            // proceed to query resource list
            if (config.autoSendGetResourceList)
                GlobalScope.launch {
                    conn.propertyClient.requestPropertyList(msg.sourceMUID, requestIdSerial++)
                }
        }
        else
            parent.sendNakForUnknownMUID(msg.group, CISubId2.PROPERTY_CAPABILITIES_REPLY, msg.address, msg.sourceMUID)
    }
    var processPropertyCapabilitiesReply: (msg: Message.PropertyGetCapabilitiesReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.propertyCapabilityReplyReceived.forEach { it(msg) }
        defaultProcessPropertyCapabilitiesReply(msg)
    }

    val defaultProcessGetDataReply: (msg: Message.GetPropertyDataReply) -> Unit = { msg ->
        connections[msg.sourceMUID]?.updateProperty(msg)
    }

    var processGetDataReply: (msg: Message.GetPropertyDataReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.getPropertyDataReplyReceived.forEach { it(msg) }
        defaultProcessGetDataReply(msg)
    }

    var processSetDataReply: (msg: Message.SetPropertyDataReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.setPropertyDataReplyReceived.forEach { it(msg) }
        // nothing to delegate further
    }

    fun sendPropertySubscribeReply(msg: Message.SubscribePropertyReply) {
        logger.logMessage(msg, MessageDirection.Out)
        val dst = MutableList<Byte>(parent.config.receivableMaxSysExSize) { 0 }
        CIFactory.midiCIPropertyChunks(
            dst, parent.config.maxPropertyChunkSize, CISubId2.PROPERTY_SUBSCRIBE_REPLY,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, msg.body
        ).forEach {
            sendOutput(msg.group, it)
        }
    }
    var processSubscribeProperty: (msg: Message.SubscribeProperty) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.subscribePropertyReceived.forEach { it(msg) }
        val conn = connections[msg.sourceMUID]
        if (conn != null) {
            val reply = conn.updateProperty(muid, msg)
            if (reply.second != null)
                sendPropertySubscribeReply(reply.second!!)
            // If the update was NOTIFY, then it is supposed to send Get Data request.
            if (reply.first == MidiCISubscriptionCommand.NOTIFY)
                sendGetPropertyData(msg.sourceMUID, conn.propertyClient.getPropertyIdForHeader(msg.header), null) // is there mutualEncoding from SubscribeProperty?
        }
        else
            // Unknown MUID - send back NAK
            parent.sendNakForUnknownMUID(msg.group, CISubId2.PROPERTY_SUBSCRIBE, msg.address, msg.sourceMUID)
    }

    fun defaultProcessSubscribePropertyReply(msg: Message.SubscribePropertyReply) {
        val conn = connections[msg.sourceMUID]
        if (conn != null) {
            if (conn.propertyClient.getHeaderFieldInteger(msg.header, PropertyCommonHeaderKeys.STATUS) == PropertyExchangeStatus.OK)
                conn.processPropertySubscriptionReply(msg)
        }
    }
    var processSubscribePropertyReply: (msg: Message.SubscribePropertyReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.subscribePropertyReplyReceived.forEach { it(msg) }
        defaultProcessSubscribePropertyReply(msg)
    }

    // Process Inquiry
    var processProcessInquiryReply: (msg: Message.ProcessInquiryReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.processInquiryReplyReceived.forEach { it(msg) }
        // no particular things to do. Event handlers should be used if any.
    }

    var processMidiMessageReportReply: (msg: Message.MidiMessageReportReply) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.midiMessageReportReplyReceived.forEach { it(msg) }
        // no particular things to do. Event handlers should be used if any.
    }

    var processEndOfMidiMessageReport: (msg: Message.MidiMessageReportNotifyEnd) -> Unit = { msg ->
        logger.logMessage(msg, MessageDirection.In)
        events.endOfMidiMessageReportReceived.forEach { it(msg) }
        // no particular things to do. Event handlers should be used if any.
    }
}
