package com.erivaldogelson.recnow

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.getSystemService

class RecordingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService<NotificationManager>()
        when (intent.action) {
            ACTION_KEEP -> notificationManager?.cancel(ScreenRecordService.FINISHED_NOTIFICATION_ID)
            ACTION_DELETE -> {
                intent.getStringExtra(EXTRA_URI)?.let { uri ->
                    runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
                }
                intent.getStringExtra(EXTRA_FILE_PATH)?.let { path ->
                    runCatching { java.io.File(path).delete() }
                }
                notificationManager?.cancel(ScreenRecordService.FINISHED_NOTIFICATION_ID)
            }
        }
    }

    companion object {
        const val ACTION_KEEP = "com.erivaldogelson.recnow.KEEP_RECORDING"
        const val ACTION_DELETE = "com.erivaldogelson.recnow.DELETE_RECORDING"
        const val EXTRA_URI = "uri"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
