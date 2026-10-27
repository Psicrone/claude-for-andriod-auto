package com.claudeauto.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.claudeauto.ClaudeAutoApp
import com.claudeauto.PairingLink
import com.claudeauto.R
import com.claudeauto.Settings
import com.claudeauto.bridge.Pairer
import com.claudeauto.databinding.ActivitySetupBinding
import com.claudeauto.databinding.ItemModeBinding
import com.claudeauto.databinding.PartManualPairingBinding
import com.claudeauto.databinding.PartPermissionBinding
import com.claudeauto.databinding.PartStepCardBinding
import com.claudeauto.databinding.StepCloudBinding
import com.claudeauto.databinding.StepComputerBinding
import com.claudeauto.databinding.StepDoneBinding
import com.claudeauto.databinding.StepModeBinding
import com.claudeauto.databinding.StepPhoneBinding
import com.claudeauto.databinding.StepWelcomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * First-run setup, and the "Connection" screen later: pick where Claude runs, get the bridge
 * installed, pair by QR code (or by hand), then grant the permissions the car needs.
 *
 * Also opens for claudeauto://pair links, from the phone's camera app or from Termux, and
 * pairs straight away.
 */
class SetupActivity : AppCompatActivity() {

    private enum class Step(val progress: Int) { WELCOME(10), MODE(30), COMPUTER(60), PHONE(60), CLOUD(60), DONE(100) }

