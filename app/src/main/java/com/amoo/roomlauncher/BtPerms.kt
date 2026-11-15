package com.amoo.roomlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object BtPerms {
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    /** Bis Android 5.1 werden Rechte bei der Installation erteilt. */
    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
