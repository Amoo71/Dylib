package com.amoo.roomlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

private val mainHandler = Handler(Looper.getMainLooper())

fun Context.toast(message: String) {
    val ctx = applicationContext
    mainHandler.post { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
}
