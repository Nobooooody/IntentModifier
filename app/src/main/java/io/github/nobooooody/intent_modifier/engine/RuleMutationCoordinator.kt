package io.github.nobooooody.intent_modifier.engine

import android.content.Context
import android.util.Log
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.data.NormalRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuleSnapshot(
    val javaRules: List<JavaCodeRule>,
    val normalRules: List<NormalRule>
)

sealed interface MutationResult {
    data object Success : MutationResult
    data class Failure(
        val message: String,
        val ruleName: String? = null,
        val ruleId: String? = null,
        val sourceFile: String? = null,
        val line: Int? = null
    ) : MutationResult
}

/**
 * 所有规则变更（保存、启停、删除、导入、冲突解决）的唯一入口。
 *
 * - 编译/发布在应用级 CoroutineScope 中执行，不随 Activity/Composable 生命周期取消；
 * - Mutex 串行化“读规则 -> 编译 -> 写 JSON -> 发布 DEX”；
 * - 编译失败时不写 JSON，避免线上 DEX 与规则列表长期不一致。
 */
class RuleMutationCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val repo = ModifierRepository(appContext)
    private val compiler = RuleCompilationManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "RuleMutationCoordinator"

        @Volatile
        private var instance: RuleMutationCoordinator? = null

        fun get(context: Context): RuleMutationCoordinator {
            return instance ?: synchronized(this) {
                instance ?: RuleMutationCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }

    fun saveJavaRule(rule: JavaCodeRule): Deferred<MutationResult> {
        return submit {
            val current = readSnapshot()
            val candidate = current.copy(javaRules = current.javaRules.upsertById(rule))
            commit(candidate) {
                repo.upsertJavaCodeRule(rule)
            }
        }
    }

    fun saveNormalRule(rule: NormalRule): Deferred<MutationResult> {
        return submit {
            val current = readSnapshot()
            val candidate = current.copy(normalRules = current.normalRules.upsertById(rule))
            commit(candidate) {
                repo.upsertNormalRule(rule)
            }
        }
    }

    /**
     * 通用变更入口：transform 在 Mutex 内基于最新规则快照计算候选集合。
     * 保存、启停、删除、导入、冲突解决都应使用它，确保“先编译、成功后落盘”。
     */
    fun mutate(transform: (RuleSnapshot) -> RuleSnapshot): Deferred<MutationResult> {
        return submit {
            val candidate = transform(readSnapshot())
            commit(candidate) {
                repo.saveJavaCodeRules(candidate.javaRules)
                repo.saveNormalRules(candidate.normalRules)
            }
        }
    }

    private fun submit(block: suspend () -> MutationResult): Deferred<MutationResult> {
        val deferred = CompletableDeferred<MutationResult>()
        scope.launch {
            try {
                deferred.complete(mutex.withLock { block() })
            } catch (t: Throwable) {
                Log.e(TAG, "Rule mutation failed", t)
                deferred.complete(MutationResult.Failure(t.message ?: t.javaClass.simpleName))
            }
        }
        return deferred
    }

    private fun readSnapshot(): RuleSnapshot {
        return RuleSnapshot(repo.getJavaCodeRules(), repo.getNormalRules())
    }

    private fun commit(candidate: RuleSnapshot, persist: () -> Unit): MutationResult {
        val javaRules = candidate.javaRules
            .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
            .sortedByDescending { it.priority }
        val normalRules = candidate.normalRules.filter { it.enabled }

        // 空集合也会编译：生成只含空 registry 的 DEX，用于清空目标进程的旧规则。
        val outcome = compiler.compile(javaRules, normalRules)
        val artifact = when (outcome) {
            is CompileOutcome.Failure -> {
                return MutationResult.Failure(
                    message = outcome.message,
                    ruleName = outcome.ruleName,
                    ruleId = outcome.ruleId,
                    sourceFile = outcome.sourceFile,
                    line = outcome.line
                )
            }
            is CompileOutcome.Success -> outcome.artifact
        }

        return try {
            persist()
            val published = compiler.publish(artifact)
            if (published.success) {
                MutationResult.Success
            } else {
                MutationResult.Failure(published.errorMessage ?: "Publish failed")
            }
        } catch (t: Throwable) {
            compiler.discard(artifact)
            Log.e(TAG, "Persist/publish failed", t)
            MutationResult.Failure(t.message ?: t.javaClass.simpleName)
        }
    }
}

private fun List<JavaCodeRule>.upsertById(rule: JavaCodeRule): List<JavaCodeRule> {
    val index = indexOfFirst { it.id == rule.id }
    return if (index >= 0) toMutableList().also { it[index] = rule } else this + rule
}

private fun List<NormalRule>.upsertById(rule: NormalRule): List<NormalRule> {
    val index = indexOfFirst { it.id == rule.id }
    return if (index >= 0) toMutableList().also { it[index] = rule } else this + rule
}
