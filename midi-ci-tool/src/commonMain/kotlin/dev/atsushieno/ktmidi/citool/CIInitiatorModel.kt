package dev.atsushieno.ktmidi.citool

import dev.atsushieno.ktmidi.ci.*
import dev.atsushieno.ktmidi.citool.view.ViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random

class CIInitiatorModel(private val outputSender: (ciBytes: List<Byte>) -> Unit) {
    fun processCIMessage(data: List<Byte>) {
        val time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        ViewModel.log("[${time.time.toString().substring(0, 8)}] SYSEX: " + data.joinToString { it.toString(16) })
        initiator.processInput(data)
    }

    private val logger = Logger()

    class Events {
        val discoveryReplyReceived = mutableListOf<(Message.DiscoveryReply) -> Unit>()
        val endpointReplyReceived = mutableListOf<(Message.EndpointReply) -> Unit>()
        val profileInquiryReplyReceived = mutableListOf<(Message.ProfileReply) -> Unit>()
        val profileAddedReceived = mutableListOf<(Message.ProfileAdded) -> Unit>()
        val profileRemovedReceived = mutableListOf<(Message.ProfileRemoved) -> Unit>()
        val profileEnabledReceived = mutableListOf<(Message.ProfileEnabled) -> Unit>()
        val profileDisabledReceived = mutableListOf<(Message.ProfileDisabled) -> Unit>()
        val unknownMessageReceived = mutableListOf<(data: List<Byte>) -> Unit>()
        val propertyCapabilityReplyReceived = mutableListOf<(Message.PropertyGetCapabilitiesReply) -> Unit>()
        val getPropertyDataReplyReceived = mutableListOf<(msg: Message.GetPropertyDataReply) -> Unit>()
        val setPropertyDataReplyReceived = mutableListOf<(msg: Message.SetPropertyDataReply) -> Unit>()
        val subscribePropertyReceived = mutableListOf<(msg: Message.SubscribePropertyReply) -> Unit>()
    }

    val events = Events()

    private val device = MidiCIDeviceInfo(1,2,1,1,
        "atsushieno", "KtMidi", "KtMidi-CI-Tool Initiator", "0.1")

    val initiator = MidiCIInitiator(device, { data ->
        ViewModel.log("[I] REQUEST SYSEX: " + data.joinToString { it.toString(16) } + "\n")
        outputSender(data)
    }).apply {
        productInstanceId = "ktmidi-ci" + (Random.nextInt() % 65536)

        // Unknown
        processUnknownCIMessage = { data ->
            logger.nak(data)
            events.unknownMessageReceived.forEach { it(data) }
            sendNakForUnknownCIMessage(data)
        }

        processDiscoveryReply = { msg ->
            logger.discoveryReply(msg)
            events.discoveryReplyReceived.forEach { it(msg) }
            handleNewEndpoint(msg)
        }

        processEndpointReply = { msg ->
            logger.endpointReply(msg)
            events.endpointReplyReceived.forEach { it(msg) }
            defaultProcessEndpointReply(msg)
        }

        // Profile Configuration

        processProfileReply = { msg ->
            logger.profileReply(msg)
            events.profileInquiryReplyReceived.forEach { it(msg) }
            defaultProcessProfileReply(msg)
        }

        processProfileAddedReport = { msg ->
            logger.profileAdded(msg)
            events.profileAddedReceived.forEach { it(msg) }
            defaultProcessProfileAddedReport(msg)
        }

        processProfileRemovedReport = { msg ->
            logger.profileRemoved(msg)
            events.profileRemovedReceived.forEach { it(msg) }
            defaultProcessProfileRemovedReport(msg)
        }

        processProfileEnabledReport = { msg ->
            logger.profileEnabled(msg)
            events.profileEnabledReceived.forEach { it(msg) }
            defaultProcessProfileEnabledReport(msg)
        }

        processProfileDisabledReport = { msg ->
            logger.profileDisabled(msg)
            events.profileDisabledReceived.forEach { it(msg) }
            defaultProcessProfileDisabledReport(msg)
        }

        // Property Exchange

        processPropertyCapabilitiesReply = { msg ->
            logger.propertyGetCapabilitiesReply(msg)
            events.propertyCapabilityReplyReceived.forEach { it(msg) }
            defaultProcessPropertyCapabilitiesReply(msg)
        }

        processGetDataReply = { msg ->
            logger.getPropertyDataReply(msg)
            events.getPropertyDataReplyReceived.forEach { it(msg) }
            defaultProcessGetDataReply(msg)
        }

        processSetDataReply = { msg ->
            logger.setPropertyDataReply(msg)
            events.setPropertyDataReplyReceived.forEach { it(msg) }
            // nothing to delegate further
        }
    }

    fun sendDiscovery() {
        val msg = initiator.createDiscoveryInquiry()
        logger.discovery(msg)
        initiator.sendDiscovery(msg)
    }

    // FIXME: we need to make MidiCIInitiator EndpointInquiry hook-able.
    fun sendEndpointMessage(targetMUID: Int) {
        val msg = initiator.createEndpointMessage(targetMUID)
        logger.endpointInquiry(msg)
        initiator.sendEndpointMessage(msg)
    }

    fun setProfile(destinationMUID: Int, address: Byte, profile: MidiCIProfileId, nextEnabled: Boolean) {
        if (nextEnabled) {
            // FIXME: maybe we should pass number of channels somehow?
            val msg = Message.SetProfileOn(address, initiator.muid, destinationMUID, profile,
                // NOTE: juce_midi_ci has a bug that it expects 1 for 7E and 7F, whereas MIDI-CI v1.2 states:
                //   "When the Profile Destination field is set to address 0x7E or 0x7F, the number of Channels is determined
                //    by the width of the Group or Function Block. Set the Number of Channels Requested field to a value of 0x0000."
                if (address < 0x10 || ViewModel.settings.workaroundJUCEProfileNumChannelsIssue.value) 1
                else 0)
            logger.setProfileOn(msg)
            initiator.setProfileOn(msg)
        } else {
            val msg = Message.SetProfileOff(address, initiator.muid, destinationMUID, profile)
            logger.setProfileOff(msg)
            initiator.setProfileOff(msg)
        }
    }

    fun sendGetPropertyDataRequest(destinationMUID: Int, resource: String) {
        initiator.sendGetPropertyData(destinationMUID, resource)
    }
    fun sendSetPropertyDataRequest(destinationMUID: Int, resource: String, data: List<Byte>, isPartial: Boolean) {
        initiator.sendSetPropertyData(destinationMUID, resource, data, isPartial)
    }
}

