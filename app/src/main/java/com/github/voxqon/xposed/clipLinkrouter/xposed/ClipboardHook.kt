package com.github.voxqon.xposed.clipLinkrouter.xposed

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.UserHandle
import com.github.voxqon.xposed.clipLinkrouter.BuildConfig
import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardHookPolicy
import com.github.voxqon.xposed.clipLinkrouter.service.ClipboardEventReceiver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Member

/** 后台访问例外仅限本包；系统变化事件唤起接收器，不需要应用常驻。 */
class ClipboardHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") return
        val installed = mutableListOf<XC_MethodHook.Unhook>()
        try {
            val service = XposedHelpers.findClass(
                "com.android.server.clipboard.ClipboardService", lpparam.classLoader,
            )
            val accessMethods = service.declaredMethods.filter {
                it.name == "clipboardAccessAllowed" && it.returnType == Boolean::class.javaPrimitiveType &&
                    ClipboardHookPolicy.supportsSignature(it.parameterTypes.map { type -> type.name })
            }
            check(accessMethods.isNotEmpty()) { "Unsupported clipboardAccessAllowed signature" }
            val focusMethod = service.getDeclaredMethod(
                "isDefaultDeviceAndUidFocused", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            check(focusMethod.returnType == Boolean::class.javaPrimitiveType)
            val clipboardClass = XposedHelpers.findClass(
                "com.android.server.clipboard.ClipboardService\$Clipboard", lpparam.classLoader,
            )
            val updateMethod = service.getDeclaredMethod(
                "setPrimaryClipInternalNoClassifyLocked", clipboardClass, ClipData::class.java,
                Int::class.javaPrimitiveType, String::class.java,
            )
            val readOperation = XposedHelpers.getStaticIntField(AppOpsManager::class.java, "OP_READ_CLIPBOARD")
            val readingUid = ThreadLocal<Int>()
            val focusBypassed = ThreadLocal<Boolean>()
            // 焦点判断很短，可能已被 ART 内联进访问方法；先反优化调用方再安装 Hook。
            // 此扩展由 LSPosed 提供，传统 Xposed API 82 中没有声明。
            val deoptimize = XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", Member::class.java)
            accessMethods.forEach { deoptimize.invoke(null, it) }
            // setter 可能被内联进上层重载，反优化这些调用方以确保事件 Hook 生效。
            service.declaredMethods.filter { it.name.startsWith("setPrimaryClipInternal") }
                .forEach { deoptimize.invoke(null, it) }

            installed += requireNotNull(XposedBridge.hookMethod(focusMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val uid = readingUid.get() ?: return
                    if (!param.hasThrowable() && param.result == false &&
                        param.args[0] == 0 && param.args[1] == uid) {
                        param.result = true
                        focusBypassed.set(true)
                    }
                }
            }))
            for (method in accessMethods) {
                installed += requireNotNull(XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 重载会互相调用，按调用层级保存并恢复线程上下文。
                        param.setObjectExtra(PREVIOUS_UID, readingUid.get())
                        param.setObjectExtra(PREVIOUS_FOCUS, focusBypassed.get())
                        focusBypassed.remove()
                        val uid = ClipboardHookPolicy.backgroundUid(
                            param.args, readOperation, BuildConfig.APPLICATION_ID,
                        )
                        if (uid == null) readingUid.remove() else readingUid.set(uid)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val uid = readingUid.get()
                            if (uid != null && focusBypassed.get() == true &&
                                !param.hasThrowable() && param.result == false) {
                                val appOps = XposedHelpers.getObjectField(param.thisObject, "mAppOps") as AppOpsManager
                                // HyperOS 默认把本包的 AppOps 设为 foreground。仅把本次已通过
                                // 其他校验的读取视为前台，显式 ignore / errored 仍保留拒绝。
                                if (appOps.unsafeCheckOpRawNoThrow("android:read_clipboard", uid,
                                        BuildConfig.APPLICATION_ID) == AppOpsManager.MODE_FOREGROUND) {
                                    param.result = true
                                }
                            }
                        } finally {
                            val previous = param.getObjectExtra(PREVIOUS_UID) as? Int
                            val previousFocus = param.getObjectExtra(PREVIOUS_FOCUS) as? Boolean
                            if (previous == null) readingUid.remove() else readingUid.set(previous)
                            if (previousFocus == null) focusBypassed.remove() else focusBypassed.set(previousFocus)
                        }
                    }
                }))
            }
            installed += requireNotNull(XposedBridge.hookMethod(updateMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || param.args[1] !is ClipData) return
                    try {
                        val clipboard = param.args[0]
                        val userId = XposedHelpers.getIntField(clipboard, "userId")
                        val deviceId = XposedHelpers.getIntField(clipboard, "deviceId")
                        val uid = param.args[2] as Int
                        // 不把跨资料夹复制或虚拟设备内容交给另一个用户的模块。
                        if (deviceId != 0 || userId < 0 || uid < 0 || uid / 100_000 != userId) return
                        val context = XposedHelpers.callMethod(param.thisObject, "getContext") as Context
                        val handler = XposedHelpers.getObjectField(param.thisObject, "mWorkerHandler") as Handler
                        val sourcePackage = param.args[3] as? String
                        val timestamp = (param.args[1] as ClipData).description.timestamp
                        handler.post { dispatch(context, userId, sourcePackage, timestamp) }
                    } catch (_: Throwable) {
                        XposedBridge.log("XposedClipLinkRouter: clipboard event dispatch unavailable")
                    }
                }
            }))
            installed += requireNotNull(XposedBridge.hookMethod(service.getDeclaredMethod("onStart"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    try {
                        val context = XposedHelpers.callMethod(param.thisObject, "getContext") as Context
                        val handler = XposedHelpers.getObjectField(param.thisObject, "mWorkerHandler") as Handler
                        val allUsers = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL")
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                val userId = XposedHelpers.callMethod(this, "getSendingUserId") as Int
                                if (userId >= 0) dispatch(context, userId)
                            }
                        }
                        XposedHelpers.callMethod(context, "registerReceiverAsUser", receiver, allUsers,
                            IntentFilter(Intent.ACTION_USER_PRESENT), null, handler, Context.RECEIVER_NOT_EXPORTED)
                    } catch (_: Throwable) {
                        XposedBridge.log("XposedClipLinkRouter: unlock event registration unavailable")
                    }
                }
            }))
            XposedBridge.log("XposedClipLinkRouter: clipboard background access and event hooks installed for " +
                "${BuildConfig.APPLICATION_ID}; overloads=${accessMethods.map { it.parameterCount }.sorted()}; " +
                "event metadata=source+timestamp; no foreground service")
        } catch (error: Throwable) {
            installed.forEach { it.unhook() }
            XposedBridge.log("XposedClipLinkRouter: clipboard hook unavailable; system behavior preserved")
            XposedBridge.log(error)
        }
    }

    private fun dispatch(context: Context, userId: Int, sourcePackage: String? = null, timestamp: Long = 0L) {
        val identity = Binder.clearCallingIdentity()
        try {
            val intent = Intent(ClipboardEventReceiver.ACTION_CHANGED)
                .setClassName(BuildConfig.APPLICATION_ID, ClipboardEventReceiver.CLASS_NAME)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(ClipboardEventReceiver.EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(ClipboardEventReceiver.EXTRA_CLIP_TIMESTAMP, timestamp)
            // 不广播剪贴板原文，也不唤起被用户强行停止的应用。
            XposedHelpers.callMethod(context, "sendBroadcastAsUser", intent,
                UserHandle.getUserHandleForUid(userId * 100_000))
        } catch (_: Throwable) {
            XposedBridge.log("XposedClipLinkRouter: clipboard event delivery unavailable")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private companion object {
        const val PREVIOUS_UID = "cliplink.previousReadingUid"
        const val PREVIOUS_FOCUS = "cliplink.previousFocusBypassed"
    }
}
