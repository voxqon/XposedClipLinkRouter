package com.github.voxqon.xposed.clipLinkrouter.ui.rules

import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import com.github.voxqon.xposed.clipLinkrouter.data.local.dao.RuleDao
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.RuleEntity
import com.github.voxqon.xposed.clipLinkrouter.data.repository.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class MemoryRuleDao : RuleDao {
        val rows = MutableStateFlow<List<RuleEntity>>(emptyList())
        var failInsert = false
        override fun observeAll() = rows
        override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun getByBuiltinKey(key: String) = rows.value.firstOrNull { it.builtinKey == key }
        override suspend fun insert(entity: RuleEntity): Long {
            if (failInsert) error("写入失败")
            val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
            rows.value += entity.copy(id = id)
            return id
        }
        override suspend fun update(entity: RuleEntity) {
            rows.value = rows.value.map { if (it.id == entity.id) entity else it }
        }
        override suspend fun setEnabled(id: Long, enabled: Boolean, nowMs: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = nowMs) else it }
        }
    }

    @Test fun `未填写默认匹配表达式时不能保存`() = runTest(dispatcher) {
        val dao = MemoryRuleDao()
        val vm = RuleEditViewModel(RuleRepository(dao), null)
        assertNotNull(vm.ui.value.patternError)
        vm.save { fail("空规则不能保存") }
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun `空关键词规则不能保存而空口令正则合法`() = runTest(dispatcher) {
        val vm = RuleEditViewModel(RuleRepository(MemoryRuleDao()), null)
        vm.update { it.copy(matchType = MatchType.KEYWORD) }
        assertNotNull(vm.ui.value.patternError)
        vm.update { it.copy(matchType = MatchType.KOULING) }
        assertNull(vm.ui.value.patternError)
    }

    @Test fun `加载规则期间不能提交为新规则`() = runTest(dispatcher) {
        val dao = MemoryRuleDao()
        dao.rows.value = listOf(RuleEntity(id = 1, name = "已有规则", type = RuleType.CUSTOM,
            matchType = MatchType.SUFFIX_HOST, pattern = "example.com", platform = Platform.GENERIC))
        val vm = RuleEditViewModel(RuleRepository(dao), 1)
        vm.save { fail("读取完成前不能保存") }
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        assertFalse(vm.ui.value.saved)
    }

    @Test fun `规则已被删除时不会误建新规则`() = runTest(dispatcher) {
        val dao = MemoryRuleDao()
        val vm = RuleEditViewModel(RuleRepository(dao), 99)
        runCurrent()
        vm.update { it.copy(pattern = "example.com") }
        vm.save { fail("不存在的规则不能保存") }
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
        assertNotNull(vm.ui.value.error)
    }

    @Test fun `连续提交只插入一次并且回调一次`() = runTest(dispatcher) {
        val dao = MemoryRuleDao()
        val vm = RuleEditViewModel(RuleRepository(dao), null)
        vm.update { it.copy(pattern = "example.com") }
        var callbacks = 0
        repeat(2) { vm.save { callbacks++ } }
        runCurrent()
        vm.save { callbacks++ }
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        assertEquals(1, callbacks)
    }

    @Test fun `保存失败后恢复可重试状态`() = runTest(dispatcher) {
        val dao = MemoryRuleDao().apply { failInsert = true }
        val vm = RuleEditViewModel(RuleRepository(dao), null)
        vm.update { it.copy(pattern = "example.com") }
        vm.save { fail("失败不能返回成功") }
        runCurrent()
        assertFalse(vm.ui.value.saving)
        assertNotNull(vm.ui.value.error)
        dao.failInsert = false
        vm.save { }
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        assertTrue(vm.ui.value.saved)
    }

    @Test fun `测试链接与保存规则使用相同的去空白和链接提取`() = runTest(dispatcher) {
        val vm = RuleEditViewModel(RuleRepository(MemoryRuleDao()), null)
        vm.update { it.copy(matchType = MatchType.REGEX, pattern = " ^https://example\\.com/path$ ",
            testUrl = "复制 example.com/path 查看") }
        assertTrue(vm.ui.value.testResult.orEmpty().startsWith("命中当前规则"))
    }

    @Test fun `新建规则保存的包名和模板去掉首尾空白`() = runTest(dispatcher) {
        val dao = MemoryRuleDao()
        val vm = RuleEditViewModel(RuleRepository(dao), null)
        vm.update { it.copy(pattern = "example.com", targetPackage = " com.example.app ",
            schemeTemplate = " app://open?url={url} ") }
        vm.save { }
        runCurrent()
        assertEquals("com.example.app", dao.rows.value.single().targetPackage)
        assertEquals("app://open?url={url}", dao.rows.value.single().schemeTemplate)
    }
}
