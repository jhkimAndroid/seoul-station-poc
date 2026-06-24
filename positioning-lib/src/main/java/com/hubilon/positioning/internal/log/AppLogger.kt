package com.hubilon.positioning.internal.log

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SSP_APP"

internal class AppLogger(context: Context, filePrefix: String = "SSP") {

    private var stream: OutputStream? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            stream = openStream(context, "${filePrefix}_${stamp}_LOG")
            Log.i(TAG, "앱 로그 생성: ${filePrefix}_${stamp}_LOG")
        } catch (e: Exception) {
            Log.e(TAG, "앱 로그 파일 생성 실패: ${e.message}", e)
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)

    fun close() {
        try { stream?.close(); stream = null }
        catch (e: Exception) { Log.e(TAG, "앱 로그 닫기 실패: ${e.message}") }
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        try {
            val line = "[${timeFmt.format(Date())}] $level/$tag: $msg\n"
            stream?.write(line.toByteArray(Charsets.UTF_8))
            stream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "앱 로그 쓰기 실패: ${e.message}")
        }
    }

    private fun openStream(context: Context, fileName: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SSP")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SSP")
            dir.mkdirs()
            File(dir, fileName).outputStream()
        }
    }
}
