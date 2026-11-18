package com.amoo.roomlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

private val mainHandler = Handler(Looper.getMainLooper())

fun Context.toast(message: String) {
    val ctx = applicationContext
    val text = Lang.auto(message)
    mainHandler.post { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
}
