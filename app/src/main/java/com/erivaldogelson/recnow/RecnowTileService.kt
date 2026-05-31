package com.erivaldogelson.recnow

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class RecnowTileService : TileService() {
    private var isRecording = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bundle = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getBundleExtra(ScreenRecordService.EXTRA_STATE)
            } else {
                @Suppress("DEPRECATION")
                intent?.extras?.getBundle(ScreenRecordService.EXTRA_STATE)
            }
            isRecording = bundle?.toRecordingState()?.isRecording ?: false
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val filter = IntentFilter(ScreenRecordService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        updateTile()
    }

    override fun onStopListening() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isRecording) {
            ContextCompat.startForegroundService(this, ScreenRecordService.stopIntent(this))
            isRecording = false
            updateTile()
            return
        }

        val requestIntent = CaptureRequestActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                30,
                requestIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(requestIntent)
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Recnow"
            subtitle = if (isRecording) "Gravando" else "Toque para gravar"
            updateTile()
        }
    }
}
