package com.amoo.roomlauncher

import android.util.Log

/** Log-Ausgaben mit festem Tag – mitlesen per: adb logcat -s RoomLauncher */
object L {
    private const val TAG = "RoomLauncher"
    fun i(msg: String) {
        Log.i(TAG, msg)
    }
    fun w(msg: String, e: Throwable? = null) {
        Log.w(TAG, msg, e)
    }
}
