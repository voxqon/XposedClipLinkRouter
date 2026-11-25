package com.github.voxqon.xposed.clipLinkrouter

import android.app.Application
import androidx.room.Room
import com.github.voxqon.xposed.clipLinkrouter.data.local.AppDatabase
import com.github.voxqon.xposed.clipLinkrouter.data.repository.RuleRepository
import com.github.voxqon.xposed.clipLinkrouter.data.settings.SettingsRepository
import com.github.voxqon.xposed.clipLinkrouter.notification.NotificationHelper
import com.github.voxqon.xposed.clipLinkrouter.service.ClipboardMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class ClipLinkApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var rules: RuleRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var rulesReady: Deferred<Unit>
        private set
    lateinit var monitor: ClipboardMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)

        val db = Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.REMOVE_OPEN_HISTORY)
            .fallbackToDestructiveMigration(true)
            .build()
        rules = RuleRepository(db.ruleDao())
        settings = SettingsRepository(this)

        // 首次启动写入内置规则种子；之后只补缺失，不覆盖用户修改。
        rulesReady = appScope.async {
            rules.seedBuiltinIfNeeded(System.currentTimeMillis())
        }
        monitor = ClipboardMonitor(this)
    }
}
