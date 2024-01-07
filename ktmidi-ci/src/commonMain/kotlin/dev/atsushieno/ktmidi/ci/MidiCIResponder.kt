package dev.atsushieno.ktmidi.ci

import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlin.random.Random

/**
 * This class is responsible only for one input/output connection pair
 *
 * The argument `sendOutput` takes a sysex bytestream which is NOT specific to MIDI 1.0 bytestream (i.e. it should
 * support sysex7 UMPs) and thus does NOT contain F0 and F7.
 * Same goes for `processInput()` function.
 *
 */
class MidiCIResponder(private var midiCIDevice: MidiCIDeviceInfo,
                      private val sendOutput: (data: List<Byte>) -> Unit,
                      val muid: Int = Random.nextInt() and 0x7F7F7F7F) {
    class Events {
        val discoveryReceived = mutableListOf<(msg: Message.DiscoveryInquiry) -> Unit>()
        val endpointInquiryReceived = mutableListOf<(msg: Message.EndpointInquiry) -> Unit>()
        val profileInquiryReceived = mutableListOf<(msg: Message.ProfileInquiry) -> Unit>()
        val setProfileOnReceived = mutableListOf<(profile: Message.SetProfileOn) -> Unit>()
        val setProfileOffReceived = mutableListOf<(profile: Message.SetProfileOff) -> Unit>()
        val unknownMessageReceived = mutableListOf<(data: List<Byte>) -> Unit>()
        val propertyCapabilityInquiryReceived = mutableListOf<(Message.PropertyGetCapabilities) -> Unit>()
        val getPropertyDataReceived = mutableListOf<(msg: Message.GetPropertyData) -> Unit>()
        val setPropertyDataReceived = mutableListOf<(msg: Message.SetPropertyData) -> Unit>()
        val subscribePropertyReceived = mutableListOf<(msg: Message.SubscribeProperty) -> Unit>()
        val subscribePropertyReplyReceived = mutableListOf<(msg: Message.SubscribePropertyReply) -> Unit>()
        val propertyNotifyReceived = mutableListOf<(msg: Message.PropertyNotify) -> Unit>()
    }

    val events = Events()

    val logger = Logger()

    var device: MidiCIDeviceInfo
        get() = midiCIDevice
        set(value) {
            midiCIDevice = value
            propertyService.deviceInfo = value
        }

    var capabilityInquirySupported = MidiCISupportedCategories.THREE_P
    var receivableMaxSysExSize = MidiCIConstants.DEFAULT_RECEIVABLE_MAX_SYSEX_SIZE
    val profiles = ObservableProfileList()
    var functionBlock: Byte = MidiCIConstants.NO_FUNCTION_BLOCK
    var productInstanceId: String = ""
    var maxSimultaneousPropertyRequests: Byte = 1

    var midiCIBufferSize = 4096

    val propertyService = CommonRulesPropertyService(muid, device)
    val properties = ServiceObservablePropertyList(propertyService)

    private var requestIdSerial: Byte = 0

    // updating property value involves updates to subscribers
    // FIXME: property update notifications should also be sent when it is triggered by
    //  client-side updates. Thus, it should be handled at CommonRulesPropertyService.
    fun updatePropertyValue(propertyId: String, data: List<Byte>) {
        properties.values.first { it.id == propertyId }.body = data
        notifyPropertyUpdatesToSubscribers(propertyId, data)
    }

    var notifyPropertyUpdatesToSubscribers: (propertyId: String, data: List<Byte>) -> Unit = { propertyId, data ->
        createPropertyNotification(propertyId, data).forEach { msg ->
            logger.subscribeProperty(msg)
            notifyPropertyUpdatesToSubscribers(msg)
        }
    }
    var createPropertyNotification: (propertyId: String, data: List<Byte>) -> Sequence<Message.SubscribeProperty> = { propertyId, data ->
        sequence {
            propertyService.subscriptions.filter { it.resource == propertyId }.forEach {
                // FIXME: maybe we should support partial notifications?
                val header = propertyService.createUpdateNotificationHeader(it.subscribeId, false)
                yield(Message.SubscribeProperty(muid, MidiCIConstants.BROADCAST_MUID_32, requestIdSerial++, header, data))
            }
        }
    }

    fun notifyPropertyUpdatesToSubscribers(msg: Message.SubscribeProperty) = sendPropertySubscription(msg)

    // Notify end of subscription updates
    fun sendPropertySubscription(msg: Message.SubscribeProperty) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        CIFactory.midiCIPropertyChunks(
            dst, CISubId2.PROPERTY_SUBSCRIBE,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, msg.body
        ).forEach {
            sendOutput(it)
        }
    }
    fun terminateSubscriptions() {
        propertyService.subscriptions.forEach {
            val msg = Message.SubscribeProperty(muid, it.muid, requestIdSerial++,
                propertyService.createTerminateNotificationHeader(it.subscribeId), listOf()
            )
            sendPropertySubscription(msg)
        }
    }

    // Message handlers

    val sendDiscoveryReply: (msg: Message.DiscoveryReply) -> Unit = { msg ->
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(
            CIFactory.midiCIDiscoveryReply(
                dst, MidiCIConstants.CI_VERSION_AND_FORMAT, msg.sourceMUID, msg.destinationMUID,
                msg.device.manufacturer, msg.device.family, msg.device.modelNumber, msg.device.softwareRevisionLevel,
                capabilityInquirySupported, receivableMaxSysExSize,
                msg.outputPathId, msg.functionBlock
            )
        )
    }
    val getDiscoveryReplyForInquiry: (msg: Message.DiscoveryInquiry) -> Message.DiscoveryReply = { msg ->
        val deviceDetails = DeviceDetails(device.manufacturerId, device.familyId, device.modelId, device.versionId)
        Message.DiscoveryReply(muid, msg.muid, deviceDetails, capabilityInquirySupported, receivableMaxSysExSize, msg.outputPathId, functionBlock)
    }
    var processDiscovery: (msg: Message.DiscoveryInquiry) -> Unit = { msg ->
        logger.discovery(msg)
        events.discoveryReceived.forEach { it(msg) }
        val reply = getDiscoveryReplyForInquiry(msg)
        logger.discoveryReply(reply)
        sendDiscoveryReply(reply)
    }

    val sendEndpointReply: (msg: Message.EndpointReply) -> Unit = { msg ->
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(
            CIFactory.midiCIEndpointMessageReply(
                dst, MidiCIConstants.CI_VERSION_AND_FORMAT, msg.sourceMUID, msg.destinationMUID, msg.status, msg.data)
        )
    }
    val getEndpointReplyForInquiry: (msg: Message.EndpointInquiry) -> Message.EndpointReply = { msg ->
        val prodId = productInstanceId
        if (prodId.length > 16)
            throw IllegalStateException("productInstanceId shall not be any longer than 16 bytes in size")
        Message.EndpointReply(muid, msg.sourceMUID, msg.status,
            if (msg.status == MidiCIConstants.ENDPOINT_STATUS_PRODUCT_INSTANCE_ID && prodId != null) prodId.toByteArray(
                Charsets.ISO_8859_1
            ).toList() else listOf() // FIXME: verify that it is only ASCII chars?
        )
    }
    var processEndpointMessage: (msg: Message.EndpointInquiry) -> Unit = { msg ->
        logger.endpointInquiry(msg)
        events.endpointInquiryReceived.forEach { it(msg) }
        val reply = getEndpointReplyForInquiry(msg)
        logger.endpointReply(reply)
        sendEndpointReply(reply)
    }

    val sendAckForInvalidateMUID: (sourceMUID: Int) -> Unit = { sourceMUID ->
        // FIXME: we need to implement some sort of state management for each initiator
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIAckNak(dst, false, MidiCIConstants.ADDRESS_FUNCTION_BLOCK,
            MidiCIConstants.CI_VERSION_AND_FORMAT, muid, sourceMUID, CISubId2.INVALIDATE_MUID, 0, 0, listOf(), listOf()))
    }
    var processInvalidateMUID = { sourceMUID: Int, targetMUID: Int -> sendAckForInvalidateMUID(sourceMUID) }

    // Profile Configuration

    val sendProfileReply: (msg: Message.ProfileReply) -> Unit = { msg ->
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIProfileInquiryReply(dst, msg.address, msg.sourceMUID, msg.destinationMUID,
            msg.enabledProfiles,
            msg.disabledProfiles)
        )
    }

    private fun getAllAddresses(address: Byte) = sequence {
        if (address == MidiCIConstants.ADDRESS_FUNCTION_BLOCK)
            yieldAll(profiles.profiles.map { it.address }.distinct().sorted())
        else
            yield(address)
    }
    val getProfileRepliesForInquiry: (msg: Message.ProfileInquiry) -> Sequence<Message.ProfileReply> = { msg ->
        sequence {
            getAllAddresses(msg.address).forEach { address ->
                yield(Message.ProfileReply(address, muid, msg.sourceMUID,
                    profiles.getMatchingProfiles(address, true),
                    profiles.getMatchingProfiles(address, false)))
            }
        }
    }
    var processProfileInquiry: (msg: Message.ProfileInquiry) -> Unit = { msg ->
        logger.profileInquiry(msg)
        events.profileInquiryReceived.forEach { it(msg) }
        getProfileRepliesForInquiry(msg).forEach { reply ->
            logger.profileReply(reply)
            sendProfileReply(reply)
        }
    }

    var onProfileSet = mutableListOf<(profile: MidiCIProfile, numChannelsRequested: Short) -> Unit>()

    private val defaultProcessSetProfile = { address: Byte, initiatorMUID: Int, destinationMUID: Int, profile: MidiCIProfileId, numChannelsRequested: Short, enabled: Boolean ->
        val newEntry = MidiCIProfile(profile, address, enabled)
        val existing = profiles.profiles.firstOrNull { it.profile == profile }
        if (existing != null)
            profiles.remove(existing)
        profiles.add(newEntry)
        onProfileSet.forEach { it(newEntry, numChannelsRequested) }
        enabled
    }

    private fun sendSetProfileReport(address: Byte, profile: MidiCIProfileId, enabled: Boolean) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIProfileReport(dst, address, enabled, muid, profile))
    }

    fun sendProfileAddedReport(profile: MidiCIProfile) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIProfileAddedRemoved(dst, profile.address, false, muid, profile.profile))
    }

    fun sendProfileRemovedReport(profile: MidiCIProfile) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIProfileAddedRemoved(dst, profile.address, true, muid, profile.profile))
    }

    fun defaultProcessSetProfileOn(msg: Message.SetProfileOn) {
        // send Profile Enabled Report only when it is actually enabled
        if (defaultProcessSetProfile(msg.address, msg.sourceMUID, msg.destinationMUID, msg.profile, msg.numChannelsRequested, true))
            sendSetProfileReport(msg.address, msg.profile, true)
    }
    fun defaultProcessSetProfileOff(msg: Message.SetProfileOff) {
        // send Profile Disabled Report only when it is actually disabled
        if (!defaultProcessSetProfile(msg.address, msg.sourceMUID, msg.destinationMUID, msg.profile, 0, false))
            sendSetProfileReport(msg.address, msg.profile, false)
    }

    var processSetProfileOn = { msg: Message.SetProfileOn ->
        logger.setProfileOn(msg)
        events.setProfileOnReceived.forEach { it(msg) }
        defaultProcessSetProfileOn(msg)
    }
    var processSetProfileOff = { msg: Message.SetProfileOff ->
        logger.setProfileOff(msg)
        events.setProfileOffReceived.forEach { it(msg) }
        defaultProcessSetProfileOff(msg)
    }

    var processProfileSpecificData: (address: Byte, sourceMUID: Int, destinationMUID: Int, profileId: MidiCIProfileId, data: List<Byte>) -> Unit = { _, _, _, _, _ -> }

    // Handling invalid messages

    fun sendNakForUnknownCIMessage(data: List<Byte>) {
        val source = data[1]
        val originalSubId = data[3]
        val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
        val destinationMUID = CIRetrieval.midiCIGetDestinationMUID(data)
        val nak = MidiCIAckNakData(source, sourceMUID, destinationMUID, originalSubId,
            CINakStatus.MessageNotSupported, 0, listOf(), listOf())
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIAckNak(dst, true, nak))
    }
    var processUnknownCIMessage: (data: List<Byte>) -> Unit = { data ->
        logger.nak(data)
        events.unknownMessageReceived.forEach { it(data) }
        sendNakForUnknownCIMessage(data)
    }

    // Property Exchange

    // Should this also delegate to property service...?
    fun sendPropertyCapabilitiesReply(msg: Message.PropertyGetCapabilitiesReply) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        sendOutput(CIFactory.midiCIPropertyGetCapabilities(dst, msg.destination, true,
            msg.sourceMUID, msg.destinationMUID, msg.maxSimultaneousRequests))
    }
    val getPropertyCapabilitiesReplyFor: (msg: Message.PropertyGetCapabilities) -> Message.PropertyGetCapabilitiesReply = { msg ->
        val establishedMaxSimultaneousPropertyRequests =
            if (msg.maxSimultaneousRequests > maxSimultaneousPropertyRequests) maxSimultaneousPropertyRequests
            else msg.maxSimultaneousRequests
        Message.PropertyGetCapabilitiesReply(msg.destination, muid, msg.sourceMUID, establishedMaxSimultaneousPropertyRequests)
    }
    var processPropertyCapabilitiesInquiry: (msg: Message.PropertyGetCapabilities) -> Unit = { msg ->
        logger.propertyGetCapabilitiesInquiry(msg)
        events.propertyCapabilityInquiryReceived.forEach { it(msg) }
        val reply = getPropertyCapabilitiesReplyFor(msg)
        logger.propertyGetCapabilitiesReply(reply)
        sendPropertyCapabilitiesReply(reply)
    }

    fun sendPropertyGetDataReply(msg: Message.GetPropertyDataReply) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        CIFactory.midiCIPropertyChunks(
            dst, CISubId2.PROPERTY_GET_DATA_REPLY,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, msg.body
        ).forEach {
            sendOutput(it)
        }
    }
    var processGetPropertyData: (msg: Message.GetPropertyData) -> Unit = { msg ->
        logger.getPropertyData(msg)
        events.getPropertyDataReceived.forEach { it(msg) }
        val reply = propertyService.getPropertyData(msg)
        logger.getPropertyDataReply(reply)
        sendPropertyGetDataReply(reply)
    }

    fun sendPropertySetDataReply(msg: Message.SetPropertyDataReply) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        CIFactory.midiCIPropertyChunks(
            dst, CISubId2.PROPERTY_SET_DATA_REPLY,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, listOf()
        ).forEach {
            sendOutput(it)
        }
    }
    var processSetPropertyData: (msg: Message.SetPropertyData) -> Unit = { msg ->
        logger.setPropertyData(msg)
        events.setPropertyDataReceived.forEach { it(msg) }
        val reply = propertyService.setPropertyData(msg)
        sendPropertySetDataReply(reply)
    }

    fun sendPropertySubscribeReply(msg: Message.SubscribePropertyReply) {
        val dst = MutableList<Byte>(midiCIBufferSize) { 0 }
        CIFactory.midiCIPropertyChunks(
            dst, CISubId2.PROPERTY_SUBSCRIBE_REPLY,
            msg.sourceMUID, msg.destinationMUID, msg.requestId, msg.header, msg.body
        ).forEach {
            sendOutput(it)
        }
    }
    var processSubscribeProperty: (msg: Message.SubscribeProperty) -> Unit = { msg ->
        logger.subscribeProperty(msg)
        events.subscribePropertyReceived.forEach { it(msg) }
        val reply = propertyService.subscribeProperty(msg)
        logger.subscribePropertyReply(reply)
        sendPropertySubscribeReply(reply)
    }

    // It receives reply to property notifications
    var processSubscribePropertyReply: (msg: Message.SubscribePropertyReply) -> Unit = { msg ->
        logger.subscribePropertyReply(msg)
        events.subscribePropertyReplyReceived.forEach { it(msg) }
    }

    var processPropertyNotify: (msg: Message.PropertyNotify) -> Unit = { msg ->
        logger.propertyNotify(msg)
        events.propertyNotifyReceived.forEach { it(msg) }
    }

    fun processInput(data: List<Byte>) {
        if (data[0] != 0x7E.toByte() || data[2] != 0xD.toByte())
            return // not MIDI-CI sysex

        val destinationMUID = CIRetrieval.midiCIGetDestinationMUID(data)
        if (destinationMUID != muid && destinationMUID != MidiCIConstants.BROADCAST_MUID_32)
            return // we are not the target

        when (data[3]) {
            // Discovery
            CISubId2.DISCOVERY_INQUIRY -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val device = CIRetrieval.midiCIGetDeviceDetails(data)
                val ciSupported = data[24]
                val max = CIRetrieval.midiCIMaxSysExSize(data)
                // only available in MIDI-CI 1.2 or later.
                val initiatorOutputPath = if (data.size > 29) data[29] else 0
                processDiscovery(Message.DiscoveryInquiry(sourceMUID, device, ciSupported, max, initiatorOutputPath))
            }

            CISubId2.ENDPOINT_MESSAGE_INQUIRY -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                // only available in MIDI-CI 1.2 or later.
                val status = data[13]
                processEndpointMessage(Message.EndpointInquiry(sourceMUID, destinationMUID, status))
            }

            CISubId2.INVALIDATE_MUID -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val targetMUID = CIRetrieval.midiCIGetMUIDToInvalidate(data)
                processInvalidateMUID(sourceMUID, targetMUID)
            }

            // Profile Configuration

            CISubId2.PROFILE_INQUIRY -> {
                val source = data[1]
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                processProfileInquiry(Message.ProfileInquiry(source, sourceMUID, destinationMUID))
            }

            CISubId2.SET_PROFILE_ON, CISubId2.SET_PROFILE_OFF -> {
                val enabled = data[3] == CISubId2.SET_PROFILE_ON
                val address = CIRetrieval.midiCIGetAddressing(data)
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val profileId = CIRetrieval.midiCIGetProfileId(data)
                val channels = (data[18] + (data[19] shl 7)).toShort()
                if (enabled)
                    processSetProfileOn(Message.SetProfileOn(address, sourceMUID, destinationMUID, profileId, channels))
                else
                    processSetProfileOff(Message.SetProfileOff(address, sourceMUID, destinationMUID, profileId))
            }

            CISubId2.PROFILE_SPECIFIC_DATA -> {
                val address = CIRetrieval.midiCIGetAddressing(data)
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val profileId = CIRetrieval.midiCIGetProfileId(data)
                val dataLength = CIRetrieval.midiCIGetProfileSpecificDataSize(data)
                processProfileSpecificData(address, sourceMUID, destinationMUID, profileId, data.drop(21).take(dataLength))
            }

            // Property Exchange

            CISubId2.PROPERTY_CAPABILITIES_INQUIRY -> {
                val destination = CIRetrieval.midiCIGetAddressing(data)
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val max = CIRetrieval.midiCIGetMaxPropertyRequests(data)

                processPropertyCapabilitiesInquiry(
                    Message.PropertyGetCapabilities(destination, sourceMUID, destinationMUID, max))
            }

            CISubId2.PROPERTY_GET_DATA_INQUIRY -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val requestId = data[13]
                val headerSize = data[14] + (data[15].toInt() shl 7)
                val header = data.drop(16).take(headerSize)
                val pos = 16 + headerSize
                val numChunks = CIRetrieval.midiCIGetPropertyTotalChunks(data)
                val chunkIndex = CIRetrieval.midiCIGetPropertyChunkIndex(data)
                val dataSize = data[pos + 4] + (data[pos + 5].toInt() shl 7)
                processGetPropertyData(Message.GetPropertyData(sourceMUID, destinationMUID, requestId, header))
            }

            CISubId2.PROPERTY_SET_DATA_INQUIRY -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val requestId = data[13]
                val headerSize = data[14] + (data[15].toInt() shl 7)
                val header = data.drop(16).take(headerSize)
                val pos = 16 + headerSize
                val numChunks = CIRetrieval.midiCIGetPropertyTotalChunks(data)
                val chunkIndex = CIRetrieval.midiCIGetPropertyChunkIndex(data)
                val dataSize = data[pos + 4] + (data[pos + 5].toInt() shl 7)
                val propData = data.drop (pos + 6).take(dataSize)
                processSetPropertyData(Message.SetPropertyData(sourceMUID, destinationMUID, requestId, header, numChunks, chunkIndex, propData))
            }

            CISubId2.PROPERTY_SUBSCRIBE -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val requestId = data[13]
                val headerSize = data[14] + (data[15].toInt() shl 7)
                val header = data.drop(16).take(headerSize)
                processSubscribeProperty(Message.SubscribeProperty(sourceMUID, destinationMUID, requestId, header, listOf()))
            }

            CISubId2.PROPERTY_SUBSCRIBE_REPLY -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val requestId = data[13]
                val headerSize = data[14] + (data[15].toInt() shl 7)
                val header = data.drop(16).take(headerSize)
                processSubscribePropertyReply(Message.SubscribePropertyReply(sourceMUID, destinationMUID, requestId, header, listOf()))
            }

            CISubId2.PROPERTY_NOTIFY -> {
                val sourceMUID = CIRetrieval.midiCIGetSourceMUID(data)
                val requestId = data[13]
                val headerSize = data[14] + (data[15].toInt() shl 7)
                val header = data.drop(16).take(headerSize)
                processPropertyNotify(Message.PropertyNotify(sourceMUID, destinationMUID, requestId, header, listOf()))
            }

            else -> {
                processUnknownCIMessage(data)
            }
        }
    }

    init {
        if (muid != muid and 0x7F7F7F7F)
            throw IllegalArgumentException("muid must consist of 7-bit byte values i.e. each 8-bit number must not have the topmost bit as 0. (`muid` must be equivalent to `muid and 0x7F7F7F7F`")
    }
}