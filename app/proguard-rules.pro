# Room、Compose、DataStore 的保留规则由对应依赖提供；避免全包 keep 抵消 R8 优化。

# assets/xposed_init 通过类名加载模块入口。
-keep class com.github.voxqon.xposed.clipLinkrouter.xposed.ClipboardHook {
    public <init>();
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-dontwarn de.robv.android.xposed.**
