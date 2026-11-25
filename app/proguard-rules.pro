# Room、Compose、DataStore 的保留规则由对应依赖提供；避免全包 keep 抵消 R8 优化。

# META-INF/xposed/java_init.list 通过类名加载模块入口。
-keep class com.github.voxqon.xposed.clipLinkrouter.xposed.ClipboardHook {
    public <init>();
    public void onSystemServerStarting(io.github.libxposed.api.XposedModuleInterface$SystemServerStartingParam);
}
-dontwarn io.github.libxposed.api.**
