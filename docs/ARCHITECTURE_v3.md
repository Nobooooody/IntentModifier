# Intent Modifier v3 架构

## 版本历史

| 版本 | 说明 |
|------|------|
| v1 (main) | 普通规则：基于表单的简单规则（action/data/package/class/flags/extras），按包名匹配，JSON 存储，直接反射修改 Intent |
| v2 (java_engine) | Java 代码规则：ECJ 编译 → D8 → 单 DEX，类名 `Rule_0`..`Rule_{N-1}`，XSharedPreferences + ContentProvider 双通道 |
| v3 (当前) | 合并 v1 普通规则 + v2 Java 代码规则，支持 per-app 编译分发，稳定 UUID 类名，内嵌 RuleRegistry |

## 数据模型

### JavaCodeRule（代码规则）

```kotlin
data class JavaCodeRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String = "",
    val targetPackages: List<String> = emptyList(),  // 空 = 全局
    val imports: String = "",
    val members: String = "",
    val condition: String = "",
    val action: String = "",
    val priority: Int = 0
)
```

### NormalRule（普通规则）

```kotlin
data class NormalRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String = "",
    val targetPackages: List<String> = emptyList(),
    val priority: Int = 0,
    val customAction: String? = null,
    val customData: String? = null,
    val customPackage: String? = null,
    val customClass: String? = null,
    val customFlags: Int? = null,
    val customCategories: List<String> = emptyList(),
    val customType: String? = null,
    val extras: List<ExtraItem> = emptyList()
)

data class ExtraItem(
    val key: String,
    val type: String,  // String, Int, Long, Boolean, Float, Double,及其 Array 版本, Null
    val values: List<String> = emptyList()
)
```

## 存储设计

### SharedPreferences（`intent_modifier_config`）

| key | 类型 | 说明 |
|-----|------|------|
| `compiled_version` | Long | 全局版本号（时间戳） |
| `shared_dex` | String | Base64 shared DEX（无 target 限制的规则） |
| `app_dex_<sanitized_pkg>` | String | Base64 app 专属 DEX |
| `java_code_rules` | String | JavaCodeRule 源数据 JSON 数组 |
| `normal_rules` | String | NormalRule 源数据 JSON 数组 |
| `launcher_hooks` | String | 各 app 的 hook 配置 JSON |

**移除的 key（v2 遗留）**：`compiled_dex`、`rule_count`、`rules_hash`

> `sanitized_pkg` = 包名中的 `.` 替换为 `_`，例如 `com.android.chrome` → `com_android_chrome`

### ContentProvider 端点

| URI | 返回 | 说明 |
|-----|------|------|
| `GET /version` | Long | 全局版本号，变更检测唯一依据 |
| `GET /dex` | String | Base64 shared DEX，或空 |
| `GET /dex/{pkg}` | String? | Base64 app 专属 DEX；不存在则返回空（Xposed 端 fallback 到 shared DEX） |

**移除的端点（v2 遗留）**：`/meta`、`/hash`、`/count`

SharedPreferences 与 ContentProvider 返回内容对齐，无各自独有的字段。

## 编译流水线

### 类名规则

每条规则分配一个稳定类名：`Rule_<UUID的前8位>`，例如 `Rule_a1b2c3d4`。

### 编译步骤

```
[输入] List<JavaCodeRule> + List<NormalRule>

1. 对所有启用规则，统一起始索引
2. 每条规则 → buildRuleTemplate() → ECJ 编译 → .class 文件
   - JavaCodeRule → 模板含 user imports/members/condition/action
   - NormalRule → 模板含自动生成的 condition（AND 精确匹配）+ action
3. 按 targetPackages 分组：
   - shared 组：targetPackages 为空的规则
   - per-app 组：按每个 packageName 分组（一条规则可同时属于多个 app 组）
4. 对 shared 组：
   - 收集所有 .class
   - 生成 RuleRegistry_shared.java 并编译（内嵌 [[className, priority], ...]）
   - D8 → shared.dex
5. 对每个 per-app 组：
   - 收集所有 .class
   - 生成 RuleRegistry_app_<sanitized_pkg>.java 并编译
   - D8 → app_<sanitized_pkg>.dex
6. 写入 SharedPreferences：
   - compiled_version = System.currentTimeMillis()
   - shared_dex = Base64(shared.dex)
   - app_dex_<pkg> = Base64(app_<pkg>.dex)  （仅当有 per-app 规则时）
```

### 编译缓存（后续优化）

- 每条规则缓存其 content hash + 生成的 .class 字节
- 仅重新编译 hash 变化的规则，未变规则跳过 ECJ 阶段
- 分组和 D8 阶段依然每次执行（.class 合并为 DEX 成本低）

