package com.github.voxqon.xposed.clipLinkrouter.data.settings

import android.content.ComponentName
import android.content.pm.PackageManager
import com.github.voxqon.xposed.clipLinkrouter.BuildConfig

/** PackageManager 保存每个用户的组件状态，应用和 system_server 共用这一份开关。 */
internal object ClipboardToastPreference {
    private val marker = ComponentName.createRelative(BuildConfig.APPLICATION_ID, ".HideClipboardReadToast")

    fun isHidden(packageManager: PackageManager): Boolean =
        packageManager.getComponentEnabledSetting(marker) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setHidden(packageManager: PackageManager, hidden: Boolean) {
        packageManager.setComponentEnabledSetting(
            marker,
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS,
        )
    }
}
