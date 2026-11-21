package com.mosstts.edge

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Keep CI previews outside application data, which Gradle removes after device tests. */
internal fun saveUiScreenshot(name: String, bitmap: Bitmap) {
    require(name.matches(Regex("[a-z-]+")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(instrumentation.targetContext.getExternalFilesDir(null), "edge-$name.png")
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    val command = "mkdir -p /data/local/tmp/edge-ui-preview && cp '${file.absolutePath}' /data/local/tmp/edge-ui-preview/"
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
        check(it.readBytes().isEmpty()) { "Could not preserve UI screenshot" }
    }
}
