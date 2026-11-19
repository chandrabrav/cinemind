package com.cinemind.app

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshots for the user guide (docs/USER_GUIDE.md): opens the real app on a fresh install and
 * walks through its main screens on a phone-sized display. The pictures are documentation, so a
 * screen that can't be reached is logged and skipped instead of failing the build.
 */
@RunWith(AndroidJUnit4::class)
class GuideScreenshotTest {

    companion object {
        private const val TAG = "GuideScreenshots"

        private fun shell(cmd: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
        }

        /** A phone-sized, phone-density display (the CI emulator is small and low-density). */
        @BeforeClass @JvmStatic
        fun phoneDisplay() {
            // The CI emulator screen is 640 px wide: about 400 dp, like a typical phone.
            shell("wm density 256")
            Thread.sleep(2_000)
        }

        @AfterClass @JvmStatic
        fun restoreDisplay() {
            shell("wm density reset")
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val missed = mutableListOf<String>()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000): Boolean = runCatching {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }.isSuccess

    private fun click(matcher: SemanticsMatcher): Boolean = runCatching {
        if (!waitFor(matcher)) return false
        val node = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        Thread.sleep(900)
        true
    }.getOrDefault(false)

    private fun back() {
        click(hasContentDescription("Back"))
    }

    private fun shot(name: String) {
        var bitmap: Bitmap? = null
        repeat(4) {
            if (bitmap == null) runCatching {
                compose.waitForIdle()
                Thread.sleep(700)
                bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            }.onFailure { Thread.sleep(1_500) }
        }
        val b = bitmap ?: run { missed += name; Log.w(TAG, "Could not capture $name"); return }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
        File(dir, "guide_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun step(name: String, block: () -> Boolean) {
        if (runCatching(block).getOrDefault(false)) shot(name) else { missed += name; Log.w(TAG, "Skipped $name") }
    }

    @Test
    fun walkThroughTheApp() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(pkg, it) }
        }

        // Home on a fresh install (after the brand moment).
        // The brand moment covers Home for a moment on a cold start: let it finish first.
        waitFor(hasText("Your first story starts here"), 30_000)
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        step("01_home") { waitFor(hasText("New project")) }

        // Import screen (New project).
        step("02_import") { click(hasText("New project")) && waitFor(hasText("Import media")) }
        back()

        step("03_templates") { click(hasText("Start from a template")) && waitFor(hasText("Templates")) }
        back()

        step("04_teleprompter") { click(hasText("Record with the teleprompter")) && waitFor(hasText("Script")) }
        back()

        // AI models hub: on this phone, cloud, diagnostics.
        step("05_ai_models") { click(hasContentDescription("On-device AI")) && waitFor(hasText("On this phone")) }
        step("06_ai_cloud") { click(hasText("Cloud")) }
        step("07_ai_diagnostics") { click(hasText("Diagnostics")) && waitFor(hasText("Agents")) }
        back()

        step("08_settings") { click(hasContentDescription("Settings")) && waitFor(hasText("Make CineMind yours")) }
        step("09_about") { click(hasText("About CineMind AI")) }
        back()
        back()

        Log.i(TAG, if (missed.isEmpty()) "All guide screenshots taken" else "Missed: $missed")
    }
}
