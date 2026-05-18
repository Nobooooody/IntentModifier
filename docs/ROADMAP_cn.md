# Intent Modifier - 规划

[English documentation](./roadmap.md)

## 项目概述

Intent Modifier 是一个安卓 Xposed 模块，用于拦截应用启动并实时修改 intent，实现高级应用启动和 intent 重定向功能。

## 已完成功能

### Java 代码规则引擎 ✅

- **Java 代码条件 & 操作** - ECJ 编译 → D8 → DEX → DexClassLoader
- **Imports & Members** - 支持自定义 import 语句和类成员代码
- **规则优先级** - 数字越大优先级越高
- **规则导入/导出** - JSON 格式，支持剪贴板和文件，含冲突解决
- **冲突解决** - 每条规则独立选择：替换 / 忽略 / 重命名旧→_old / 重命名新→_new

### 规则数据通信（模块 → Hook 的目标 App）

- **XSharedPreferences**（主渠道）- 快速，原生 Xposed 框架可用（LSPosed/EdXposed）
- **ContentProvider**（备用渠道）- 虚拟化 Xposed 框架可用（FPA/npatch 等）
- **本地缓存** - DEX + meta.json 缓存在目标 App 的 `/cache/intent_modifier_rules/`
- **运行时加载** - 首次 hook 触发时懒加载，每次检查版本
- **完整回退链** - XSharedPreferences → ContentProvider → 本地缓存

### Hook 方法

- **Instrumentation**（默认）- Hook `android.app.Instrumentation.execStartActivity`
- **Launcher3** - Hook `com.android.launcher3.Launcher.startActivitySafely`
- **Custom Class** - Hook 用户指定的类的 `startActivity` 方法

### 用户界面

- **规则页面** - 列表支持启用/禁用/编辑/删除，FAB 添加，导入/导出菜单
- **启动器页面** - 每个 App 独立选择 hook 类型，支持添加/删除
- **设置页面** - 语言选择，使用 MaterialCard 样式
- **选择应用页面** - 加载动画，实时搜索，Toolbar 刷新按钮

## 待完成功能

### 1. 用户界面增强

- 规则模板（常用场景预定义）
- 规则测试/预览模式
- 详细的 intent 日志查看器
- 规则分组管理

### 2. Hook 方法扩展

- 支持更多启动器 hook 方法
- 自定义 hook 注入点
- 按应用选择 hook 方式的 UI

### 3. 额外的 Intent 修改

- 支持所有 Intent extras 类型
- MIME 类型操作
- Intent flags 管理
- Category 管理

### 4. 备份与同步

- 云端备份集成
- 规则版本控制和回滚
- 设备间规则同步

### 5. 性能优化

- 优化 UI 渲染
- 改善列表滚动流畅度
- 规则列表延迟加载

---

## 技术架构

### 数据流

```
【模块端】
  规则编辑器 → ModifierRepository (SharedPreferences)
            → RuleCompilationManager (ECJ + D8)
            → Base64 DEX 存储到 SharedPreferences
            → RuleProvider (ContentProvider) 提供数据端点

【Xposed 端】
  handleLoadPackage → 注册 hook 回调
                    → （首次 hook 触发时）
  beforeHookedMethod → ensureRulesLoaded()
                         → tryGetRemoteVersion() [XSP → CP]
                         → 对比本地 meta.json
                         → 版本过期则下载 DEX
                         → loadDexFromFile() → CompiledRules
                    → applyRules() → 匹配条件，执行操作
```

### 包结构

```
io.github.nobooooody.intent_modifier
├── XposedInit.kt                    # Hook 入口 + 规则加载 + applyRules
├── IntentModifierApp.kt             # Application 类（暂无特殊逻辑）
├── data/
│   ├── JavaCodeRule.kt              # 规则数据模型
│   └── ModifierRepository.kt         # SharedPreferences 读写
├── engine/
│   └── RuleCompilationManager.kt     # ECJ → D8 → DEX 编译
├── compiler/
│   ├── JavaEngineSetting.kt         # JDK JAR 路径、classpath 配置
│   └── JavaPrintWriter.kt           # 编译日志捕获
└── ui/
    ├── MainActivity.kt              # 底部导航（规则/启动器/设置）
    ├── JavaCodeRuleEditorActivity.kt # 规则编辑器（imports/members/condition/action）
    ├── AppPickerActivity.kt         # 应用选择（搜索/刷新）
    └── provider/
        └── RuleProvider.kt          # ContentProvider（hash/count/dex/version/meta）
```

### 关键文件

| 文件 | 用途 |
|------|------|
| `XposedInit.kt` | Hook 注册、规则加载、intent 修改 |
| `RuleCompilationManager.kt` | 将 Java 代码片段编译为 DEX |
| `ModifierRepository.kt` | 持久化规则和启动器 hook 配置 |
| `JavaCodeRuleEditorActivity.kt` | 编辑规则（imports/members/condition/action/priority） |
| `RuleProvider.kt` | ContentProvider 数据端点 |
| `MainActivity.kt` | 导航、导入/导出对话框、冲突解决 |

### ContentProvider 端点

| 路径 | 返回值 | 使用场景 |
|------|--------|----------|
| `/meta` | version, hash, count | 一次查询获取全部元数据 |
| `/dex` | Base64 DEX | 下载编译好的规则 |
| `/version` | Long | 快速版本检查 |
| `/hash` | String | Hash 校验 |
| `/count` | Int | DexClassLoader 规则数量 |

### 已知兼容性

| Xposed 框架 | XSharedPreferences | ContentProvider |
|---|---|---|
| LSPosed / EdXposed | ✅ 可用 | ✅ 可用（备用） |
| FPA | ❌ 失败 | ✅ 可用 |
| npatch | ❌ 失败 | ✅ 可用 |

### 下一步重构任务

- [ ] 将 `RuleLoader` 从 `XposedInit` 中提取出来，保持关注点分离
- [ ] 将 `LauncherHooksLoader` 移入 `data/` 包
- [ ] 为 `RuleCompilationManager` 添加单元测试
- [ ] 为冲突解决逻辑添加单元测试
- [ ] 统一常量定义到 companion object
- [ ] 清理所有文件的未使用 import
- [ ] 为 release 构建添加 ProGuard/R8 规则