package com.amoo.roomlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.amoo.roomlauncher.theme.BgImage
import com.amoo.roomlauncher.ui.LauncherRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val app get() = application as RoomApp
    private lateinit var root: LauncherRoot
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var askedEnableBt = false
    private var imageCallback: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKeepScreenOn(app.prefs.keepScreenOn)
        root = LauncherRoot(this, app)
        setContentView(root)
        hideSystemBars()

        if (BtPerms.granted(this)) {
            startBluetooth()
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(BtPerms.required(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && BtPerms.granted(this)) startBluetooth()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home wurde gedrückt, während der Launcher vorne ist
        root.onHome()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Zurück verlässt den Launcher nie
        root.onBack()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        app.apps.refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        root.dispose()
        scope.cancel()
        super.onDestroy()
    }

    fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @SuppressLint("MissingPermission")
    fun startBluetooth() {
        app.startBluetooth()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!adapter.isEnabled && !askedEnableBt) {
            askedEnableBt = true
            runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
    }

    /** Bild/GIF auswählen und als eigenen Hintergrund speichern. */
    fun pickImage(callback: (Boolean) -> Unit) {
        imageCallback = callback
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(Intent.createChooser(intent, "Bild oder GIF wählen"), REQ_IMAGE) }
            .onFailure { callback(false) }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE) return
        val cb = imageCallback ?: return
        imageCallback = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = BgImage.file(this@MainActivity)
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching false
                    target.length() > 0
                }.getOrDefault(false)
            }
            cb(ok)
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_IMAGE = 2
    }
}