    private lateinit var binding: ActivitySetupBinding
    private val app get() = application as ClaudeAutoApp
    private val pairer by lazy { Pairer(app.settings) }
    private val history = ArrayList<Step>()
    private var busy = false
    private var summary = ""
    private var refreshDone: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshDone?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        val link = intent?.dataString?.let(PairingLink::parse)
        when {
            link != null -> {
                show(if (link.isOnThisPhone) Step.PHONE else Step.COMPUTER)
                pairWith(link)
            }
            intent.getBooleanExtra(EXTRA_CHANGE, false) -> show(Step.MODE)
            else -> show(Step.WELCOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(PairingLink::parse)?.let(::pairWith)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Termux, the F-Droid page or the permission screens.
        if (history.lastOrNull() == Step.PHONE || history.lastOrNull() == Step.DONE) show(history.last(), replace = true)
    }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    private fun show(step: Step, replace: Boolean = false) {
        val forward = !replace && (history.isEmpty() || step.ordinal >= history.last().ordinal)
        if (!replace) {
            TransitionManager.beginDelayedTransition(binding.container, MaterialSharedAxis(MaterialSharedAxis.X, forward))
            history.remove(step)
            history.add(step)
        }
        binding.container.removeAllViews()
        binding.container.addView(
            when (step) {
                Step.WELCOME -> welcome()
                Step.MODE -> mode()
                Step.COMPUTER -> computer()
                Step.PHONE -> phone()
                Step.CLOUD -> cloud()
                Step.DONE -> done()
            },
        )
        binding.stepProgress.setProgressCompat(step.progress, true)
        binding.toolbar.navigationIcon =
            if (history.size > 1 || app.settings.isConfigured) ContextCompat.getDrawable(this, R.drawable.ic_arrow_back) else null
        if (!replace) setStatus(null)
    }

    private fun back() {
        if (busy) return
        if (history.size <= 1) {
            if (app.settings.isConfigured) openMain() else finish()
            return
        }
        if (history.removeAt(history.lastIndex) == Step.DONE) {
            openMain()
            return
        }
        TransitionManager.beginDelayedTransition(binding.container, MaterialSharedAxis(MaterialSharedAxis.X, false))
        show(history.last(), replace = true)
        setStatus(null)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun setStatus(text: String?, working: Boolean = false) {
        busy = working
        binding.statusBar.visibility = if (text == null) View.GONE else View.VISIBLE
        binding.statusText.text = text
        binding.statusBusy.visibility = if (working) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------
    // Steps
    // ---------------------------------------------------------------------------------------

    private fun welcome(): View {
        val step = StepWelcomeBinding.inflate(layoutInflater, binding.container, false)
        step.start.setOnClickListener { show(Step.MODE) }
        return step.root
    }

    private fun mode(): View {
        val step = StepModeBinding.inflate(layoutInflater, binding.container, false)
        fun option(option: ItemModeBinding, @DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int, next: Step) {
            option.icon.setImageResource(icon)
            option.title.setText(title)
            option.body.setText(body)
            option.root.setOnClickListener { show(next) }
        }
        option(step.optionComputer, R.drawable.ic_computer, R.string.mode_computer_long, R.string.mode_computer_body, Step.COMPUTER)
        option(step.optionPhone, R.drawable.ic_phone, R.string.mode_phone, R.string.mode_phone_body, Step.PHONE)
        option(step.optionCloud, R.drawable.ic_cloud, R.string.mode_cloud, R.string.mode_cloud_body, Step.CLOUD)
        step.optionComputer.badge.visibility = View.VISIBLE
        return step.root
    }

    private fun computer(): View {
        val step = StepComputerBinding.inflate(layoutInflater, binding.container, false)
        fun showCommand(id: Int) {
            val (where, command) = when (id) {
                R.id.osWindows -> R.string.where_windows to INSTALL_WINDOWS
                R.id.osPi -> R.string.where_pi to INSTALL_PI
                else -> R.string.where_mac_linux to INSTALL_MAC_LINUX
            }
            step.commandWhere.setText(where)
            step.command.text = command
        }
        step.osGroup.addOnButtonCheckedListener { _, id, checked -> if (checked) showCommand(id) }
        step.osGroup.check(R.id.osWindows)
        showCommand(R.id.osWindows)
        step.copyCommand.setOnClickListener { copy(step.command.text.toString()) }
        step.shareCommand.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Claude Auto bridge install command")
                .putExtra(Intent.EXTRA_TEXT, step.command.text.toString())
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
        step.scan.setOnClickListener { scan() }
        manualPairing(step.manual, Settings.MODE_BRIDGE)
        return step.root
    }

    private fun phone(): View {
        val step = StepPhoneBinding.inflate(layoutInflater, binding.container, false)
        val termuxInstalled = isInstalled(TERMUX)
        card(step.step1, 1, R.string.phone_step1_title, R.string.phone_step1_body)
        if (termuxInstalled) {
            step.step1.body.setText(R.string.termux_installed)
            step.step1.body.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
            step.step1.body.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        } else {
            step.step1.action.visibility = View.VISIBLE
            step.step1.action.setText(R.string.get_termux)
            step.step1.action.setOnClickListener { open(TERMUX_DOWNLOAD) }
        }
        card(step.step2, 2, R.string.phone_step2_title, R.string.phone_step2_body)
        step.step2.action.visibility = View.VISIBLE
        step.step2.action.setText(R.string.copy_open_termux)
        step.step2.action.isEnabled = termuxInstalled
        step.step2.action.setOnClickListener {
            copy(INSTALL_TERMUX)
            packageManager.getLaunchIntentForPackage(TERMUX)?.let(::startActivity)
        }
        card(step.step3, 3, R.string.phone_step3_title, R.string.phone_step3_body)
        step.step3.action.visibility = View.VISIBLE
        step.step3.action.setText(R.string.battery_settings)
        step.step3.action.isEnabled = termuxInstalled
        step.step3.action.setOnClickListener {
            open(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$TERMUX")))
        }
        manualPairing(step.manual, Settings.MODE_PHONE)
        step.manual.urlLayout.visibility = View.GONE
        return step.root
    }

    private fun cloud(): View {
        val step = StepCloudBinding.inflate(layoutInflater, binding.container, false)
        step.apiKey.setText(app.settings.apiKey)
        step.githubToken.setText(app.settings.githubToken)
        step.repos.setText(app.settings.repos.joinToString("\n"))
        step.getApiKey.setOnClickListener { open(API_KEYS_URL) }
        step.createToken.setOnClickListener { open(GITHUB_TOKEN_URL) }
        step.connect.setOnClickListener {
            if (busy) return@setOnClickListener
            val apiKey = step.apiKey.text.toString().trim()
            if (!apiKey.startsWith("sk-ant-")) {
                step.apiKeyLayout.error = "Anthropic API keys start with sk-ant-"
                return@setOnClickListener
            }
            step.apiKeyLayout.error = null
            app.settings.apiKey = apiKey
            app.settings.githubToken = step.githubToken.text.toString()
            app.settings.repos = step.repos.text.toString().split('\n', ',')
            app.settings.mode = Settings.MODE_CLOUD
            setStatus("Connecting to Anthropic. The first time takes a few seconds to set up your cloud agent…", working = true)
            lifecycleScope.launch {
                val loaded = app.store.refresh()
                if (loaded.isSuccess) {
                    summary = "Cloud mode is ready" +
                        if (app.settings.repos.isEmpty()) ". With no repos, Claude works in an empty sandbox." else " with ${app.settings.repos.size} repo(s)."
                    show(Step.DONE)
                } else {
                    setStatus("Couldn't set up cloud mode: ${loaded.exceptionOrNull()?.message}")
                }
            }
        }
        return step.root
    }

    private fun done(): View {
        val step = StepDoneBinding.inflate(layoutInflater, binding.container, false)
        step.summary.text = summary.ifEmpty { "Claude Auto is set up." }
        fun permission(row: PartPermissionBinding, @DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int, permission: String) {
            row.icon.setImageResource(icon)
            row.title.setText(title)
            row.body.setText(body)
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            row.action.setText(if (granted) R.string.allowed else R.string.allow)
            row.action.isEnabled = !granted
            row.action.setOnClickListener { permissions.launch(arrayOf(permission)) }
        }
        permission(step.mic, R.drawable.ic_mic, R.string.perm_mic_title, R.string.perm_mic_body, Manifest.permission.RECORD_AUDIO)
        permission(step.notifications, R.drawable.ic_notifications, R.string.perm_notify_title, R.string.perm_notify_body, Manifest.permission.POST_NOTIFICATIONS)
        step.androidAuto.icon.setImageResource(R.drawable.ic_car)
        step.androidAuto.title.setText(R.string.perm_auto_title)
        step.androidAuto.body.setText(R.string.perm_auto_body)
        step.androidAuto.action.setText(R.string.show_me)
        step.androidAuto.action.setOnClickListener { androidAutoHelp() }
        step.finish.setOnClickListener { openMain() }
        refreshDone = { if (history.lastOrNull() == Step.DONE) show(Step.DONE, replace = true) }
        return step.root
    }

    // ---------------------------------------------------------------------------------------
    // Pairing
    // ---------------------------------------------------------------------------------------

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val link = barcode.rawValue?.let(PairingLink::parse)
                if (link == null) setStatus("That isn't a Claude Auto pairing code. Run claude-auto-bridge pair on your computer.")
                else pairWith(link)
            }
            .addOnFailureListener { setStatus("Couldn't open the scanner (${it.message}). Enter the code by hand instead.") }
    }

    private fun pairWith(link: PairingLink) {
        if (busy) return
        setStatus("Pairing with ${link.url}…", working = true)
        lifecycleScope.launch {
            val result = runCatching { pairer.pair(link) }.getOrElse {
                setStatus(it.message ?: "Pairing failed")
                return@launch
            }
            paired(result)
        }
    }

    private fun manualPairing(manual: PartManualPairingBinding, mode: String) {
        manual.url.setText(app.settings.bridgeUrlFor(mode))
        manual.toggle.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.container)
            manual.fields.visibility = if (manual.fields.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val go = {
            val url = if (mode == Settings.MODE_PHONE) Settings.PHONE_BRIDGE_URL else manual.url.text.toString().trim()
            val code = manual.code.text.toString()
            if (!busy) {
                setStatus("Checking the bridge's certificate…", working = true)
                lifecycleScope.launch {
                    val result = runCatching {
                        pairer.pair(url, code, mode, expected = null) { confirmCertificate(it) }
                    }.getOrElse {
                        setStatus(it.message ?: "Pairing failed")
                        return@launch
                    }
                    paired(result)
                }
            }
        }
        manual.pair.setOnClickListener { go() }
        manual.code.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) go() } }
    }

    private suspend fun paired(result: Pairer.Paired) {
        setStatus("Paired. Loading your projects…", working = true)
        val loaded = app.store.refresh()
        val where = if (result.mode == Settings.MODE_PHONE) "the bridge on this phone" else "your computer"
        summary = "Paired with $where" + (result.pinnedCertificate?.let { ". Certificate $it is pinned, so only that bridge can talk to this phone." } ?: ".")
        if (loaded.isFailure) summary += "\n\nBut: ${loaded.exceptionOrNull()?.message}"
        show(Step.DONE)
    }

    private suspend fun confirmCertificate(short: String): Boolean = suspendCancellableCoroutine { cont ->
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_lock)
            .setTitle("Check the certificate")
            .setMessage("Your computer printed \"Certificate: …\" next to the pairing code.\n\nThis bridge's certificate is\n\n$short\n\nDo they match?")
            .setPositiveButton("They match") { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton("No") { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun card(card: PartStepCardBinding, number: Int, @StringRes title: Int, @StringRes body: Int) {
        card.number.text = number.toString()
        card.title.setText(title)
        card.body.setText(body)
    }

    private fun androidAutoHelp() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_car)
            .setTitle(R.string.auto_steps_title)
            .setMessage(R.string.auto_steps)
            .setPositiveButton(R.string.open_android_auto) { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
                    ?: Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO"))
                open(launch)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Claude Auto", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun isInstalled(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun open(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            setStatus("Nothing on this phone can open that.")
        }
    }

    companion object {
        /** Start at "Where should Claude run?" instead of the welcome screen. */
        const val EXTRA_CHANGE = "change"

        private const val RAW = "https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main"
        const val INSTALL_WINDOWS = "irm $RAW/install/install.ps1 | iex"
        const val INSTALL_MAC_LINUX = "curl -fsSL $RAW/install/install.sh | bash"
        const val INSTALL_PI = "curl -fsSL $RAW/install/raspberry-pi.sh | bash"
        /** Installs, signs in and pairs in one go; pairing opens this app with the details. */
        const val INSTALL_TERMUX = "curl -fsSL $RAW/bridge/termux/install.sh | bash && claude-auto-bridge login && claude-auto-bridge pair"

        private const val TERMUX = "com.termux"
        private const val TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/"
        private const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        private const val API_KEYS_URL = "https://platform.claude.com/settings/keys"
        private const val GITHUB_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
    }
}
