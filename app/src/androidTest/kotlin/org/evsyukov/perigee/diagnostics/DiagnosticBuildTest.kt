package org.evsyukov.perigee.diagnostics

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.EngineTrace
import org.evsyukov.perigee.legal.AboutScreen
import org.evsyukov.perigee.reader.ReaderScreen
import org.evsyukov.perigee.reader.ReaderViewModel
import org.evsyukov.perigee.reader.TestBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostic build as installed: its own identity beside the app, the trace from the reader's request
 * down to the native drawing and the screen, and saving the report. Run on the APK built with
 * `-PperigeeDiagnostics=true`; on any other build only [anOrdinaryBuildHasNoTraceAndKeepsItsSignIn] runs.
 *
 * Not checked here: the system's file picker itself (the test answers for it, after checking the request
 * it was given), and anything of a real crash, which an emulator does not reproduce.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticBuildTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private val diagnostic get() = activity.packageName.endsWith(".diagnostic")

    private fun string(id: Int) = activity.getString(id)

    /** Activities of this package that take the address the sign-in provider sends the reader back to. */
    private fun signInReceivers() = activity.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_VIEW, Uri.parse("bookorbit://oauth2-callback?code=x")).setPackage(activity.packageName), 0,
    )

    @Test
    fun anOrdinaryBuildHasNoTraceAndKeepsItsSignIn() {
        assumeFalse(diagnostic)
        assertFalse(Diagnostics.enabled)
        assertFalse(File(activity.noBackupFilesDir, "diagnostics").exists())
        assertEquals(1, signInReceivers().size)
    }

    @Test
    fun theDiagnosticBuildIsAnAppOfItsOwn() {
        assumeTrue(diagnostic)
        val pm = activity.packageManager
        assertEquals("org.evsyukov.perigee.diagnostic", activity.packageName)
        assertEquals("Perigee Diagnostics", pm.getApplicationLabel(activity.applicationInfo).toString())
        assertTrue("debuggable", activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(pm.getPackageInfo(activity.packageName, 0).versionName.orEmpty().endsWith("-diagnostic"))
        assertEquals("the app's sign-in address is left to the app", 0, signInReceivers().size)
        assertTrue("started with the process", Diagnostics.enabled)
        assertNull(Diagnostics.failure)
    }

    @Test
    fun theReportIsSavedWhereThePickerSaysAndAFailureCanBeRetried() {
        assumeTrue(diagnostic)
        val saved = File(activity.cacheDir, "report-${System.nanoTime()}.txt")
        // A provider that is not there: the system refuses to open the place.
        val answers = ArrayDeque(listOf(Uri.parse("content://${activity.packageName}.missing/report.txt"), Uri.fromFile(saved)))
        val asked = mutableListOf<Intent>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                asked += contract.createIntent(activity, input)
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(answers.removeFirst()))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { AboutScreen(onLicenses = {}, onClose = {}) }
            }
        }
        val save = rule.onNodeWithText(string(R.string.action_save_diagnostics))
        save.performScrollTo().performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(hasText(R.string.diagnostics_save_failed)).fetchSemanticsNodes().isNotEmpty() }

        save.performScrollTo().performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(hasText(R.string.diagnostics_saved)).fetchSemanticsNodes().isNotEmpty() }
        val report = saved.readText()
        assertTrue(report.startsWith("Perigee diagnostics report"))
        assertTrue("this run's header: ${report.take(600)}", "package=org.evsyukov.perigee.diagnostic" in report)
        assertTrue("process exits, latest first:" in report)

        // What the picker was asked for, twice: a new plain text file, offered under the report's name.
        assertEquals(2, asked.size)
        for (intent in asked) {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty().startsWith("perigee-diagnostics-"))
        }
    }

    private fun hasText(id: Int) = androidx.compose.ui.test.hasText(string(id))

    @Test
    fun smoothingOffThenOnIsTracedFromTheRequestToTheScreen() {
        assumeTrue(diagnostic)
        runBlocking { ReaderPreferences.get(activity).update { ReaderPrefs() } }
        val book = TestBook.write(File(activity.cacheDir, "diagnostic-test.fb2"), title = "Diagnostics ${System.nanoTime()}")
        rule.setContent { MaterialTheme { ReaderScreen(book, onClose = {}) } }
        val model = ViewModelProvider(activity)[ReaderViewModel::class.java]
        fun page() = model.state.value.page?.takeIf { !model.state.value.busy }?.bitmap

        rule.waitUntil(TIMEOUT) { page() != null }
        rule.runOnUiThread { model.updatePrefs { it.copy(antialiasing = false) } }
        rule.waitUntil(TIMEOUT) { page()?.let(::colours)?.let { it <= 2 } == true }

        val mark = "n=${System.nanoTime()}"
        Diagnostics.event("test.mark") { mark }
        rule.runOnUiThread { model.updatePrefs { it.copy(antialiasing = true) } }
        rule.waitUntil(TIMEOUT) { page()?.let(::colours)?.let { it > 2 } == true }
        val shown = page()!!
        // The pixels drawn, not the setting: smoothing on gives the letters grey edges.
        assertTrue("smoothing on: ${colours(shown)} colours", colours(shown) > 2)

        val id = EngineTrace.idOf(shown)
        val chain = listOf(
            "prefs.request aa=false>true",
            "reader.relayout aa=true",
            "book.apply branch=laid_out aa=true",
            "doc.apply ",
            "native.apply.begin aa=1",
            "native.apply.props aa_mode=2",
            "native.apply.end",
            "bitmap.alloc bitmap=$id ",
            "doc.render ",
            "native.render.locked",
            "native.render.laid_out",
            "native.render.drawn",
            "native.render.unlocked",
            "doc.rendered doc=",
            "page.shown bitmap=$id ",
            "compose.bind bitmap=$id ",
        )
        var missing: String? = null
        try {
            rule.waitUntil(TIMEOUT) {
                val events = Diagnostics.report(activity).lines().dropWhile { "test.mark $mark" !in it }
                var at = 0
                missing = chain.firstOrNull { step ->
                    val found = (at until events.size).firstOrNull { step in events[it] }
                    if (found != null) at = found + 1
                    found == null
                }
                missing == null
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the trace has every step in order; first missing: $missing")
        }
    }

    /** The distinct colours of a page. */
    private fun colours(bitmap: Bitmap): Int =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            .toSet().size

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