### RuleRegistry 内嵌格式

shared DEX 中：
```java
package engine;
public class RuleRegistry_shared {
    public static Object[][] getRules() {
        return new Object[][]{
            {"engine.Rule_a1b2c3d4", 100},  // className, priority
            {"engine.Rule_e5f6g7h8", 50},
        };
    }
}
```

app DEX 中：
```java
package engine;
public class RuleRegistry_app_com_android_chrome {
    public static Object[][] getRules() {
        return new Object[][]{
            {"engine.Rule_i9j0k1l2", 200},
        };
    }
}
```

### 普通规则模板

```java
package engine;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public class Rule_{UUID8} {
    // 编译时根据 NormalRule 字段生成的常量
    private static final String TARGET_ACTION = "{customAction}";
    private static final String TARGET_DATA = "{customData}";
    private static final String TARGET_PKG = "{customPackage}";
    private static final String TARGET_CLASS = "{customClass}";
    private static final int TARGET_FLAGS = {customFlags};
    private static final String TARGET_TYPE = "{customType}";
    // targetCategories, extras 同理...

    public static boolean evaluate(Context ctx, Intent intent, Intent result) {
        // 根据填写的字段自动生成 AND 精确匹配条件
        // 字段为空则跳过该条件
        if (intent.getComponent() == null) return false;
        String targetPkg = intent.getComponent().getPackageName();
        if (targetPkg == null) return false;

        boolean matches = true;
        if (!TARGET_PKG.isEmpty())
            matches = matches && targetPkg.equals(TARGET_PKG);
        if (!TARGET_ACTION.isEmpty())
            matches = matches && TARGET_ACTION.equals(intent.getAction());
        if (!TARGET_CLASS.isEmpty())
            matches = matches && TARGET_CLASS.equals(intent.getComponent().getClassName());
        // ... 其他字段同理
        return matches;
    }

    public static void execute(Context ctx, Intent intent, Intent result) {
        if (!TARGET_ACTION.isEmpty()) result.setAction(TARGET_ACTION);
        if (!TARGET_DATA.isEmpty()) result.setData(Uri.parse(TARGET_DATA));
        if (!TARGET_PKG.isEmpty() && !TARGET_CLASS.isEmpty())
            result.setClassName(TARGET_PKG, TARGET_CLASS);
        if (TARGET_FLAGS != 0) result.addFlags(TARGET_FLAGS);
        // ... categories, type, extras
    }
}
```

## Xposed 运行时加载流程

```
loadRulesIfNeeded(lpparam, ctx):
  targetPkg = lpparam.packageName
  cacheDir = /data/data/<targetPkg>/cache/intent_modifier_rules/

  // 1. 获取远端版本
  remoteVersion → XSharedPreferences → ContentProvider fallback

  // 2. 检查本地缓存
  localVersion = read meta.json from cacheDir

  // 3. 版本匹配且 DEX 存在 → 直接加载
  if remoteVersion == localVersion && DEX files exist:
    return loadFromCache(cacheDir)

  // 4. 下载 DEX
  sharedDexBytes = tryGetDex()                    // /dex 端点
  appDexBytes = tryGetAppDex(targetPkg)            // /dex/{pkg} 端点

  // 5. 写入缓存
  write cacheDir/rules_shared.dex
  write cacheDir/rules_app.dex  (if appDexBytes != null)
  write cacheDir/meta.json { version: remoteVersion }

  // 6. DexClassLoader 加载（支持 : 分隔多 DEX 路径）
  dexPath = buildString {
      append(cacheDir/rules_shared.dex)
      if (appDexBytes != null) append(":").append(cacheDir/rules_app.dex)
  }
  optimizedDir = /data/data/<targetPkg>/code_cache/optimized/
  classLoader = DexClassLoader(dexPath, optimizedDir, null, parentClassLoader)

  // 7. 合并 RuleRegistry，按 priority 排序
  sharedRegistry = classLoader.loadClass("engine.RuleRegistry_shared")
  allRules = sharedRegistry.getMethod("getRules").invoke(null) as Array<Array<Any>>

  if appDexBytes != null:
    appRegistryName = "engine.RuleRegistry_app_${sanitize(targetPkg)}"
    appRegistry = classLoader.loadClass(appRegistryName)
    appRules = appRegistry.getMethod("getRules").invoke(null) as Array<Array<Any>>
    allRules = allRules + appRules

  allRules.sortByDescending { it[1] as Int }  // 按 priority 降序

  // 8. 加载每条规则的方法
  loadedRules = allRules.map { (className, _) ->
    clazz = classLoader.loadClass(className as String)
    LoadedRule(
      evaluateMethod = clazz.getMethod("evaluate", Context, Intent, Intent),
      executeMethod = clazz.getMethod("execute", Context, Intent, Intent)
    )
  }
  compiledRules = CompiledRules(loadedRules)
```

