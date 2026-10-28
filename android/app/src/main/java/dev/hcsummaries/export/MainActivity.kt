package dev.hcsummaries.export

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {

    private val providerPackage = HEALTH_CONNECT_PACKAGE
    private lateinit var store: ExportStore
    private lateinit var sync: SyncComponents
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var sdkStatus = HealthConnectClient.SDK_UNAVAILABLE
    private var reader: HealthConnectReader? = null
    private var granted: Set<Metric> = emptySet()
    private var grantedPermissions: Set<String> = emptySet()
    private var backgroundSupported = false
    private var work = WorkSnapshot(periodicEnqueued = false, running = false, nextRunAt = null)
    private var syncBusy = false
    private var foregroundSyncRunning = false
    private var checkingStatus = false
    private var pendingEnableAuto = false
    /** The first-run "Choose health data" request is open; on return, keep only what was allowed. */
    private var pendingChooseData = false
    /** Nothing was allowed last time; Health Connect may not show its dialog again, so open its settings instead. */
    private var chooseDataDenied = false
    private val selected = mutableSetOf<Metric>()
    private var rangeDays = ExportPlanner.DEFAULT_DAYS
    private var busy = false
    private var preview: Pair<List<Metric>, List<DayResult>>? = null
    private var pendingSave: File? = null
    private var settingsOpen = false
    private var detailsOpen = false
    private var advancedOpen = false
    /** Grants have been read at least once, so "not granted" is a fact rather than "not loaded yet". */
    private var permissionsLoaded = false
    private var home: HomeStatus? = null
    /** Sync result messages are hidden once sync moves on, so they never contradict the status. */
    private var messageTransient = false
    private var lastActivity: UploadActivity? = null

    // Views
    private lateinit var scroll: ScrollView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusStep: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSync: TextView
    private lateinit var statusNote: TextView
    private lateinit var primaryButton: Button
    private lateinit var syncMessage: TextView
    private lateinit var pairCard: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var connectButton: Button
    private lateinit var settingsSection: Section
    private lateinit var detailsSection: Section
    private lateinit var advancedSection: Section
    private lateinit var connectionRow: StatusRow
    private lateinit var uploadRow: StatusRow
    private lateinit var backgroundRow: StatusRow
    private lateinit var accessRow: StatusRow
    private lateinit var refreshButton: Button
    private lateinit var autoSwitch: Switch
    private lateinit var syncNowButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var openSiteButton: Button
    private lateinit var grantButton: Button
    private lateinit var manageButton: Button
    private lateinit var technicalDetails: TextView
    private lateinit var createButton: Button
    private lateinit var message: TextView
    private lateinit var previewCard: LinearLayout
    private lateinit var previewBody: LinearLayout
    private lateinit var exportsBody: LinearLayout
    private val checkboxes = mutableMapOf<Metric, CheckBox>()
    private val grantLabels = mutableMapOf<Metric, TextView>()

    private val permissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            refresh(afterPermissionRequest = true)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val file = pendingSave
            pendingSave = null
            if (uri != null && file != null) saveCopy(file, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep health values out of screenshots and the recents thumbnail in release builds.
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        store = ExportStore(this)
        sync = SyncComponents(this)
        // Sections start collapsed and only open when the user opens them; kept across rotation.
        settingsOpen = savedInstanceState?.getBoolean(STATE_SETTINGS, false) ?: false
        detailsOpen = savedInstanceState?.getBoolean(STATE_DETAILS, false) ?: false
        advancedOpen = savedInstanceState?.getBoolean(STATE_ADVANCED, false) ?: false
        loadSettings()
        setContentView(buildUi())
        observeWork()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS, settingsOpen)
        outState.putBoolean(STATE_DETAILS, detailsOpen)
        outState.putBoolean(STATE_ADVANCED, advancedOpen)
    }

    private fun observeWork() {
        val wm = WorkManager.getInstance(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.PERIODIC_WORK),
                    wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.ONE_TIME_WORK),
                ) { periodic, oneTime -> SyncScheduler.snapshot(periodic, oneTime) }
                    .collect {
                        work = it
                        renderSync()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // Picks up grants changed or revoked in Health Connect settings.
        refreshSiteStatus(explicit = false) // Picks up ChatGPT access turned on or off on the Site.
    }

    // region State

    private fun loadSettings() {
        val keys = prefs.getStringSet("selected", null)
        selected.clear()
        if (keys == null) selected += Metric.entries else selected += keys.mapNotNull { Metric.fromKey(it) }
        rangeDays = prefs.getInt("days", ExportPlanner.DEFAULT_DAYS).takeIf { it in ExportPlanner.RANGE_OPTIONS }
            ?: ExportPlanner.DEFAULT_DAYS
    }

    private fun saveSettings() {
        prefs.edit().putStringSet("selected", selected.map { it.key }.toSet()).putInt("days", rangeDays).apply()
    }

    private fun refresh(afterPermissionRequest: Boolean = false) {
        sdkStatus = HealthConnectClient.getSdkStatus(this, providerPackage)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            reader = null
            granted = emptySet()
            grantedPermissions = emptySet()
            backgroundSupported = false
            pendingEnableAuto = false
            permissionsLoaded = false
            render()
            return
        }
        val r = reader ?: HealthConnectReader(HealthConnectClient.getOrCreate(this)).also { reader = it }
        backgroundSupported = sync.health.backgroundReadSupported()
        lifecycleScope.launch {
            grantedPermissions = try {
                r.grantedPermissions()
            } catch (e: Exception) {
                emptySet()
            }
            granted = Metric.entries.filter { it.readPermission in grantedPermissions }.toSet()
            permissionsLoaded = true
            if (afterPermissionRequest && pendingChooseData) {
                pendingChooseData = false
                // The categories allowed in Health Connect's dialog become the selection.
                val allowed = selected.intersect(granted)
                if (allowed.isNotEmpty() && allowed != selected) {
                    selected.retainAll(allowed)
                    saveSettings()
                    onCategoriesChanged()
                }
                chooseDataDenied = allowed.isEmpty()
                if (chooseDataDenied) showSyncMessage(getString(R.string.choose_data_none), Tone.WARN)
            } else if (afterPermissionRequest && (selected - granted).isNotEmpty()) {
                showSyncMessage(getString(R.string.permission_denied_hint), Tone.WARN)
            }
            if (pendingEnableAuto) {
                pendingEnableAuto = false
                finishEnablingAutoSync()
            } else {
                resumeAutoSyncIfPossible()
            }
            render()
        }
    }

    private val backgroundGranted: Boolean
        get() = SyncEngine.BACKGROUND_PERMISSION in grantedPermissions

    private fun autoSyncReady(): Boolean =
        backgroundSupported && backgroundGranted && selected.isNotEmpty() && (selected - granted).isEmpty()

    /** Restarts paused automatic sync once the user has fixed what paused it (permissions, categories). */
    private fun resumeAutoSyncIfPossible() {
        if (!sync.settings.autoSync || !sync.credentials.isPaired()) return
        val paused = sync.state.load().paused
        if (paused != null && !paused.resumable) return
        if (!autoSyncReady()) return
        if (paused != null) {
            sync.state.clearPause()
            SyncScheduler.enable(this)
        } else {
            SyncScheduler.ensurePeriodic(this)
        }
    }

    /**
     * Asks the Site whether this phone is still connected and whether ChatGPT access is on.
     * Reads no Health Connect data. Automatic checks (on resume) are throttled; failures keep
     * the last known values, labelled with the time they were checked.
     */
    private fun refreshSiteStatus(explicit: Boolean) {
        if (checkingStatus || !sync.credentials.isPaired()) return
        val last = sync.state.loadSite().lastCheckAt
        if (!explicit && last != null && Duration.between(last, Instant.now()).abs() < AUTO_CHECK_MIN_INTERVAL) return
        checkingStatus = true
        renderSync()
        lifecycleScope.launch {
            val result = try {
                sync.statusRefresher().refresh()
            } finally {
                checkingStatus = false
            }
            if (result == SiteStatusRefresher.Result.REVOKED) showSyncMessage(getString(R.string.pause_revoked), Tone.WARN)
            render()
        }
    }

    // endregion

    // region Automatic sync

    private fun onCategoriesChanged() {
        if (!sync.settings.autoSync || !sync.credentials.isPaired()) return
        if (autoSyncReady()) {
            sync.state.clearPause()
            // Restart any queued run so the next upload uses the new selection.
            SyncScheduler.enable(this, replaceRunning = true)
        }
        renderSync()
    }

    /** Checks the pasted code locally. Nothing is sent until the code is valid and, without a built-in Site, its host is confirmed. */
    private fun connect() {
        when (val step = PairingFlow.start(codeInput.text.toString(), sync.policy, Instant.now())) {
            is PairingFlow.Step.Pair -> pair(step.code)
            is PairingFlow.Step.Confirm -> confirmSite(step)
            is PairingFlow.Step.Reject -> showSyncMessage(
                getString(
                    when (step.problem) {
                        ConnectionCodes.Problem.NO_SITE_CONFIGURED -> R.string.sync_not_configured
                        ConnectionCodes.Problem.MALFORMED -> R.string.code_malformed
                        ConnectionCodes.Problem.WRONG_SITE -> R.string.code_wrong_site
                        ConnectionCodes.Problem.UNSAFE_SITE -> R.string.code_unsafe_site
                        ConnectionCodes.Problem.EXPIRED -> R.string.code_expired
                    },
                ),
                Tone.ERROR,
            )
        }
    }

    /** Shows the code's hostname and pairs only after an explicit Connect. Cancel sends nothing. */
    private fun confirmSite(step: PairingFlow.Step.Confirm) {
        val body = SpannableStringBuilder()
        body.append(step.host, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        body.append("\n\n").append(getString(R.string.confirm_site_body))
        if (!step.chatgptSite) body.append("\n\n").append(getString(R.string.confirm_site_unusual))
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_site_title)
            .setMessage(body)
            .setNegativeButton(R.string.cancel) { _, _ -> showSyncMessage(getString(R.string.connect_cancelled), Tone.INFO) }
            .setOnCancelListener { showSyncMessage(getString(R.string.connect_cancelled), Tone.INFO) }
            .setPositiveButton(R.string.connect) { _, _ -> pair(step.code) }
            .show()
    }

    private fun pair(code: ConnectionCode) {
        val origin = code.origin
        syncBusy = true
        showSyncMessage(getString(R.string.connecting), Tone.INFO)
        renderSync()
        lifecycleScope.launch {
            val name = SyncComponents.deviceName()
            val result = withContext(Dispatchers.IO) { sync.api(origin, code.serviceToken).pair(code.pairingToken, name) }
            syncBusy = false
            when (result) {
                is ApiResult.Paired -> {
                    // Pairing replaced any earlier device on the Site; drop local state from it.
                    SyncScheduler.cancelAll(this@MainActivity)
                    sync.settings.bumpGeneration()
                    sync.state.clear()
                    sync.credentials.save(
                        SyncCredentials(origin, code.serviceToken, result.deviceId, result.deviceToken, result.deviceName, Instant.now()),
                    )
                    codeInput.setText("")
                    clearClipboardIfHolds(code)
                    showSyncMessage(getString(R.string.connected, result.deviceName), Tone.OK)
                    if (sync.settings.autoSync && autoSyncReady()) SyncScheduler.enable(this@MainActivity)
                    refreshSiteStatus(explicit = true)
                }
                is ApiResult.Failed -> showSyncMessage(
                    getString(
                        when (result.rejection) {
                            Rejection.PAIRING_INVALID -> R.string.code_expired
                            Rejection.ACCESS_DENIED -> R.string.pair_access_denied
                            Rejection.TRANSIENT -> R.string.pair_offline
                            else -> R.string.pair_failed
                        },
                    ),
                    Tone.ERROR,
                )
                else -> showSyncMessage(getString(R.string.pair_failed), Tone.ERROR)
            }
            render()
        }
    }

    private fun clearClipboardIfHolds(code: ConnectionCode) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        try {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return
            val held = ConnectionCodes.parse(text, sync.policy, Instant.EPOCH)
            if (held is ConnectionCodes.Result.Ok && held.code.pairingToken == code.pairingToken) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
        }
    }

    private fun pasteCode() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) return showSyncMessage(getString(R.string.clipboard_empty), Tone.WARN)
        codeInput.setText(text)
    }

    private fun onAutoSwitch(checked: Boolean) {
        if (!checked) {
            sync.settings.autoSync = false
            sync.settings.bumpGeneration()
            SyncScheduler.cancelAll(this)
            showSyncMessage(getString(R.string.auto_turned_off), Tone.INFO)
            renderSync()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_consent_title)
            .setMessage(getString(R.string.auto_consent_body, selected.sortedBy { it.ordinal }.joinToString { it.label }))
            .setNegativeButton(R.string.cancel) { _, _ -> renderSync() }
            .setOnCancelListener { renderSync() }
            .setPositiveButton(R.string.auto_consent_accept) { _, _ -> beginEnablingAutoSync() }
            .show()
    }

    private fun beginEnablingAutoSync() {
        if (selected.isEmpty()) {
            showSyncMessage(getString(R.string.need_selection), Tone.WARN)
            return renderSync()
        }
        val request = (selected - granted).map { it.readPermission }.toMutableSet()
        if (!backgroundGranted) request += SyncEngine.BACKGROUND_PERMISSION
        if (request.isEmpty()) return finishEnablingAutoSync()
        pendingEnableAuto = true
        try {
            permissionLauncher.launch(request)
        } catch (_: ActivityNotFoundException) {
            pendingEnableAuto = false
            openHealthConnectPermissions()
            renderSync()
        }
    }

    private fun finishEnablingAutoSync() {
        if (autoSyncReady()) {
            sync.settings.autoSync = true
            sync.state.clearPause()
            SyncScheduler.enable(this)
            showSyncMessage(getString(R.string.auto_turned_on), Tone.OK)
        } else {
            sync.settings.autoSync = false
            val missing = (selected - granted).sortedBy { it.ordinal }.map { it.label }
            showSyncMessage(
                if (!backgroundGranted) getString(R.string.auto_needs_background)
                else getString(R.string.need_grant, missing.joinToString()),
                Tone.WARN,
            )
        }
        renderSync()
    }

    private fun syncNow() {
        if (backgroundSupported && backgroundGranted) {
            // The status rows show the queued run; no separate message that could go stale.
            sync.state.clearPause()
            SyncScheduler.syncNow(this)
            hideSyncMessage()
            return renderSync()
        }
        // No background access: read while the app is on screen.
        syncBusy = true
        foregroundSyncRunning = true
        showSyncMessage(getString(R.string.sync_foreground_running), Tone.INFO, transient = true)
        renderSync()
        lifecycleScope.launch {
            val outcome = sync.engine().run(SyncTrigger.MANUAL, inBackground = false)
            sync.state.record(outcome, Instant.now(), willRetry = false)
            if (outcome is SyncOutcome.Paused && outcome.reason == PauseReason.DEVICE_REVOKED) {
                sync.credentials.clear()
                sync.settings.bumpGeneration()
            }
            syncBusy = false
            foregroundSyncRunning = false
            when (outcome) {
                is SyncOutcome.Success -> showSyncMessage(getString(R.string.sync_done, outcome.days), Tone.OK, transient = true)
                is SyncOutcome.Paused -> showSyncMessage(pauseText(outcome.reason), Tone.WARN, transient = true)
                is SyncOutcome.Failed -> showSyncMessage(getString(R.string.sync_failed, failText(outcome.reason)), Tone.ERROR, transient = true)
                SyncOutcome.Superseded -> hideSyncMessage()
            }
            render()
        }
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disconnect_title)
            .setMessage(R.string.disconnect_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disconnect) { _, _ -> disconnect() }
            .show()
    }

    private fun disconnect() {
        // Local first: cancel work and erase credentials so nothing can upload after this point.
        val previous = sync.forgetPairing()
        render()
        val client = previous?.let { sync.api(it) } ?: return showSyncMessage(getString(R.string.disconnected_local), Tone.OK)
        syncBusy = true
        renderSync()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.disconnect(previous.deviceToken) }
            syncBusy = false
            showSyncMessage(
                getString(if (result is ApiResult.Disconnected) R.string.disconnected_remote else R.string.disconnected_offline),
                if (result is ApiResult.Disconnected) Tone.OK else Tone.WARN,
            )
            renderSync()
        }
    }

    /** Opens the paired (or built-in) private Site at its ChatGPT access card. Only the owner's switch there can turn access on. */
    private fun openAccessSettings() = openSite("/#$ACCESS_ANCHOR")

    /** Opens only the built-in Site or the one confirmed when pairing; never an origin from an unconfirmed code. */
    private fun openSite(path: String = "/") {
        val origin = sync.siteOrigin() ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(origin + path)))
        } catch (_: ActivityNotFoundException) {
            showSyncMessage(getString(R.string.no_browser), Tone.ERROR)
        }
    }

    /** First run: one Health Connect dialog for the selected categories, where the user picks what to share. */
    private fun chooseData() {
        if (selected.isEmpty()) return openSettings()
        if (chooseDataDenied) return openHealthConnectPermissions()
        val request = (selected - granted).map { it.readPermission }.toSet()
        if (request.isEmpty()) return render()
        pendingChooseData = true
        try {
            permissionLauncher.launch(request)
        } catch (_: ActivityNotFoundException) {
            pendingChooseData = false
            openHealthConnectPermissions()
        }
    }

    private fun showCodeHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.code_help_title)
            .setMessage(R.string.code_help_body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Asks for everything sync is missing in one Health Connect dialog, or opens its settings if nothing can be asked. */
    private fun fixHealthConnect() {
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) return openProviderInStore()
        val request = (selected - granted).map { it.readPermission }.toMutableSet()
        if (sync.settings.autoSync && backgroundSupported && !backgroundGranted) request += SyncEngine.BACKGROUND_PERMISSION
        if (request.isEmpty()) return openHealthConnectPermissions()
        try {
            permissionLauncher.launch(request)
        } catch (_: ActivityNotFoundException) {
            openHealthConnectPermissions()
        }
    }

    private fun openSettings() {
        settingsOpen = true
        settingsSection.render()
        scroll.post { scroll.smoothScrollTo(0, settingsSection.container.top) }
    }

    private fun runAction(action: CalloutAction) {
        when (action) {
            CalloutAction.NONE -> Unit
            CalloutAction.TURN_ON_AUTO -> onAutoSwitch(true)
            CalloutAction.SYNC_NOW -> syncNow()
            CalloutAction.FIX_HEALTH_CONNECT -> fixHealthConnect()
            CalloutAction.OPEN_ACCESS_SETTINGS -> openAccessSettings()
            CalloutAction.REFRESH_STATUS -> refreshSiteStatus(explicit = true)
            CalloutAction.OPEN_SETTINGS -> openSettings()
            CalloutAction.CHOOSE_DATA -> chooseData()
        }
    }

    private fun pauseText(reason: PauseReason): String = when (reason) {
        PauseReason.PERMISSION_MISSING ->
            getString(R.string.pause_permission, (selected - granted).sortedBy { it.ordinal }.joinToString { it.label }.ifEmpty { "a selected category" })
        else -> getString(
            when (reason) {
                PauseReason.NOT_PAIRED -> R.string.pause_not_paired
                PauseReason.DEVICE_REVOKED -> R.string.pause_revoked
                PauseReason.SITE_ACCESS_DENIED -> R.string.pause_access_denied
                PauseReason.HEALTH_CONNECT_UNAVAILABLE -> R.string.pause_hc_unavailable
                PauseReason.BACKGROUND_UNSUPPORTED -> R.string.pause_background_unsupported
                PauseReason.BACKGROUND_PERMISSION_MISSING -> R.string.pause_background_permission
                PauseReason.NO_CATEGORIES -> R.string.pause_no_categories
                PauseReason.PERMISSION_MISSING -> R.string.pause_no_categories
            },
        )
    }

    private fun failText(reason: FailReason): String = getString(
        when (reason) {
            FailReason.OFFLINE_OR_SERVER -> R.string.fail_offline
            FailReason.HEALTH_CONNECT_ERROR -> R.string.fail_health_connect
            FailReason.STALE -> R.string.fail_stale
            FailReason.REJECTED -> R.string.fail_rejected
            FailReason.TIMEZONE_UNSUPPORTED -> R.string.fail_timezone
            FailReason.OUT_OF_BOUNDS -> R.string.fail_bounds
            FailReason.READ_INTERRUPTED -> R.string.fail_interrupted
            FailReason.UNEXPECTED -> R.string.fail_unexpected
        },
    )

    private fun checkFailText(failure: StatusCheckFailure): String = getString(
        when (failure) {
            StatusCheckFailure.OFFLINE -> R.string.check_fail_offline
            StatusCheckFailure.SITE_OUTDATED -> R.string.check_fail_outdated
            StatusCheckFailure.ACCESS_DENIED -> R.string.check_fail_denied
            StatusCheckFailure.UNEXPECTED -> R.string.check_fail_unexpected
        },
    )

    private fun showSyncMessage(text: String, tone: Tone, transient: Boolean = false) {
        if (!::syncMessage.isInitialized) return
        syncMessage.text = text
        syncMessage.setTextColor(getColor(toneColor(tone)))
        syncMessage.visibility = View.VISIBLE
        messageTransient = transient
    }

    private fun hideSyncMessage() {
        if (::syncMessage.isInitialized) syncMessage.visibility = View.GONE
        messageTransient = false
    }

    private fun currentHome(credentials: SyncCredentials?): HomeStatus {
        val available = sdkStatus == HealthConnectClient.SDK_AVAILABLE
        return HomeStatusModel.build(
            HomeInputs(
                siteConfigured = sync.policy !is SitePolicy.Misconfigured,
                paired = credentials != null,
                healthConnectAvailable = available,
                backgroundSupported = available && backgroundSupported,
                autoSyncOn = sync.settings.autoSync,
                state = sync.state.load(),
                site = sync.state.loadSite(),
                work = work,
                online = sync.online(),
                foregroundSyncRunning = foregroundSyncRunning,
                checkingStatus = checkingStatus,
                permissionMissing = permissionsLoaded && available && credentials != null &&
                    ((selected - granted).isNotEmpty() || (sync.settings.autoSync && backgroundSupported && !backgroundGranted)),
                dataChosen = !(permissionsLoaded && available && credentials != null && selected.intersect(granted).isEmpty()),
            ),
        )
    }

    private fun renderSync() {
        if (!::statusCard.isInitialized) return
        // Decrypted once per render; never displayed or logged.
        val credentials = sync.credentials.load()
        val h = currentHome(credentials)
        home = h
        if (lastActivity != null && h.upload.activity != lastActivity && messageTransient) hideSyncMessage()
        lastActivity = h.upload.activity
        val available = sdkStatus == HealthConnectClient.SDK_AVAILABLE
        val connected = h.connection == ConnectionState.CONNECTED

        renderStatus(h)
        renderConnectionRow(h, credentials)
        renderUploadRow(h)
        renderBackgroundRow(h)
        renderAccessRow(h)

        // Secondary copies of the main action are hidden while the main button offers the same thing.
        refreshButton.visibility = if (connected && h.overview.action != CalloutAction.REFRESH_STATUS) View.VISIBLE else View.GONE
        refreshButton.isEnabled = !checkingStatus
        refreshButton.text = getString(if (checkingStatus) R.string.checking_status else R.string.refresh_status)

        pairCard.visibility =
            if (h.connection == ConnectionState.NOT_PAIRED || h.connection == ConnectionState.REVOKED) View.VISIBLE else View.GONE
        connectButton.isEnabled = !syncBusy
        autoSwitch.setOnCheckedChangeListener(null)
        autoSwitch.isChecked = sync.settings.autoSync && backgroundSupported
        autoSwitch.isEnabled = available && backgroundSupported && !syncBusy
        autoSwitch.visibility = if (connected && available && backgroundSupported) View.VISIBLE else View.GONE
        autoSwitch.setOnCheckedChangeListener { _, checked -> onAutoSwitch(checked) }
        val running = h.upload.activity == UploadActivity.RUNNING
        syncNowButton.visibility = if (connected && h.overview.action != CalloutAction.SYNC_NOW) View.VISIBLE else View.GONE
        syncNowButton.isEnabled = available && !syncBusy && !running
        syncNowButton.text = getString(if (running) R.string.syncing_now_button else R.string.sync_now)
        disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
        disconnectButton.isEnabled = !syncBusy
        openSiteButton.visibility = if (sync.siteOrigin(credentials) != null) View.VISIBLE else View.GONE
        renderTechnicalDetails(h, credentials)
    }

    private fun renderStatus(h: HomeStatus) {
        val o = h.overview
        val tone = when (o.overall) {
            Overall.READY, Overall.CONNECTED -> StatusTone.OK
            Overall.NEEDS_ATTENTION -> StatusTone.WARN
            Overall.SYNCING, Overall.WAITING -> StatusTone.INFO
            Overall.NOT_CONNECTED -> StatusTone.NEUTRAL
        }
        val title = getString(
            when (o.overall) {
                Overall.READY -> R.string.overall_ready
                Overall.CONNECTED -> R.string.overall_connected
                Overall.NEEDS_ATTENTION -> R.string.overall_attention
                Overall.SYNCING -> R.string.overall_syncing
                Overall.WAITING -> R.string.overall_waiting
                Overall.NOT_CONNECTED -> R.string.overall_not_connected
            },
        )
        val step = HomeStatusModel.setupStep(h)
        statusStep.visibility = if (step == null) View.GONE else View.VISIBLE
        if (step != null) {
            val label = getString(
                when (step) {
                    1 -> R.string.setup_step_1
                    2 -> R.string.setup_step_2
                    else -> R.string.setup_step_3
                },
            )
            statusStep.text = getString(R.string.setup_step, step, label)
        }
        statusTitle.text = getString(R.string.with_symbol, symbol(tone), title)
        statusTitle.contentDescription = title
        statusTitle.setTextColor(getColor(toneFg(tone)))
        (statusCard.background as GradientDrawable).apply {
            setColor(getColor(toneBg(tone)))
            setStroke(dp(2), getColor(toneFg(tone)))
        }
        // The last successful sync stays visible whatever else is happening.
        val connected = h.connection == ConnectionState.CONNECTED
        statusSync.visibility = if (connected) View.VISIBLE else View.GONE
        statusSync.text = h.upload.lastSuccessAt?.let { getString(R.string.last_sync, shortWhen(it)) } ?: getString(R.string.last_sync_never)
        val note = noteText(h)
        statusNote.text = note.orEmpty()
        statusNote.visibility = if (note == null) View.GONE else View.VISIBLE

        // Installing from the store cannot help where Health Connect is not supported at all.
        val action = o.action.takeUnless { it == CalloutAction.FIX_HEALTH_CONNECT && sdkStatus == HealthConnectClient.SDK_UNAVAILABLE }
            ?: CalloutAction.NONE
        if (action == CalloutAction.NONE) {
            primaryButton.visibility = View.GONE
            return
        }
        primaryButton.visibility = View.VISIBLE
        primaryButton.text = getString(
            when (action) {
                CalloutAction.TURN_ON_AUTO -> R.string.action_turn_on_auto
                CalloutAction.SYNC_NOW -> R.string.sync_now
                CalloutAction.FIX_HEALTH_CONNECT ->
                    if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) R.string.action_fix_health_connect else R.string.hc_install_update
                CalloutAction.OPEN_ACCESS_SETTINGS -> if (o.note == OverviewNote.ACCESS_OFF) R.string.action_turn_on_access else R.string.action_open_access
                CalloutAction.REFRESH_STATUS -> if (checkingStatus) R.string.checking_status else R.string.refresh_status
                CalloutAction.OPEN_SETTINGS -> R.string.action_choose_categories
                CalloutAction.CHOOSE_DATA -> R.string.continue_label
                CalloutAction.NONE -> R.string.refresh_status
            },
        )
        primaryButton.contentDescription =
            if (action == CalloutAction.OPEN_ACCESS_SETTINGS) getString(R.string.action_open_access_description) else null
        primaryButton.isEnabled = when (action) {
            CalloutAction.REFRESH_STATUS -> !checkingStatus
            CalloutAction.SYNC_NOW, CalloutAction.TURN_ON_AUTO -> !syncBusy && sdkStatus == HealthConnectClient.SDK_AVAILABLE
            else -> true
        }
        primaryButton.setOnClickListener { runAction(action) }
    }

    /** One short line under the status. Full detail is in Details. */
    private fun noteText(h: HomeStatus): String? {
        val failure = h.upload.failure ?: FailReason.UNEXPECTED
        val checkFailure = h.access.failure ?: StatusCheckFailure.UNEXPECTED
        return when (h.overview.note) {
            OverviewNote.NONE, OverviewNote.NOT_PAIRED, OverviewNote.FIRST_SYNC_PENDING -> null
            OverviewNote.NO_SITE -> getString(R.string.sync_not_configured)
            OverviewNote.REVOKED -> getString(R.string.note_revoked)
            OverviewNote.HEALTH_CONNECT -> getString(
                if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) R.string.hc_update_required else R.string.hc_unavailable,
            )
            OverviewNote.PERMISSION -> {
                val missing = (selected - granted).sortedBy { it.ordinal }.map { it.label }
                when {
                    permissionsLoaded && missing.isNotEmpty() -> getString(R.string.note_permission, missing.joinToString())
                    permissionsLoaded && !backgroundGranted -> getString(R.string.note_background_permission)
                    else -> getString(R.string.note_permission_generic)
                }
            }
            OverviewNote.CHOOSE_DATA -> getString(R.string.note_choose_data)
            OverviewNote.PAUSED -> pauseText(h.upload.pause ?: PauseReason.NO_CATEGORIES)
            OverviewNote.QUEUED -> getString(R.string.note_queued)
            OverviewNote.WAITING_FOR_NETWORK -> getString(R.string.note_waiting_network)
            OverviewNote.RETRYING -> getString(R.string.note_retrying, failText(failure))
            OverviewNote.SYNC_FAILED -> getString(R.string.sync_failed, failText(failure))
            OverviewNote.ACCESS_OFF -> getString(if (h.access.current) R.string.note_access_off else R.string.note_access_off_last_known)
            OverviewNote.ACCESS_CHECKING -> getString(R.string.note_access_checking)
            OverviewNote.ACCESS_NOT_CHECKED -> getString(R.string.note_access_not_checked)
            OverviewNote.ACCESS_UNKNOWN ->
                if (h.access.failure == StatusCheckFailure.SITE_OUTDATED) getString(R.string.note_access_outdated)
                else getString(R.string.note_access_unknown, checkFailText(checkFailure))
            OverviewNote.ACCESS_STALE -> getString(R.string.note_access_stale, checkFailText(checkFailure))
        }
    }

    private fun renderConnectionRow(h: HomeStatus, credentials: SyncCredentials?) {
        val name = credentials?.deviceName.orEmpty().ifEmpty { getString(R.string.this_phone) }
        when (h.connection) {
            ConnectionState.CONNECTED ->
                connectionRow.set(getString(R.string.row_connected), StatusTone.OK, getString(R.string.row_connected_detail, name))
            ConnectionState.NOT_PAIRED ->
                connectionRow.set(getString(R.string.row_not_connected), StatusTone.NEUTRAL, getString(R.string.row_not_connected_detail))
            ConnectionState.REVOKED ->
                connectionRow.set(getString(R.string.row_revoked), StatusTone.WARN, getString(R.string.row_revoked_detail))
            ConnectionState.NOT_CONFIGURED ->
                connectionRow.set(getString(R.string.row_no_site), StatusTone.NEUTRAL, getString(R.string.sync_not_configured))
        }
    }

    private fun renderUploadRow(h: HomeStatus) {
        val u = h.upload
        val lines = mutableListOf<String>()
        u.lastSuccessAt?.let { lines += getString(R.string.row_last_success, whenText(it)) }
        u.lastSuccessRange?.let { lines += getString(R.string.row_covered, rangeText(it)) }
        val note: Pair<String, StatusTone>? = when (u.activity) {
            UploadActivity.IDLE -> null
            UploadActivity.QUEUED -> getString(R.string.activity_queued) to StatusTone.INFO
            UploadActivity.WAITING_FOR_NETWORK -> getString(R.string.activity_waiting_network) to StatusTone.INFO
            UploadActivity.RUNNING -> getString(R.string.activity_running) to StatusTone.INFO
            UploadActivity.RETRYING -> getString(R.string.activity_retrying, failText(u.failure ?: FailReason.UNEXPECTED)) to StatusTone.WARN
            UploadActivity.FAILED -> getString(R.string.activity_failed, failText(u.failure ?: FailReason.UNEXPECTED)) to StatusTone.ERROR
            UploadActivity.PAUSED -> getString(R.string.activity_paused, pauseText(u.pause ?: PauseReason.NO_CATEGORIES)) to StatusTone.WARN
        }
        when (u.phase) {
            UploadPhase.COMPLETED -> uploadRow.set(getString(R.string.row_sync_completed), StatusTone.OK, lines.joinToString("\n"), note)
            UploadPhase.RUNNING -> uploadRow.set(getString(R.string.row_sync_running), StatusTone.INFO, getString(R.string.row_sync_first_running), null)
            UploadPhase.QUEUED -> uploadRow.set(getString(R.string.row_sync_queued), StatusTone.INFO, getString(R.string.row_sync_first_queued), note)
            UploadPhase.NOT_DONE -> uploadRow.set(
                getString(R.string.row_sync_not_done),
                if (h.connection == ConnectionState.CONNECTED) StatusTone.WARN else StatusTone.NEUTRAL,
                if (lines.isEmpty()) getString(R.string.row_sync_none) else lines.joinToString("\n"),
                note,
            )
        }
    }

    private fun renderBackgroundRow(h: HomeStatus) {
        val b = h.background
        backgroundRow.hideAction()
        when (b.state) {
            BackgroundState.ON -> backgroundRow.set(
                getString(R.string.row_on),
                StatusTone.OK,
                b.nextRunAt?.let { getString(R.string.row_background_next, timeText(it)) } ?: getString(R.string.row_background_on),
            )
            BackgroundState.PAUSED -> {
                backgroundRow.set(getString(R.string.row_paused), StatusTone.WARN, pauseText(b.pause ?: PauseReason.NO_CATEGORIES))
                if (b.pause == PauseReason.BACKGROUND_PERMISSION_MISSING || b.pause == PauseReason.PERMISSION_MISSING ||
                    b.pause == PauseReason.HEALTH_CONNECT_UNAVAILABLE
                ) {
                    backgroundRow.showAction(getString(R.string.action_fix_health_connect)) { fixHealthConnect() }
                }
            }
            BackgroundState.OFF -> backgroundRow.set(getString(R.string.row_off), StatusTone.NEUTRAL, getString(R.string.row_background_off))
            BackgroundState.UNAVAILABLE ->
                backgroundRow.set(getString(R.string.row_unavailable), StatusTone.NEUTRAL, getString(R.string.row_background_unavailable))
            BackgroundState.NOT_APPLICABLE ->
                backgroundRow.set(getString(R.string.row_off), StatusTone.NEUTRAL, getString(R.string.row_connect_first))
        }
    }

    private fun renderAccessRow(h: HomeStatus) {
        val a = h.access
        val checked = a.checkedAt?.let { whenText(it) }
        val refreshing = if (a.refreshing) "\n" + getString(R.string.access_refreshing) else ""
        when (a.state) {
            AccessState.ON ->
                if (a.current) accessRow.set(getString(R.string.row_on), StatusTone.OK, getString(R.string.access_on_detail, checked) + refreshing)
                else accessRow.set(
                    getString(R.string.row_on_last_known),
                    StatusTone.NEUTRAL,
                    getString(R.string.access_on_stale_detail, checked, checkFailText(a.failure ?: StatusCheckFailure.UNEXPECTED)) + refreshing,
                )
            AccessState.OFF ->
                if (a.current) accessRow.set(getString(R.string.row_off), StatusTone.WARN, getString(R.string.access_off_detail, checked) + refreshing)
                else accessRow.set(
                    getString(R.string.row_off_last_known),
                    StatusTone.WARN,
                    getString(R.string.access_off_stale_detail, checked, checkFailText(a.failure ?: StatusCheckFailure.UNEXPECTED)) + refreshing,
                )
            AccessState.CHECKING -> accessRow.set(getString(R.string.row_checking), StatusTone.INFO, getString(R.string.access_checking_detail))
            AccessState.UNKNOWN -> when (a.failure) {
                null -> accessRow.set(getString(R.string.row_not_checked), StatusTone.NEUTRAL, getString(R.string.access_unchecked_detail))
                else -> accessRow.set(
                    getString(R.string.row_couldnt_check),
                    StatusTone.NEUTRAL,
                    getString(R.string.access_failed_detail, checkFailText(a.failure)),
                )
            }
            AccessState.NOT_APPLICABLE -> accessRow.set(getString(R.string.row_unknown), StatusTone.NEUTRAL, getString(R.string.row_connect_first))
        }
    }

    private fun renderTechnicalDetails(h: HomeStatus, credentials: SyncCredentials?) {
        val state = sync.state.load()
        val site = sync.state.loadSite()
        val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        val lines = mutableListOf<String>()
        lines += getString(
            R.string.tech_site,
            sync.siteOrigin(credentials)?.let(SyncOrigin::host)
                ?: getString(if (sync.policy is SitePolicy.Generic) R.string.tech_none_generic else R.string.tech_none),
        )
        lines += getString(if (sync.policy is SitePolicy.Pinned) R.string.tech_build_pinned else R.string.tech_build_generic)
        credentials?.let { lines += getString(R.string.tech_paired, it.deviceName, time.format(it.pairedAt)) }
        lines += getString(
            R.string.tech_health_connect,
            getString(
                when (sdkStatus) {
                    HealthConnectClient.SDK_AVAILABLE -> R.string.hc_available
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> R.string.hc_update_required
                    else -> R.string.hc_unavailable
                },
            ),
        )
        lines += getString(R.string.tech_background_feature, getString(if (backgroundSupported) R.string.tech_yes else R.string.tech_no))
        lines += getString(R.string.tech_background_permission, getString(if (backgroundGranted) R.string.tech_yes else R.string.tech_no))
        if (sync.settings.autoSync && h.background.state == BackgroundState.ON) {
            lines += work.nextRunAt?.let { getString(R.string.next_run_at, timeText(it)) } ?: getString(R.string.next_run_unknown)
        }
        state.lastAttemptAt?.let { at ->
            lines += if (state.lastFailure != null) getString(R.string.last_attempt_failed, time.format(at), failText(state.lastFailure))
            else getString(R.string.tech_last_attempt, time.format(at))
        }
        site.siteLastSyncAt?.let { lines += getString(R.string.tech_site_last_sync, time.format(it)) }
        site.lastCheckAt?.let { at ->
            lines += site.lastCheckFailure?.let { getString(R.string.tech_last_check_failed, time.format(at), checkFailText(it)) }
                ?: getString(R.string.tech_last_check, time.format(at))
        }
        lines += getString(R.string.auto_scheduling_note)
        lines += getString(R.string.not_chatgpt_health)
        lines += getString(R.string.tech_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        technicalDetails.text = lines.joinToString("\n")
    }

    private fun whenText(instant: Instant): String {
        val absolute = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)
        val now = System.currentTimeMillis()
        val millis = instant.toEpochMilli()
        if (millis > now + 60_000) return absolute
        val relative = DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
        return getString(R.string.time_with_relative, absolute, relative)
    }

    /** "3:42 PM (5 minutes ago)" today, otherwise with the date. */
    private fun shortWhen(instant: Instant): String {
        val zone = ZoneId.systemDefault()
        if (instant.atZone(zone).toLocalDate() != LocalDate.now(zone)) return whenText(instant)
        val relative = DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        return getString(R.string.time_with_relative, timeText(instant), relative)
    }

    private fun timeText(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)

    /** "2026-09-28 to 2026-10-27" as stored by the sync state, shown with localized dates. */
    private fun rangeText(range: String): String {
        val parts = range.split(" to ")
        if (parts.size != 2) return range
        return try {
            val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            getString(R.string.range_from_to, f.format(LocalDate.parse(parts[0])), f.format(LocalDate.parse(parts[1])))
        } catch (_: Exception) {
            range
        }
    }

    // endregion

    // region Actions

    private fun requestSelectedPermissions() {
        val toRequest = (selected - granted).map { it.readPermission }.toSet()
        if (toRequest.isEmpty()) return
        try {
            permissionLauncher.launch(toRequest)
        } catch (e: ActivityNotFoundException) {
            openHealthConnectPermissions()
        }
    }

    private fun openHealthConnectPermissions() {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 34) {
                add(Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, packageName))
            }
            add(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        }
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun openProviderInStore() {
        val uri = Uri.parse("market://details?id=$providerPackage&url=healthconnect%3A%2F%2Fonboarding")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")
            .putExtra("overlay", true).putExtra("callerId", packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$providerPackage")))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun createExport() {
        val r = reader ?: return
        val metrics = Metric.entries.filter { it in selected }
        if (metrics.isEmpty()) return showMessage(getString(R.string.need_selection), Tone.WARN)
        val missing = metrics.filter { it !in granted }
        if (missing.isNotEmpty()) return showMessage(getString(R.string.need_grant, missing.joinToString { it.label }), Tone.WARN)
        val zone = ZoneId.systemDefault()
        if (!ExportJson.isSupportedZone(zone)) return showMessage(getString(R.string.export_failed_timezone, zone.id), Tone.ERROR)

        // The snapshot time is fixed before reading so day windows and generatedAt agree.
        val generatedAt = Instant.now()
        val windows = ExportPlanner.windows(generatedAt.atZone(zone).toLocalDate(), rangeDays, zone)
        busy = true
        showMessage(getString(R.string.exporting), Tone.INFO)
        render()
        lifecycleScope.launch {
            try {
                val days = r.read(windows, metrics)
                val bad = ExportJson.firstOutOfBounds(days)
                if (bad != null) {
                    showMessage(getString(R.string.export_failed_bounds, bad.first.label, bad.second.toString()), Tone.ERROR)
                    return@launch
                }
                val json = ExportJson.build(generatedAt, zone, metrics, days)
                withContext(Dispatchers.IO) { store.save(json, generatedAt) }
                preview = metrics to days
                val range = "${days.first().window.date} to ${days.last().window.date}"
                showMessage(getString(R.string.export_done, days.size, range) + "\n" + getString(R.string.upload_hint), Tone.OK)
            } catch (_: HealthReadInterruptedException) {
                showMessage(getString(R.string.export_failed_interrupted), Tone.ERROR)
            } catch (e: PermissionRevokedException) {
                granted = granted - e.metrics.toSet()
                showMessage(getString(R.string.export_failed_permission, e.metrics.joinToString { it.label }), Tone.ERROR)
            } catch (e: Exception) {
                // Only the exception type is shown; messages could contain request details.
                showMessage(getString(R.string.export_failed_generic, e.javaClass.simpleName), Tone.ERROR)
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun describeFile(file: File): String = file.name.removePrefix("health-connect-export-").removeSuffix(".json")

    private fun confirmThen(file: File, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_share_title)
            .setMessage(getString(R.string.confirm_share_body, describeFile(file)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_label) { _, _ -> action() }
            .show()
    }

    private fun share(file: File) = confirmThen(file) {
        val uri = store.uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.no_share_target), Tone.ERROR)
        }
    }

    private fun save(file: File) = confirmThen(file) {
        pendingSave = file
        try {
            saveLauncher.launch(file.name)
        } catch (_: ActivityNotFoundException) {
            pendingSave = null
            showMessage(getString(R.string.save_failed), Tone.ERROR)
        }
    }

    private fun saveCopy(file: File, uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = store.read(file).toByteArray(Charsets.UTF_8)
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
                } catch (_: Exception) {
                    false
                }
            }
            showMessage(getString(if (ok) R.string.saved_to_files else R.string.save_failed), if (ok) Tone.OK else Tone.ERROR)
        }
    }

    private fun confirmDelete(file: File?) {
        AlertDialog.Builder(this)
            .setTitle(if (file == null) R.string.delete_all else R.string.confirm_delete_title)
            .setMessage(if (file == null) R.string.confirm_delete_all_body else R.string.confirm_delete_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (file == null) store.deleteAll() else store.delete(file)
                if (store.list().isEmpty()) preview = null
                render()
            }
            .show()
    }

    // endregion

    // region UI

    private enum class Tone { INFO, OK, WARN, ERROR }

    private enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.INFO -> R.color.muted
        Tone.OK -> R.color.ok
        Tone.WARN -> R.color.warn
        Tone.ERROR -> R.color.error
    }

    private fun toneFg(tone: StatusTone): Int = when (tone) {
        StatusTone.NEUTRAL -> R.color.text
        StatusTone.INFO -> R.color.info
        StatusTone.OK -> R.color.ok
        StatusTone.WARN -> R.color.warn
        StatusTone.ERROR -> R.color.error
    }

    private fun toneBg(tone: StatusTone): Int = when (tone) {
        StatusTone.NEUTRAL -> R.color.neutral_bg
        StatusTone.INFO -> R.color.info_bg
        StatusTone.OK -> R.color.ok_bg
        StatusTone.WARN -> R.color.warn_bg
        StatusTone.ERROR -> R.color.error_bg
    }

    /** A text symbol next to each status, so state is never conveyed by color alone. */
    private fun symbol(tone: StatusTone): String = when (tone) {
        StatusTone.OK -> "✓"
        StatusTone.WARN -> "!"
        StatusTone.ERROR -> "✕"
        StatusTone.INFO -> "…"
        StatusTone.NEUTRAL -> "•"
    }

    private fun showMessage(text: String, tone: Tone) {
        if (!::message.isInitialized) return
        message.text = text
        message.setTextColor(getColor(toneColor(tone)))
        message.visibility = View.VISIBLE
    }

    private fun render() {
        val available = sdkStatus == HealthConnectClient.SDK_AVAILABLE
        renderCategories()
        manageButton.isEnabled = available
        createButton.isEnabled = available && !busy && selected.isNotEmpty()
        createButton.text = getString(if (busy) R.string.exporting else R.string.create_export)
        renderPreview()
        renderExports()
        renderSync()
    }

    private fun renderCategories() {
        val available = sdkStatus == HealthConnectClient.SDK_AVAILABLE
        for (metric in Metric.entries) {
            checkboxes[metric]?.apply {
                isChecked = metric in selected
                isEnabled = available && !busy
            }
            // Only a problem is shown; granted categories need no label.
            grantLabels[metric]?.visibility =
                if (available && permissionsLoaded && metric in selected && metric !in granted) View.VISIBLE else View.GONE
        }
        grantButton.visibility = if (available && permissionsLoaded && (selected - granted).isNotEmpty()) View.VISIBLE else View.GONE
        grantButton.isEnabled = !busy
        if (::createButton.isInitialized) createButton.isEnabled = available && !busy && selected.isNotEmpty()
    }

    private fun renderPreview() {
        val data = preview
        previewCard.visibility = if (data == null) View.GONE else View.VISIBLE
        previewBody.removeAllViews()
        if (data == null) return
        val (metrics, days) = data
        val table = TableLayout(this)
        table.addView(row(listOf("Date") + metrics.map { it.label }, header = true))
        val now = Instant.now()
        for (day in days.reversed()) {
            val date = day.window.date.toString() + if (now.isBefore(day.window.end.toInstant())) " (${getString(R.string.partial)})" else ""
            table.addView(row(listOf(date) + metrics.map { m -> day.values[m]?.let { format(m, it) } ?: getString(R.string.no_data) }))
        }
        previewBody.addView(HorizontalScrollView(this).apply { addView(table) })
    }

    private fun renderExports() {
        exportsBody.removeAllViews()
        val files = store.list()
        if (files.isEmpty()) {
            exportsBody.addView(text(getString(R.string.exports_empty), muted = true))
            return
        }
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        for (file in files) {
            val created = formatter.format(Instant.ofEpochMilli(file.lastModified()))
            val sizeKb = (file.length() + 1023) / 1024
            exportsBody.addView(text("$created · $sizeKb KB", bold = true).apply { setPadding(0, dp(10), 0, 0) })
            exportsBody.addView(text(file.name, muted = true, size = 12f))
            exportsBody.addView(button(getString(R.string.share), ButtonStyle.SECONDARY) { share(file) })
            exportsBody.addView(button(getString(R.string.save), ButtonStyle.SECONDARY) { save(file) })
            exportsBody.addView(button(getString(R.string.delete), ButtonStyle.DANGER) { confirmDelete(file) })
        }
        exportsBody.addView(button(getString(R.string.delete_all), ButtonStyle.DANGER) { confirmDelete(null) })
    }

    private fun format(metric: Metric, value: Double): String {
        val n = NumberFormat.getNumberInstance()
        return when (metric) {
            Metric.STEPS -> n.apply { maximumFractionDigits = 0 }.format(value)
            Metric.SLEEP -> {
                val total = Math.round(value)
                "${total / 60} h ${total % 60} min"
            }
            Metric.HEART_RATE -> "${n.apply { maximumFractionDigits = 0 }.format(value)} bpm"
            Metric.WEIGHT -> "${n.apply { maximumFractionDigits = 2 }.format(value)} kg"
            Metric.DISTANCE -> "${n.apply { maximumFractionDigits = 2 }.format(value / 1000)} km"
            Metric.ACTIVE_CALORIES -> "${n.apply { maximumFractionDigits = 0 }.format(value)} kcal"
        }
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        column.addView(heading(getString(R.string.app_name), size = 22f))
        column.addView(buildStatusCard())
        column.addView(buildPairCard())

        settingsSection = Section(getString(R.string.settings_title), nested = false, { settingsOpen }, { settingsOpen = it })
        buildSettings(settingsSection.body)
        column.addView(settingsSection.container)

        detailsSection = Section(getString(R.string.details_title), nested = false, { detailsOpen }, { detailsOpen = it })
        buildDetails(detailsSection.body)
        column.addView(detailsSection.container)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return scroll
    }

    private fun buildStatusCard(): LinearLayout {
        statusCard = card {
            statusStep = text("", size = 13f, bold = true, muted = true).apply { visibility = View.GONE }
            addView(statusStep.apply { setPadding(0, 0, 0, dp(2)) })
            statusTitle = heading("", size = 22f)
            // Announce status changes (e.g. "Ready") to screen readers without moving focus.
            statusTitle.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(statusTitle)
            statusSync = text("", size = 15f)
            addView(statusSync.apply { setPadding(0, dp(4), 0, 0) })
            statusNote = text("", size = 15f)
            addView(statusNote.apply { setPadding(0, dp(4), 0, 0) })
            primaryButton = button("") {}
            addView(primaryButton.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
            syncMessage = text("", size = 14f).apply {
                visibility = View.GONE
                setPadding(0, dp(10), 0, 0)
            }
            syncMessage.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(syncMessage)
        }
        return statusCard
    }

    private fun buildPairCard(): LinearLayout {
        pairCard = card {
            addView(heading(getString(R.string.pair_title)))
            addView(text(getString(R.string.pair_steps), muted = true, size = 14f).apply { setPadding(0, dp(4), 0, 0) })
            codeInput = EditText(this@MainActivity).apply {
                hint = getString(R.string.code_hint)
                // Masked, no suggestions or autofill: the code is a credential.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSingleLine = true
                textSize = 15f
                minHeight = dp(48)
            }
            addView(codeInput)
            addView(button(getString(R.string.paste_code), ButtonStyle.SECONDARY) { pasteCode() })
            connectButton = button(getString(R.string.connect)) { connect() }
            addView(connectButton)
            addView(text(getString(R.string.pair_help), muted = true, size = 13f).apply { setPadding(0, dp(8), 0, 0) })
            addView(button(getString(R.string.code_help_title), ButtonStyle.SECONDARY) { showCodeHelp() }.apply {
                background = null
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                textSize = 14f
            })
        }
        return pairCard
    }

    private fun buildSettings(box: LinearLayout) {
        autoSwitch = Switch(this).apply {
            text = getString(R.string.auto_switch)
            textSize = 16f
            minHeight = dp(48)
        }
        box.addView(autoSwitch)
        syncNowButton = button(getString(R.string.sync_now), ButtonStyle.SECONDARY) { syncNow() }
        box.addView(syncNowButton)

        box.addView(heading(getString(R.string.categories_title), size = 15f).apply { setPadding(0, dp(16), 0, 0) })
        for (metric in Metric.entries) {
            val checkbox = CheckBox(this).apply {
                text = metric.label
                textSize = 16f
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    val changed = if (checked) selected.add(metric) else selected.remove(metric)
                    saveSettings()
                    if (changed) onCategoriesChanged()
                    renderCategories()
                }
            }
            checkboxes[metric] = checkbox
            box.addView(checkbox)
            val status = text(getString(R.string.not_granted), size = 13f).apply {
                setTextColor(getColor(R.color.warn))
                setPadding(dp(32), 0, 0, dp(4))
                visibility = View.GONE
            }
            grantLabels[metric] = status
            box.addView(status)
        }
        grantButton = button(getString(R.string.grant_selected), ButtonStyle.SECONDARY) { requestSelectedPermissions() }
        box.addView(grantButton)
        manageButton = button(getString(R.string.hc_open_settings), ButtonStyle.SECONDARY) { openHealthConnectPermissions() }
        box.addView(manageButton)

        advancedSection = Section(getString(R.string.advanced_title), nested = true, { advancedOpen }, { advancedOpen = it })
        buildAdvanced(advancedSection.body)
        box.addView(advancedSection.container)
    }

    private fun buildDetails(box: LinearLayout) {
        connectionRow = StatusRow(getString(R.string.row_label_connection)).also { box.addView(it.container) }
        uploadRow = StatusRow(getString(R.string.row_label_sync)).also { box.addView(it.container) }
        backgroundRow = StatusRow(getString(R.string.row_label_background)).also { box.addView(it.container) }
        accessRow = StatusRow(getString(R.string.row_label_access)).also { box.addView(it.container) }
        refreshButton = button(getString(R.string.refresh_status), ButtonStyle.SECONDARY) { refreshSiteStatus(explicit = true) }
        box.addView(refreshButton.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
        box.addView(heading(getString(R.string.tech_title), size = 15f).apply { setPadding(0, dp(16), 0, 0) })
        technicalDetails = text("", size = 13f, muted = true)
        box.addView(technicalDetails.apply { setTextIsSelectable(true) })
    }

    private fun buildAdvanced(box: LinearLayout) {
        box.addView(heading(getString(R.string.manual_title), size = 15f).apply { setPadding(0, dp(8), 0, 0) })
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (days in ExportPlanner.RANGE_OPTIONS) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.range_days, days)
                textSize = 16f
                minHeight = dp(48)
                isChecked = days == rangeDays
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        rangeDays = days
                        saveSettings()
                    }
                }
            })
        }
        box.addView(group)
        createButton = button(getString(R.string.create_export), ButtonStyle.SECONDARY) { createExport() }
        box.addView(createButton)
        message = text("").apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(4))
        }
        box.addView(message)
        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(heading(getString(R.string.preview_title), size = 15f))
            addView(text(getString(R.string.preview_help), muted = true, size = 13f))
            previewBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(previewBody)
        }
        box.addView(previewCard)
        box.addView(heading(getString(R.string.exports_title), size = 15f).apply { setPadding(0, dp(16), 0, 0) })
        exportsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(exportsBody)

        openSiteButton = button(getString(R.string.open_site), ButtonStyle.SECONDARY) { openSite() }
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20) }
        box.addView(openSiteButton)
        box.addView(button(getString(R.string.privacy_button), ButtonStyle.SECONDARY) {
            startActivity(Intent(this, PermissionsRationaleActivity::class.java))
        })
        disconnectButton = button(getString(R.string.disconnect), ButtonStyle.DANGER) { confirmDisconnect() }
        box.addView(disconnectButton)
    }

    /**
     * A labelled expand/collapse row. Collapsed until the user opens it; the open state is
     * owned by the activity (saved across rotation) and is never changed by a render.
     */
    private inner class Section(
        private val label: String,
        nested: Boolean,
        private val isOpen: () -> Boolean,
        private val setOpen: (Boolean) -> Unit,
    ) {
        val toggle: Button = button(label, ButtonStyle.SECONDARY) {
            setOpen(!isOpen())
            render()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
            textSize = if (nested) 16f else 17f
            setTextColor(getColor(R.color.text))
            background = null
            setPadding(0, dp(8), 0, dp(8))
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
        }
        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val container: LinearLayout =
            if (nested) {
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
                    addView(View(this@MainActivity).apply { setBackgroundColor(getColor(R.color.border)) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
                }
            } else {
                card {}.apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
            }.apply {
                addView(toggle)
                addView(body)
            }

        init {
            render()
        }

        fun render() {
            val open = isOpen()
            body.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = getString(R.string.section_toggle, if (open) "▾" else "▸", label)
            toggle.contentDescription = label
            ViewCompat.setStateDescription(toggle, getString(if (open) R.string.expanded else R.string.collapsed))
        }
    }

    /** One labelled line of the status card. The whole row is read as one item by screen readers. */
    private inner class StatusRow(private val label: String) {
        private val pill = TextView(this@MainActivity).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val detail = text("", size = 14f).apply {
            setPadding(0, dp(4), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val note = text("", size = 14f).apply {
            setPadding(0, dp(4), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val content = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            addView(text(label, size = 13f, bold = true, muted = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(detail)
            addView(note)
        }
        private val action = button("", ButtonStyle.SECONDARY) {}.apply { visibility = View.GONE }
        val container = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
            addView(content)
            addView(action)
        }

        fun set(value: String, tone: StatusTone, detailText: String, noteText: Pair<String, StatusTone>? = null) {
            pill.text = getString(R.string.with_symbol, symbol(tone), value)
            pill.setTextColor(getColor(toneFg(tone)))
            (pill.background as GradientDrawable).setColor(getColor(toneBg(tone)))
            detail.text = detailText
            detail.visibility = if (detailText.isEmpty()) View.GONE else View.VISIBLE
            note.text = noteText?.first.orEmpty()
            noteText?.let { note.setTextColor(getColor(toneFg(it.second))) }
            note.visibility = if (noteText == null) View.GONE else View.VISIBLE
            content.contentDescription = listOfNotNull(
                getString(R.string.row_description, label, value),
                detailText.takeIf { it.isNotEmpty() },
                noteText?.first,
            ).joinToString(". ")
        }

        fun showAction(label: String, onClick: () -> Unit) {
            action.text = label
            action.setOnClickListener { onClick() }
            action.visibility = View.VISIBLE
        }

        fun hideAction() {
            action.visibility = View.GONE
        }
    }

    private fun card(content: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.card))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), getColor(R.color.border))
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        content()
    }

    private fun heading(value: String, size: Float = 17f) = text(value, size = size, bold = true).also {
        ViewCompat.setAccessibilityHeading(it, true)
    }

    private fun text(value: String, size: Float = 15f, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(if (muted) R.color.muted else R.color.text))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    /** Full-width buttons stacked vertically, so labels never crowd or truncate on narrow phones. */
    private fun button(label: String, style: ButtonStyle = ButtonStyle.PRIMARY, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        minHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        styleButton(this, style)
        setOnClickListener { onClick() }
    }

    private fun styleButton(button: Button, style: ButtonStyle) {
        button.setTextColor(
            getColor(
                when (style) {
                    ButtonStyle.PRIMARY -> R.color.brand_on
                    ButtonStyle.SECONDARY -> R.color.brand
                    ButtonStyle.DANGER -> R.color.error
                },
            ),
        )
        button.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (style == ButtonStyle.PRIMARY) {
                setColor(getColor(R.color.brand))
            } else {
                setColor(getColor(R.color.card))
                setStroke(dp(1), getColor(if (style == ButtonStyle.DANGER) R.color.error else R.color.border_strong))
            }
        }
    }

    private fun row(cells: List<String>, header: Boolean = false) = TableRow(this).apply {
        for ((i, cell) in cells.withIndex()) {
            addView(TextView(this@MainActivity).apply {
                text = cell
                textSize = 13f
                setTextColor(getColor(if (header) R.color.muted else R.color.text))
                if (header) typeface = Typeface.DEFAULT_BOLD
                gravity = if (i == 0) Gravity.START else Gravity.END
                setPadding(if (i == 0) 0 else dp(14), dp(4), 0, dp(4))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // endregion

    private companion object {
        const val STATE_SETTINGS = "settings_open"
        const val STATE_DETAILS = "details_open"
        const val STATE_ADVANCED = "advanced_open"
        /** Matches id="chatgpt-access" on the dashboard's ChatGPT plugin access card. */
        const val ACCESS_ANCHOR = "chatgpt-access"
        val AUTO_CHECK_MIN_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
