package com.erivaldogelson.recnow

import android.app.Activity
import android.os.Debug
import android.widget.Toast

fun Activity.closeIfUnsafeRuntime(): Boolean {
    if (BuildConfig.DEBUG || !Debug.isDebuggerConnected()) return false
    Toast.makeText(this, getString(R.string.security_debugger_detected), Toast.LENGTH_LONG).show()
    finish()
    return true
}
