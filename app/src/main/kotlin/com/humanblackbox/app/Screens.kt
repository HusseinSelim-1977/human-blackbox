package com.humanblackbox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.humanblackbox.core.Decision
import com.humanblackbox.core.Modality
import java.time.Instant

@Composable
private fun SectionBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SosSection(
    contacts: List<EmergencyContact>,
    events: List<LocalSosEvent>,
    lastDecision: Decision?,
    notice: String?,
    testMode: Boolean,
    checkIn: LocalCheckInState?,
    onConfirmCheckIn: () -> Unit,
    micGranted: Boolean,
    cameraGranted: Boolean,
    locationGranted: Boolean,
    notificationsGranted: Boolean,
    locationIncluded: Boolean,
    onRequestMic: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    SectionBody {
        Text("Emergency", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Manual SOS is a user assertion. It records a local event and requests a local " +
                        "notification only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Stored contacts: ${contacts.size}. Local events: ${events.size}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Coarse location on local events: ${if (locationIncluded) "enabled" else "disabled"}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (testMode) "Test mode is ON: no external service is contacted." else "Test mode is OFF.",
                    style = MaterialTheme.typography.bodySmall,
                )
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        lastDecision?.let { decision ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Last local decision", style = MaterialTheme.typography.titleMedium)
                    Text("Action: ${decision.recommendedAction.name}")
                    Text("Event type: ${decision.eventType.name} (no classifier was run)")
                    Text("Time: ${Instant.ofEpochMilli(decision.timestampMs)}")
                    Text(decision.explanation.summary)
                    Text(
                        "No model confidence is reported: manual SOS is user-initiated, not inferred.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        checkIn?.let { state ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.escalated) {
                            "Check-in missed: local escalation sent"
                        } else {
                            "Check-in required: ${state.secondsLeft} s left to cancel"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        if (state.testMode) {
                            "TEST mode: countdown and escalation are local only."
                        } else {
                            "Escalation is a local notification only: no call, SMS or service contacted."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onConfirmCheckIn,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                            contentDescription = if (state.escalated) {
                                "Acknowledge the local escalation"
                            } else {
                                "Confirm I am OK and cancel the local SOS escalation"
                            }
                        },
                    ) {
                        Text(
                            text = if (state.escalated) "Acknowledge local escalation" else "I'm OK — cancel escalation",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }

        Text("Runtime permissions", style = MaterialTheme.typography.titleMedium)
        PermissionRow("Microphone", micGranted, onRequestMic)
        PermissionRow("Camera", cameraGranted, onRequestCamera)
        PermissionRow("Location", locationGranted, onRequestLocation)
        PermissionRow("Notifications", notificationsGranted, onRequestNotifications)
        Text(
            "Permissions are requested only from these buttons. This screen starts no microphone, " +
                "camera or network stream.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (granted) {
            Text("Granted", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(
                onClick = onRequest,
                modifier = Modifier.heightIn(min = 56.dp).semantics {
                    contentDescription = "Request $label permission"
                },
            ) { Text("Request") }
        }
    }
}

@Composable
fun ContactsSection(
    contacts: List<EmergencyContact>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    SectionBody {
        Text("Emergency contacts", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Contacts are encrypted on this device. The SOS button does not call, text or otherwise " +
                "contact them in this build.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onAdd(name.trim(), phone.trim())
                name = ""
                phone = ""
            },
            enabled = name.isNotBlank() && phone.isNotBlank(),
        ) {
            Text("Add contact")
        }
        if (contacts.isEmpty()) {
            Text("No contacts stored.")
        } else {
            contacts.forEach { contact ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(contact.phone, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = { onRemove(contact.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySection(events: List<LocalSosEvent>, onClear: () -> Unit) {
    SectionBody {
        Text("Event history", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Only SOS events recorded by this app appear here. No detections are fabricated.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onClear, enabled = events.isNotEmpty()) { Text("Clear local history") }
        if (events.isEmpty()) {
            Text("No local events recorded.")
        } else {
            events.forEach { event ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (event.testMode) "TEST Manual SOS" else "Manual SOS",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("Time: ${Instant.ofEpochMilli(event.timestampMs)}")
                        Text("Action: ${event.action} / event type: ${event.eventType}")
                        Text(event.summary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Coarse location: ${event.location ?: "not included"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusSection(modelAssets: List<String>) {
    val modalities = listOf(Modality.AUDIO, Modality.MOTION, Modality.VISION, Modality.FUSION)
    SectionBody {
        Text("Model status", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Interpreter binding: UNAVAILABLE (no interpreter bound)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "Bundled .tflite assets: ${if (modelAssets.isEmpty()) "none found" else modelAssets.joinToString()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        modalities.forEach { modality ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${modality.name}: UNAVAILABLE", style = MaterialTheme.typography.titleMedium)
                    Text("Version: UNAVAILABLE")
                    Text("Last inference: UNAVAILABLE")
                }
            }
        }
        Text(
            "Manual SOS does not consult any of these models and works with none of them loaded.",
            style = MaterialTheme.typography.bodySmall,
        )
        // TODO(models): bind a trained FusionEngine interpreter and publish measured
        //   ModelStatus values only when a real .tflite artifact ships.
    }
}

@Composable
fun PrivacySection(
    audioEnabled: Boolean,
    motionEnabled: Boolean,
    visionEnabled: Boolean,
    fusionEnabled: Boolean,
    evidenceConsent: Boolean,
    locationConsent: Boolean,
    cloudAlertOptIn: Boolean,
    highContrast: Boolean,
    testMode: Boolean,
    onSetModality: (Modality, Boolean) -> Unit,
    onSetEvidenceConsent: (Boolean) -> Unit,
    onSetCloudAlertOptIn: (Boolean) -> Unit,
    onToggleHighContrast: (Boolean) -> Unit,
    onToggleLocationConsent: (Boolean) -> Unit,
) {
    SectionBody {
        Text("Privacy controls", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Kill switches affect model-based detection only. Manual SOS remains available " +
                "because it is user-initiated and local.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ToggleRow("Microphone detection", audioEnabled) { onSetModality(Modality.AUDIO, it) }
        ToggleRow("Motion detection", motionEnabled) { onSetModality(Modality.MOTION, it) }
        ToggleRow("Vision detection", visionEnabled) { onSetModality(Modality.VISION, it) }
        ToggleRow("Fusion detection", fusionEnabled) { onSetModality(Modality.FUSION, it) }
        ToggleRow("Evidence upload consent", evidenceConsent, onSetEvidenceConsent)
        Text(
            "Evidence upload stays off unless explicitly granted; this screen enqueues no upload " +
                "and Firebase config is optional.",
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow(
            label = "Cloud alert opt-in (alerts topic)",
            checked = cloudAlertOptIn,
            onCheckedChange = onSetCloudAlertOptIn,
            enabled = !testMode,
        )
        Text(
            if (testMode) {
                "Test mode is on: the opt-in cannot be changed and no external service is contacted."
            } else {
                "Opt-in is stored locally and off by default; enabling it attempts an alerts-topic " +
                    "subscription. Manual SOS and its escalation still stay local only."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow("High-contrast display", highContrast, onToggleHighContrast)
        Text(
            "High contrast uses a black/white palette with a bright error colour as a fallback.",
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow("Allow coarse location on local events", locationConsent, onToggleLocationConsent)
        Text(
            "Location is read from cached device fixes and rounded to ~1 km. It is stored locally " +
                "only when this toggle and the location permission are both granted.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
