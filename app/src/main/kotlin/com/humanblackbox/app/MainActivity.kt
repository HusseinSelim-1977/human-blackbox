package com.humanblackbox.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.humanblackbox.core.Decision
import com.humanblackbox.core.Modality
import com.humanblackbox.data.PrivacySettings
import kotlinx.coroutines.launch

private enum class AppSection(val label: String) {
    SOS("SOS"), CONTACTS("Contacts"), HISTORY("History"), MODELS("Models"), PRIVACY("Privacy"),
}

/**
 * UI shell. Everything shown here is either user-entered, persisted locally,
 * or explicitly marked unavailable. No model, benchmark or contact is faked.
 *
 * Wired: Compose navigation, runtime permission requests, manual SOS controller,
 * encrypted local contacts/history, privacy kill switches from the data module.
 * Placeholder: model inference, cloud alerts and evidence upload are not wired.
 */
class MainActivity : ComponentActivity() {
    private val privacySettings by lazy { PrivacySettings(applicationContext) }
    private val eventStore by lazy { LocalEventStore(applicationContext) }
    private val sosController: ManualSosController by lazy { LocalManualSosController() }
    private val notifier by lazy { LocalAlertNotifier(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HumanBlackBoxApp(privacySettings, eventStore, sosController, notifier)
                }
            }
        }
    }
}

@Composable
private fun HumanBlackBoxApp(
    privacySettings: PrivacySettings,
    eventStore: LocalEventStore,
    sosController: ManualSosController,
    notifier: LocalAlertNotifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf(AppSection.SOS) }
    var testMode by rememberSaveable { mutableStateOf(false) }
    var contacts by remember { mutableStateOf(eventStore.contacts()) }
    var events by remember { mutableStateOf(eventStore.events()) }
    var lastDecision by remember { mutableStateOf<Decision?>(null) }
    var locationConsent by remember { mutableStateOf(eventStore.locationConsent()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    var pendingLocationConsent by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionEpoch++
        val denied = result.filterValues { !it }.keys
            .map { it.removePrefix("android.permission.") }
        notice = if (denied.isEmpty()) {
            "Permission granted."
        } else {
            "Not granted: ${denied.joinToString()}. Affected controls stay disabled."
        }
        if (pendingLocationConsent) {
            pendingLocationConsent = false
            if (hasLocationPermission(context)) {
                eventStore.setLocationConsent(true)
                locationConsent = true
                notice = "Coarse location enabled for local events only."
            }
        }
    }

    val audioEnabled by privacySettings.modalityEnabled(Modality.AUDIO).collectAsState(initial = true)
    val motionEnabled by privacySettings.modalityEnabled(Modality.MOTION).collectAsState(initial = true)
    val visionEnabled by privacySettings.modalityEnabled(Modality.VISION).collectAsState(initial = true)
    val fusionEnabled by privacySettings.modalityEnabled(Modality.FUSION).collectAsState(initial = true)
    val evidenceConsent by privacySettings.evidenceUploadConsent().collectAsState(initial = false)

    val modelAssets = remember {
        context.assets.list("").orEmpty().filter { it.endsWith(".tflite", ignoreCase = true) }
    }

    fun onSos() {
        val decision = sosController.trigger(testMode)
        val location = if (locationConsent && hasLocationPermission(context)) CoarseLocation.read(context) else null
        eventStore.addEvent(
            LocalSosEvent(
                id = "${decision.timestampMs}-${System.nanoTime()}",
                timestampMs = decision.timestampMs,
                eventType = decision.eventType.name,
                action = decision.recommendedAction.name,
                summary = decision.explanation.summary,
                location = location,
                testMode = testMode,
            ),
        )
        events = eventStore.events()
        lastDecision = decision
        notifier.post(decision, testMode)
        notice = if (testMode) {
            "TEST SOS recorded locally. No external service contacted."
        } else {
            "Manual SOS recorded locally. No emergency call placed."
        }
    }

    val micGranted = remember(permissionEpoch) { isGranted(context, Manifest.permission.RECORD_AUDIO) }
    val cameraGranted = remember(permissionEpoch) { isGranted(context, Manifest.permission.CAMERA) }
    val locationGranted = remember(permissionEpoch) { hasLocationPermission(context) }
    val notificationsGranted = remember(permissionEpoch) { hasNotificationPermission(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionTabs(current = section, onSelect = { section = it })
        Box(modifier = Modifier.weight(1f)) {
            when (section) {
                AppSection.SOS -> SosSection(
                    contacts = contacts,
                    events = events,
                    lastDecision = lastDecision,
                    notice = notice,
                    testMode = testMode,
                    micGranted = micGranted,
                    cameraGranted = cameraGranted,
                    locationGranted = locationGranted,
                    notificationsGranted = notificationsGranted,
                    locationIncluded = locationConsent && locationGranted,
                    onRequestMic = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                    onRequestCamera = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    },
                    onRequestLocation = {
                        pendingLocationConsent = locationConsent
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                    onRequestNotifications = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    },
                )
                AppSection.CONTACTS -> ContactsSection(
                    contacts = contacts,
                    onAdd = { name, phone ->
                        eventStore.addContact(name, phone)
                        contacts = eventStore.contacts()
                        notice = "Contact stored on this device only."
                    },
                    onRemove = { id ->
                        eventStore.removeContact(id)
                        contacts = eventStore.contacts()
                    },
                )
                AppSection.HISTORY -> HistorySection(
                    events = events,
                    onClear = {
                        eventStore.clearEvents()
                        events = eventStore.events()
                        lastDecision = null
                    },
                )
                AppSection.MODELS -> StatusSection(modelAssets = modelAssets)
                AppSection.PRIVACY -> PrivacySection(
                    audioEnabled = audioEnabled,
                    motionEnabled = motionEnabled,
                    visionEnabled = visionEnabled,
                    fusionEnabled = fusionEnabled,
                    evidenceConsent = evidenceConsent,
                    locationConsent = locationConsent,
                    onSetModality = { modality, enabled ->
                        scope.launch { privacySettings.setModalityEnabled(modality, enabled) }
                    },
                    onSetEvidenceConsent = { enabled ->
                        scope.launch { privacySettings.setEvidenceUploadConsent(enabled) }
                    },
                    onToggleLocationConsent = { enabled ->
                        if (enabled) {
                            pendingLocationConsent = true
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        } else {
                            eventStore.setLocationConsent(false)
                            locationConsent = false
                            notice = "Coarse location disabled for local events."
                        }
                    },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Test emergency mode (local only)", modifier = Modifier.weight(1f))
                Switch(checked = testMode, onCheckedChange = { testMode = it })
            }
            Button(
                onClick = { onSos() },
                modifier = Modifier.fillMaxWidth().height(112.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(
                    text = if (testMode) "TEST SOS" else "SOS",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            Text(
                "Assistive tool only — not authoritative emergency detection. " +
                    "No emergency calls are placed.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SectionTabs(current: AppSection, onSelect: (AppSection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppSection.values().forEach { item ->
            val selected = item == current
            Button(
                onClick = { onSelect(item) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(item.label)
            }
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun hasLocationPermission(context: Context): Boolean =
    isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
