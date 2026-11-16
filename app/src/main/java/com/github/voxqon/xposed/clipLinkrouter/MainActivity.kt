package com.github.voxqon.xposed.clipLinkrouter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.github.voxqon.xposed.clipLinkrouter.ui.navigation.AppNavHost
import com.github.voxqon.xposed.clipLinkrouter.ui.theme.ClipLinkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipLinkTheme {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    getPrefs().edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
                }

                // 首次进入时主动申请一次通知权限；拒绝过一次后不再自动弹，
                // 之后由设置页的手动入口处理。
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED && !getPrefs().getBoolean(KEY_NOTIF_ASKED, false)) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                AppNavHost(app = application as ClipLinkApp)
            }
        }
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "cliplink_runtime"
        private const val KEY_NOTIF_ASKED = "notif_permission_asked"
    }
}
