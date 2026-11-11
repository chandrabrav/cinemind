package com.cinemind.core.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves the current composition as a PNG into AGP's additional test output directory, which the
 * connected-test task copies back to build/outputs/connected_android_test_additional_output/
 * (CI uploads it). Falls back to the app's external files dir when run outside Gradle.
 */
fun ComposeContentTestRule.saveScreenshot(name: String): File {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val dir = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
        ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
    val file = File(dir, "$name.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
