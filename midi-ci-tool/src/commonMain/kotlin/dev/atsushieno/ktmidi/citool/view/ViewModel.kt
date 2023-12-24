package dev.atsushieno.ktmidi.citool.view

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.atsushieno.ktmidi.ci.*
import dev.atsushieno.ktmidi.citool.AppModel

object ViewModel {
    private var logText = mutableStateOf("")

    val log: MutableState<String>
        get() = logText
    fun log(msg: String) {
        Snapshot.withMutableSnapshot { logText.value += msg + (if (msg.endsWith('\n')) "" else "\n") }
    }

    var selectedRemoteDeviceMUID = mutableStateOf(0)
    val selectedRemoteDevice = derivedStateOf {
        val conn = AppModel.ciDeviceManager.initiator.initiator.connections[selectedRemoteDeviceMUID.value]
        if (conn != null) ConnectionViewModel(conn) else null
    }

    init {
        // When a new entry is appeared and nothing was selected, move to the new entry.
        AppModel.ciDeviceManager.initiator.initiator.connectionsChanged.add { change, conn ->
            if (selectedRemoteDeviceMUID.value == 0 && change == MidiCIInitiator.ConnectionChange.Added)
                Snapshot.withMutableSnapshot { selectedRemoteDeviceMUID.value = conn.muid }
        }
    }
}

class ConnectionViewModel(val conn: MidiCIInitiator.Connection) {
    fun selectProfile(profile: MidiCIProfileId) {
        val nextProfile = profiles.firstOrNull { it.profile.profile.toString() == profile.toString() }
        Snapshot.withMutableSnapshot { selectedProfile.value = nextProfile }
    }

    var selectedProfile = mutableStateOf<MidiClientProfileViewModel?>(null)

    val profiles = mutableStateListOf<MidiClientProfileViewModel>().apply {
        addAll(conn.profiles.profiles.map {
            MidiClientProfileViewModel(this@ConnectionViewModel, it)
        })
    }

    val properties = mutableStateListOf<ObservablePropertyList.Entry>().apply { addAll(conn.properties.entries)}

    init {
        conn.profiles.profilesChanged.add { change, profile ->
            when (change) {
                ObservableProfileList.ProfilesChange.Added -> profiles.add(MidiClientProfileViewModel(this, profile))
                ObservableProfileList.ProfilesChange.Removed -> profiles.removeAll { it.profile.toString() == profile.profile.toString() }
            }
        }
        conn.properties.propertiesCatalogUpdated.add {
            properties.clear()
            properties.addAll(conn.properties.entries)
        }
    }
}

class MidiClientProfileViewModel(private val parent: ConnectionViewModel, val profile: MidiCIProfile) {
    val enabled = mutableStateOf(profile.enabled)
}
