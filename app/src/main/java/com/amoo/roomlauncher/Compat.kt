package com.amoo.roomlauncher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/** Registriert einen Receiver für System-Broadcasts auf allen Android-Versionen. */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun Context.registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}
