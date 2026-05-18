# Intent Modifier - 使用指南

[English documentation](./usage.md)

## 快速开始

### 安装

1. 安装 APK
2. 在 LSPosed/Xposed 中启用模块
3. 配置模块作用域，包含你的启动器
4. 打开应用配置规则

### 添加规则

1. 在"规则"页面点击 **+** 按钮
2. 选择要配置的应用
3. 设置需要的修改：
   - **自定义 Action**：覆盖 intent action（如 `android.intent.action.VIEW`）
   - **自定义 Data**：设置自定义 data URI
   - **自定义 Package**：重定向到其他应用
   - **自定义 Class**：覆盖 activity 类
   - **Flags**：设置 intent flags（如 `270532608`）
   - **Categories**：添加 intent categories
   - **MIME Type**：设置 MIME 类型
   - **Extras**：添加键值对

### 理解 Intent 字段

| 字段 | 说明 | 示例 |
|-------|------|------|
| Action | Intent action | `android.intent.action.MAIN` |
| Data | Intent URI | `content://com.example.app` |
| Package | 目标应用包名 | `com.android.settings` |
| Class | 目标 activity | `.MainActivity` |
| Flags | Activity 启动标志 | `270532608` |
| Category | Intent 类别 | `android.intent.category.LAUNCHER` |
| Type | MIME 类型 | `image/png` |

### Extra 类型

支持的 extra 值类型：

- **Boolean** - `true` 或 `false`
- **Integer** - 整数（如 `123`）
- **Long** - 大整数（如 `9223372036854775807`）
- **Float** - 小数（如 `3.14`）
- **String** - 文本
- **URI** - Content 或文件 URI

### 数组类型

- **BooleanArray** - 多个布尔值
- **IntegerArray** - 多个整数
- **LongArray** - 多个长整数
- **FloatArray** - 多个小数
- **StringArray** - 多个字符串
- **ByteArray**, **CharArray**, **ShortArray**, **DoubleArray**

### 配置启动器 Hook

1. 进入"启动器"页面
2. 添加启动器应用
3. 选择 hook 方式：
   - **Instrumentation（默认）**：Hook `android.app.Instrumentation.execStartActivity`
   - **Launcher3**：Hook `com.android.launcher3.Launcher.startActivitySafely`

### 导出/导入

**导出：**
- 点击菜单 → 导出到文件 或 复制到剪贴板

**导入：**
- 点击菜单 → 从文件导入 或 从剪贴板导入
- 选择冲突处理方式：
  - **替换全部**：删除旧规则
  - **保留新规则**：冲突时覆盖
  - **保留旧规则**：冲突时跳过

### 切换语言

1. 进入"设置"页面
2. 选择语言：
   - **跟随系统**
   - **English**
   - **Chinese**