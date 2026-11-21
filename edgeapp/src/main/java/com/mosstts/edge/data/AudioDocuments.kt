package com.mosstts.edge.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object AudioDocuments {
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    fun save(context: Context, file: File, uri: Uri) {
        file.inputStream().use { input ->
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> input.copyTo(output) }
                ?: throw IOException("Unable to open output document")
        }
    }
}
