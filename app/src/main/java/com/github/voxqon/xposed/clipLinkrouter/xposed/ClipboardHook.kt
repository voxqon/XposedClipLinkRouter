package com.github.voxqon.xposed.clipLinkrouter.xposed

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import com.github.voxqon.xposed.clipLinkrouter.BuildConfig
import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardHookPolicy
import com.github.voxqon.xposed.clipLinkrouter.data.settings.ClipboardToastPreference
import com.github.voxqon.xposed.clipLinkrouter.service.ClipboardEventReceiver
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** 后台访问例外仅限本包；系统变化事件唤起接收器，不需要应用常驻。 */
class ClipboardHook : XposedModule() {
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val installed = mutableListOf<HookHandle>()
        try {
            val loader = param.classLoader
            val service = loader.loadClass("com.android.server.clipboard.ClipboardService")
            val accessMethods = service.declaredMethods.filter {
                it.name == "clipboardAccessAllowed" && it.returnType == Boolean::class.javaPrimitiveType &&
                    ClipboardHookPolicy.supportsSignature(it.parameterTypes.map { type -> type.name })
            }
            check(accessMethods.isNotEmpty()) { "Unsupported clipboardAccessAllowed signature" }
            val focusMethod = service.getDeclaredMethod(
                "isDefaultDeviceAndUidFocused", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            check(focusMethod.returnType == Boolean::class.javaPrimitiveType)
            val clipboardClass = loader.loadClass(service.name + "\$Clipboard")
            val updateMethod = service.getDeclaredMethod(
                "setPrimaryClipInternalNoClassifyLocked", clipboardClass, ClipData::class.java,
                Int::class.javaPrimitiveType, String::class.java,
            )
            val readOperation = AppOpsManager::class.java.getField("OP_READ_CLIPBOARD").getInt(null)
            val appOpsField = service.getDeclaredField("mAppOps").apply { isAccessible = true }
            val userIdField = clipboardClass.getDeclaredField("userId").apply { isAccessible = true }
            val deviceIdField = clipboardClass.getDeclaredField("deviceId").apply { isAccessible = true }
            val workerHandlerField = service.getDeclaredField("mWorkerHandler").apply { isAccessible = true }
            val getContext = service.getMethod("getContext")
            val readingUid = ThreadLocal<Int>()
            val focusBypassed = ThreadLocal<Boolean>()
            // 焦点判断很短，可能已被 ART 内联进访问方法；先反优化调用方再安装 Hook。
            accessMethods.forEach { deoptimize(it) }
            // setter 可能被内联进上层重载，反优化这些调用方以确保事件 Hook 生效。
            service.declaredMethods.filter { it.name.startsWith("setPrimaryClipInternal") }
                .forEach { deoptimize(it) }

            installed += hook(focusMethod).intercept { chain ->
                val result = chain.proceed()
                val uid = readingUid.get()
                if (uid != null && result == false && chain.getArg(0) == 0 && chain.getArg(1) == uid) {
                    focusBypassed.set(true)
                    true
                } else {
                    result
                }
            }
            for (method in accessMethods) {
                installed += hook(method).intercept { chain ->
                    // 重载会互相调用，按调用层级保存并恢复线程上下文。
                    val previousUid = readingUid.get()
                    val previousFocus = focusBypassed.get()
                    val uid = ClipboardHookPolicy.backgroundUid(chain.args, readOperation, BuildConfig.APPLICATION_ID)
                    if (uid == null) readingUid.remove() else readingUid.set(uid)
                    focusBypassed.remove()
                    try {
                        val result = chain.proceed()
                        if (uid != null && focusBypassed.get() == true && result == false) {
                            val appOps = appOpsField.get(chain.thisObject) as AppOpsManager
                            // HyperOS 默认把本包的 AppOps 设为 foreground。仅把本次已通过
                            // 其他校验的读取视为前台，显式 ignore / errored 仍保留拒绝。
                            appOps.unsafeCheckOpRawNoThrow("android:read_clipboard", uid,
                                BuildConfig.APPLICATION_ID) == AppOpsManager.MODE_FOREGROUND
                        } else {
                            result
                        }
                    } finally {
                        if (previousUid == null) readingUid.remove() else readingUid.set(previousUid)
                        if (previousFocus == null) focusBypassed.remove() else focusBypassed.set(previousFocus)
                    }
                }
            }
            installed += hook(updateMethod).intercept { chain ->
                val result = chain.proceed()
                val clipData = chain.getArg(1) as? ClipData
                if (clipData != null) {
                    try {
                        val clipboard = chain.getArg(0)
                        val userId = userIdField.getInt(clipboard)
                        val deviceId = deviceIdField.getInt(clipboard)
                        val uid = chain.getArg(2) as Int
                        // 不把跨资料夹复制或虚拟设备内容交给另一个用户的模块。
                        if (deviceId == 0 && userId >= 0 && uid >= 0 && uid / 100_000 == userId) {
                            val context = getContext.invoke(chain.thisObject) as Context
                            val handler = workerHandlerField.get(chain.thisObject) as Handler
                            val sourcePackage = chain.getArg(3) as? String
                            val timestamp = clipData.description.timestamp
                            handler.post { dispatch(context, userId, sourcePackage, timestamp) }
                        }
                    } catch (_: Throwable) {
                        log(Log.WARN, TAG, "clipboard event dispatch unavailable")
                    }
                }
                result
            }
            installed += hook(service.getDeclaredMethod("onStart")).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context = getContext.invoke(chain.thisObject) as Context
                    val handler = workerHandlerField.get(chain.thisObject) as Handler
                    registerUnlockReceiver(context, handler)
                } catch (_: Throwable) {
                    log(Log.WARN, TAG, "unlock event registration unavailable")
                }
                result
            }
            log(Log.INFO, TAG, "clipboard background access and event hooks installed")
        } catch (_: Throwable) {
            installed.forEach { it.unhook() }
            log(Log.ERROR, TAG, "clipboard hook unavailable; system behavior preserved")
        }
        installClipboardToastHook(param.classLoader)
    }

    private fun installClipboardToastHook(loader: ClassLoader) {
        val installed = mutableListOf<HookHandle>()
        try {
            val service = loader.loadClass("com.android.server.clipboard.ClipboardService")
            val clipboardClass = loader.loadClass(service.name + "\$Clipboard")
            val intType = Int::class.javaPrimitiveType!!
            val signature = listOf(String::class.java, intType, intType, clipboardClass)
            val toastMethods = service.declaredMethods.filter {
                it.name == "showAccessNotificationLocked" && it.returnType == Void.TYPE &&
                    (it.parameterTypes.toList() == signature || it.parameterTypes.toList() == signature + intType)
            }
            check(toastMethods.isNotEmpty())
            val getContext = service.getMethod("getContext")
            val createContextAsUser = Context::class.java.getMethod(
                "createContextAsUser", UserHandle::class.java, intType,
            )
            val clipboardImpl = loader.loadClass(service.name + "\$ClipboardImpl")
            val readers = clipboardImpl.declaredMethods.filter {
                it.name == "getPrimaryClip" && it.returnType == ClipData::class.java
            }
            check(readers.isNotEmpty())
            readers.forEach { deoptimize(it) }

            for (method in toastMethods) {
                installed += hook(method).intercept { chain ->
                    val uid = Binder.getCallingUid()
                    val hidden = chain.getArg(0) == BuildConfig.APPLICATION_ID && chain.getArg(1) == uid &&
                        chain.getArg(2) == uid / 100_000 && runCatching {
                            val identity = Binder.clearCallingIdentity()
                            try {
                                val context = getContext.invoke(chain.thisObject) as Context
                                val userContext = createContextAsUser.invoke(
                                    context, UserHandle.getUserHandleForUid(uid), 0,
                                ) as Context
                                ClipboardToastPreference.isHidden(userContext.packageManager)
                            } finally {
                                Binder.restoreCallingIdentity(identity)
                            }
                        }.getOrDefault(false)
                    if (hidden) null else chain.proceed()
                }
            }
            log(Log.INFO, TAG, "clipboard access toast hook installed")
        } catch (_: Throwable) {
            installed.forEach { it.unhook() }
            log(Log.WARN, TAG, "clipboard access toast hook unavailable; system toast preserved")
        }
    }

    private fun registerUnlockReceiver(context: Context, handler: Handler) {
        val allUsers = UserHandle::class.java.getField("ALL").get(null)
        val getSendingUserId = BroadcastReceiver::class.java.getMethod("getSendingUserId")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val userId = getSendingUserId.invoke(this) as Int
                if (userId >= 0) dispatch(context, userId)
            }
        }
        Context::class.java.getMethod(
            "registerReceiverAsUser", BroadcastReceiver::class.java, UserHandle::class.java,
            IntentFilter::class.java, String::class.java, Handler::class.java, Int::class.javaPrimitiveType,
        ).invoke(context, receiver, allUsers, IntentFilter(Intent.ACTION_USER_PRESENT), null,
            handler, Context.RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("MissingPermission") // 仅系统服务进程调用，并以系统身份发送广播。
    private fun dispatch(context: Context, userId: Int, sourcePackage: String? = null, timestamp: Long = 0L) {
        val identity = Binder.clearCallingIdentity()
        try {
            val intent = Intent(ClipboardEventReceiver.ACTION_CHANGED)
                .setClassName(BuildConfig.APPLICATION_ID, ClipboardEventReceiver.CLASS_NAME)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(ClipboardEventReceiver.EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(ClipboardEventReceiver.EXTRA_CLIP_TIMESTAMP, timestamp)
            // 不广播剪贴板原文，也不唤起被用户强行停止的应用。
            context.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(userId * 100_000))
        } catch (_: Throwable) {
            log(Log.WARN, TAG, "clipboard event delivery unavailable")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private companion object {
        const val TAG = "XposedClipLinkRouter"
    }
}
