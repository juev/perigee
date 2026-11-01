package org.evsyukov.perigee.library

import android.Manifest
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The system's notice for a screen that hides its bars, marked as seen for the length of a test,
 * and the setting put back as it was after it, whether the test passed or not.
 *
 * The notice is SystemUI's `ImmersiveModeConfirmation`: shown when a screen hides the bars and the
 * secure setting `immersive_mode_confirmations` is not "confirmed", in a window of its own that takes
 * the input focus, so that a key pressed meanwhile goes to it and not to the screen. On a fresh test
 * emulator nobody has confirmed it. SystemUI watches the setting and takes the notice down when it
 * turns "confirmed". The setting is the device's, not the app's: the README says where this is read.
 *
 * What is put back is the value as the settings give it, none or a string. They keep the word "null"
 * as no setting at all (`SettingsState.internValue`), so a "null" is put back as none.
 */
class ImmersiveNoticeSeen : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val before = read()
            var failure: Throwable? = null
            try {
                write(CONFIRMED)
                base.evaluate()
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                try {
                    write(before)
                } catch (e: Throwable) {
                    // The test's own failure comes first; one that passed fails on the setting left behind.
                    if (failure != null) failure.addSuppressed(e) else throw e
                }
            }
        }
    }

    companion object {
        const val KEY = "immersive_mode_confirmations"
        const val CONFIRMED = "confirmed"

        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val resolver get() = instrumentation.targetContext.contentResolver

        /** The setting as the device has it; null when there is none. Any app may read this one. */
        fun read(): String? = Settings.Secure.getString(resolver, KEY)

        /**
         * Sets the setting, or removes it for null, under the shell's right to write secure settings,
         * held for the write only; then reads it back, as a write that returned is not one that took.
         */
        fun write(value: String?) {
            val automation = instrumentation.uiAutomation
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                if (value == null) {
                    resolver.delete(Settings.Secure.getUriFor(KEY), null, null)
                } else {
                    Settings.Secure.putString(resolver, KEY, value)
                }
            } finally {
                automation.dropShellPermissionIdentity()
            }
            val now = read()
            check(now == value) { "$KEY is ${now?.let { "\"$it\"" }}, not ${value?.let { "\"$it\"" }}" }
        }

        /**
         * What holds the input as the system tells it, for a wait that ended in vain: the setting, the
         * focus of each display (`dumpsys window displays`: `windows` lists the windows, not the focus),
         * the notice's window if there is one, and the focus `dumpsys input` names, cut short.
         */
        fun focus(): String {
            val displays = dumpsys("window displays").filter { it.trim().startsWith("mCurrentFocus=") || it.trim().startsWith("mFocusedApp=") }
            val notice = dumpsys("window windows").filter { NOTICE in it }.take(NOTICE_LINES)
            val input = dumpsys("input").let { lines ->
                lines.filterIndexed { i, line ->
                    FOCUS.containsMatchIn(line) || i > 0 && lines[i - 1].trim().let { it == "FocusedApplications:" || it == "FocusedWindows:" }
                }
            }
            val lines = (displays + notice + input).map { it.trim().take(LINE) }.take(LINES)
            return "$KEY ${read()?.let { "\"$it\"" }}; ${lines.joinToString(" | ").ifEmpty { "dumpsys names no focus" }}"
        }

        private fun dumpsys(what: String): List<String> =
            instrumentation.uiAutomation.executeShellCommand("dumpsys $what").let { output ->
                ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readLines() }
            }

        private const val NOTICE = "ImmersiveModeConfirmation"
        private val FOCUS = Regex("$NOTICE|FocusedWindows|FocusedApplications")
        private const val NOTICE_LINES = 4
        private const val LINE = 200
        private const val LINES = 24
    }
}
