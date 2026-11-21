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
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.bufferedReader().readText()
        }
    // UiAutomation executes one command directly; it does not interpret shell operators or quotes.
    require(file.absolutePath.none { it.isWhitespace() })
    val destination = "/data/local/tmp/edge-ui-preview/edge-$name.png"
    shell("mkdir -p /data/local/tmp/edge-ui-preview")
    shell("cp ${file.absolutePath} $destination")
    check(shell("ls $destination").trim() == destination) {
        "Could not preserve UI screenshot: $destination"
    }
}
