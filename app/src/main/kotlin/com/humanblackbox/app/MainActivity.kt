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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.humanblackbox.cloud.FcmRegistrationStatus
import com.humanblackbox.cloud.FcmTopicRegistrar
import com.humanblackbox.core.Decision
import com.humanblackbox.core.Modality
import com.humanblackbox.data.PrivacySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppSection(val label: String) {
    SOS("SOS"), CONTACTS("Contacts"), HISTORY("History"), MODELS("Models"), PRIVACY("Privacy"),
}

/** Inside the required 10–30 s check-in window. */
private const val CHECK_IN_SECONDS = 20

private val CheckInStateSaver = listSaver<LocalCheckInState?, Any>(
    save = { state ->
        if (state == null) {
            emptyList()
        } else {
            listOf(state.testMode, state.secondsLeft, state.escalated)
        }
    },
    restore = { values ->
        if (values.size == 3) {
            LocalCheckInState(values[0] as Boolean, values[1] as Int, values[2] as Boolean)
        } else {
            null
        }
    },
)

/** Explicit fallback when system colours do not give enough contrast. */
private val HighContrastColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.Black,
)

/**
 * UI shell. Everything shown here is either user-entered, persisted locally,
 * or explicitly marked unavailable. No model, benchmark or contact is faked.
 *
 * Wired: Compose navigation, runtime permission requests, manual SOS controller
 * with a cancelable local check-in, encrypted local contacts/history, privacy
 * kill switches, high-contrast fallback and an explicit FCM alert opt-in.
 * Model inference is not wired; manual SOS and its escalation stay local-only.
 */
class MainActivity : ComponentActivity() {
    private val privacySettings by lazy { PrivacySettings(applicationContext) }
    private val eventStore by lazy { LocalEventStore(applicationContext) }
    private val sosController: ManualSosController by lazy { LocalManualSosController() }
    private val notifier by lazy { LocalAlertNotifier(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var highContrast by rememberSaveable { mutableStateOf(false) }
            MaterialTheme(colorScheme = if (highContrast) HighContrastColors else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HumanBlackBoxApp(
                        privacySettings = privacySettings,
                        eventStore = eventStore,
                        sosController = sosController,
                        notifier = notifier,
                        highContrast = highContrast,
                        onToggleHighContrast = { highContrast = it },
                    )
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
    highContrast: Boolean,
    onToggleHighContrast: (Boolean) -> Unit,
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
    var cloudAlertOptIn by remember { mutableStateOf(eventStore.cloudAlertOptIn()) }
    var checkIn by rememberSaveable(stateSaver = CheckInStateSaver) {
        mutableStateOf<LocalCheckInState?>(null)
    }
    var checkInEpoch by rememberSaveable { mutableIntStateOf(0) }

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

    LaunchedEffect(checkInEpoch) {
        if (checkInEpoch == 0) return@LaunchedEffect
        while ((checkIn?.secondsLeft ?: 0) > 0 && checkIn?.escalated == false) {
            delay(1_000)
            val current = checkIn ?: return@LaunchedEffect
            checkIn = current.copy(secondsLeft = current.secondsLeft - 1)
        }
        val current = checkIn ?: return@LaunchedEffect
        if (!current.escalated) {
            checkIn = current.copy(escalated = true)
            notifier.postEscalation(current.testMode)
            notice = "No check-in received. Local escalation only — no call, SMS or service contacted."
        }
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
        checkIn = LocalCheckInState(testMode = testMode, secondsLeft = CHECK_IN_SECONDS, escalated = false)
        checkInEpoch++
        notice = if (testMode) {
            "TEST SOS recorded locally. Check in within $CHECK_IN_SECONDS s; no external service contacted."
        } else {
            "Manual SOS recorded locally. Check in within $CHECK_IN_SECONDS s; no emergency call placed."
        }
    }

    fun onConfirmCheckIn() {
        val current = checkIn ?: return
        notifier.clear(current.testMode)
        checkIn = null
        checkInEpoch++
        notice = if (current.escalated) {
            "Local escalation acknowledged. No external service contacted."
        } else {
            "Check-in confirmed. Escalation cancelled; no external service contacted."
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
                    checkIn = checkIn,
                    onConfirmCheckIn = { onConfirmCheckIn() },
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
                    cloudAlertOptIn = cloudAlertOptIn,
                    highContrast = highContrast,
                    testMode = testMode,
                    onSetModality = { modality, enabled ->
                        scope.launch { privacySettings.setModalityEnabled(modality, enabled) }
                    },
                    onSetEvidenceConsent = { enabled ->
                        scope.launch { privacySettings.setEvidenceUploadConsent(enabled) }
                    },
                    onSetCloudAlertOptIn = { enabled ->
                        eventStore.setCloudAlertOptIn(enabled)
                        cloudAlertOptIn = enabled
                        if (enabled && !testMode) {
                            scope.launch {
                                val result = FcmTopicRegistrar(context.applicationContext).registerForAlerts()
                                notice = when (result.status) {
                                    FcmRegistrationStatus.REGISTERED ->
                                        "Alerts topic registered. Manual SOS still stays local only."
                                    FcmRegistrationStatus.UNAVAILABLE ->
                                        "No Firebase configuration; opt-in is stored locally only."
                                    FcmRegistrationStatus.FAILED ->
                                        "Registration failed; opt-in is stored locally only."
                                }
                            }
                        } else {
                            notice = if (enabled) {
                                "Opt-in stored locally. Turn off test mode to register for alerts."
                            } else {
                                "Cloud alert opt-in revoked locally."
                            }
                        }
                    },
                    onToggleHighContrast = onToggleHighContrast,
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
                Switch(
                    checked = testMode,
                    onCheckedChange = { testMode = it },
                    modifier = Modifier.semantics { contentDescription = "Test emergency mode, local only" },
                )
            }
            Button(
                onClick = { onSos() },
                modifier = Modifier.fillMaxWidth().height(112.dp).semantics {
                    contentDescription = if (testMode) {
                        "Test SOS: record a local test event, no external service contacted"
                    } else {
                        "Local SOS: record locally, check in to cancel escalation, no emergency call"
                    }
                },
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
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Open ${item.label} section"
                },
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
