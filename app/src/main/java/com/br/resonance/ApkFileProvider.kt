package com.br.resonance

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * ApkFileProvider: ContentProvider serving the local installed APK
 * to Android system share sheets (Quick Share, WhatsApp, Bluetooth, etc.)
 * with zero external dependencies.
 */
class ApkFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.br.resonance.fileprovider"

        fun getUriForFile(file: File): Uri {
            return Uri.parse("content://$AUTHORITY/${file.name}")
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val fileName = uri.lastPathSegment ?: "Resonance.apk"
        val file = File(context?.cacheDir, "shared_apk/$fileName")
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        if (file.exists()) {
            cursor.addRow(arrayOf<Any>(file.name, file.length()))
        } else {
            cursor.addRow(arrayOf<Any>(fileName, 0L))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val fileName = uri.lastPathSegment ?: "Resonance.apk"
        val file = File(context?.cacheDir, "shared_apk/$fileName")
        return if (file.exists()) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