> 排序在每次 `loadRulesIfNeeded()` 触发重载时执行（包括进程首次加载、热更新版本变化后重新下载 DEX）。

## 规则执行

```kotlin
fun applyRules(intent: Intent): Intent {
    val rules = compiledRules ?: return intent
    val result = Intent(intent)
    for (rule in rules.list) {
        val matched = rule.evaluateMethod.invoke(null, ctx, intent, result) as? Boolean ?: false
        if (matched) {
            rule.executeMethod.invoke(null, ctx, intent, result)
            return result  // 一条规则命中后即返回（break 逻辑不变）
        }
    }
    return intent
}
```

## 数据流总览

```
【模块端 (io.github.nobooooody.intent_modifier)】

规则列表（混合 JavaCodeRule + NormalRule）
  ↕ ModifierRepository (SharedPreferences)
  ↕ RuleCompilationManager
       ECJ → .class → 分组 → D8 → shared.dex + per-app.dex
  ↕ SharedPreferences (compiled_version + shared_dex + app_dex_*)
  ↕ RuleProvider (ContentProvider: /version, /dex, /dex/{pkg})
```

```
【Xposed 端 (目标 App 进程)】

handleLoadPackage → 注册 hook
beforeHookedMethod
  → loadRulesIfNeeded()
       → XSharedPreferences | ContentProvider → 获取 version
       → 对比本地 meta.json
       → 版本变化则下载 DEX
       → DexClassLoader (shared + app DEX，: 分隔)
       → 合并 RuleRegistry → 按 priority 排序 → 加载方法
  → applyRules(intent)
       → 遍历 rules → evaluate → execute → break
       → 返回修改后的 Intent
```

## UI 设计

### 规则编辑器（JavaCodeRule）
- 目标应用：复用 AppPickerActivity 多选模式，返回包名列表
- 编辑器中以 **Chip/子卡片** 展示已选应用（显示应用名 + 包名，带 ✕ 删除按钮）
- 未选择任何目标 = 全局规则

### 规则编辑器（NormalRule）
- 新建 Compose Activity，各字段对应表单
- Extras 支持动态增删，全部类型：String, Int, Long, Boolean, Float, Double, 对应 Array, Null

### 规则列表
- 混合展示 JavaCodeRule + NormalRule，用图标/标签区分类型
- 排序按 priority 降序，与执行时顺序一致

## 迁移策略

旧 v2 用户升级后，首次在编辑器中"保存"时：
1. `java_code_rules` 中无 `id` 的旧规则自动分配 UUID
2. `compiled_dex` / `rule_count` / `rules_hash` 等旧 key 不会再被读取
3. 新编译流程生成 `shared_dex` + `app_dex_*` 覆盖旧数据
4. 无需额外迁移逻辑

## 实施阶段

| 阶段 | 内容 | 涉及文件 |
|------|------|---------|
| 1 | 数据模型 + Repository | JavaCodeRule.kt, NormalRule.kt(新建), ModifierRepository.kt |
| 2 | 编译流水线重构 | RuleCompilationManager.kt（核心重写） |
| 3 | ContentProvider 精简 | RuleProvider.kt |
| 4 | Xposed 运行时改造 | XposedInit.kt（RuleLoader 统一） |
| 5 | UI 调整 + Target Packages | JavaCodeRuleEditorActivity.kt, AppPickerActivity.kt, MainActivity.kt |
| 6 | 普通规则编辑器 + 模板 | NormalRuleEditorActivity.kt(新建), RuleCompilationManager.kt |

## 包结构（v3）

```
io.github.nobooooody.intent_modifier
├── XposedInit.kt              # Hook 入口 + applyRules
├── IntentModifierApp.kt
├── data/
│   ├── JavaCodeRule.kt        # JavaCodeRule + NormalRule + ExtraItem 数据模型
│   └── ModifierRepository.kt  # SharedPreferences CRUD
├── engine/
│   └── RuleCompilationManager.kt  # 编译 + RuleLoader 统一
├── compiler/
│   ├── JavaEngineSetting.kt
│   └── JavaPrintWriter.kt
└── ui/
    ├── MainActivity.kt              # 规则列表（混合展示）
    ├── JavaCodeRuleEditorActivity.kt# 代码规则编辑器（含 target packages）
    ├── NormalRuleEditorActivity.kt  # 普通规则编辑器（新建）
    ├── AppPickerActivity.kt         # 应用选择（多选模式）
    ├── ConflictResolutionActivity.kt
    └── provider/
        └── RuleProvider.kt     # ContentProvider（精简为 3 个端点）
```
