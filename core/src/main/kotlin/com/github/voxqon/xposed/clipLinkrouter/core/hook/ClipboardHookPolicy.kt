package com.github.voxqon.xposed.clipLinkrouter.core.hook

/** AOSP Android 15 / 16 和 HyperOS 的访问重载；未知签名保持原行为。 */
object ClipboardHookPolicy {
    private val accessSignature = listOf(
        "int", "java.lang.String", "java.lang.String", "int", "int", "int",
    )

    fun supportsSignature(types: List<String>): Boolean =
        types.size in 6..9 && types.take(6) == accessSignature &&
            types.drop(6).all { it == "boolean" }

    /** 包名所有权由原系统方法的 checkPackage 验证；这里只限制操作、用户和设备范围。 */
    fun backgroundUid(args: List<Any?>, readOperation: Int, modulePackage: String): Int? {
        if (args.size !in 6..9 || args[0] != readOperation || args[1] != modulePackage) return null
        if (args[2] != null && args[2] !is String) return null
        if (args.drop(6).any { it !is Boolean }) return null
        val uid = args[3] as? Int ?: return null
        val userId = args[4] as? Int ?: return null
        val deviceId = args[5] as? Int ?: return null
        if (uid < 0 || uid % 100_000 < 10_000 || uid / 100_000 != userId || deviceId != 0) return null
        return uid
    }
}
