# Intent Modifier - 使用指南

[English documentation](./USAGE.md)

## 快速开始

### 安装

1. 安装 APK
2. 在 LSPosed/Xposed 中启用模块
3. 配置模块作用域，包含你的启动器
4. 打开应用配置规则

### 添加规则

1. 进入**规则**页面
2. 点击 **+** 按钮
3. 编写 Java 代码规则：
   - **Imports（导入语句）**：自定义 `import` 语句（如 `import android.net.Uri;`）
   - **Members（成员代码）**：类的字段和辅助方法
   - **Condition（条件代码）**：返回 `boolean` 的 Java 代码
   - **Action（动作代码）**：修改 `result` Intent 的 Java 代码
4. 设置优先级（数字越大越先检查）
5. 点击**测试编译**检查错误
6. 点击**保存**编译并激活规则

### 规则字段

| 字段 | 说明 |
|------|------|
| Imports | 自定义 `import` 语句，引入外部类 |
| Members | 类的字段和辅助方法 |
| Condition | 返回 `boolean` 的 Java 代码 — 判断规则是否匹配 |
| Action | 修改 `result` Intent 的 Java 代码 |
| Priority | 数字越大越先检查（首个匹配生效） |

### 内置变量

在 **Condition** 和 **Action** 中可以使用：

- `ctx` — `android.content.Context` — 可用于日志输出、Toast、调用系统服务
- `intent` — `android.content.Intent` — **原始** Intent（被启动的 App）
- `result` — `android.content.Intent` — **修改后** 的 Intent（实际被启动的）

### 规则示例

**重定向抖音到特定 Tab：**
```java
// Imports
import android.net.Uri;

// Members
public static String getPkg(Intent i) {
    return i.getPackage() != null ? i.getPackage() : i.getComponent() != null ? i.getComponent().getPackageName() : null;
}

// Condition
return "com.ss.android.ugc.aweme".equals(getPkg(intent));

// Action
result.setData(Uri.parse("snssdk1128://land_tab?tabid=homepage_notification"));
```

**重定向百度系应用：**
```java
// Condition
return intent.getPackage() != null && intent.getPackage().contains("baidu");

// Action
result.setPackage("com.example.custom");
```

**打印日志并修改：**
```java
// Imports
import android.util.Log;

// Condition
Log.d("MyRule", "Launching: " + intent.getPackage());
return intent.getPackage() != null && intent.getPackage().contains("baidu");

// Action
Log.d("MyRule", "Redirecting to custom package");
result.setPackage("com.example.custom");
```

### 配置启动器 Hook

1. 进入**启动器**页面
2. 点击 **+**
3. 选择一个启动器应用
4. 选择 hook 方式：
   - **Instrumentation（默认）**：Hook `android.app.Instrumentation.execStartActivity` — 适用于大多数应用
   - **Launcher3**：Hook `com.android.launcher3.Launcher.startActivitySafely` — 适用于 Lawnchair/Pixel Launcher 系列
   - **Custom（自定义）**：Hook 用户指定的类

### 导出 / 导入

**导出：**
- 点击菜单（⋮）→ 导出到文件 或 复制到剪贴板

**导入：**
- 点击菜单（⋮）→ 从文件导入 或 从剪贴板导入
- 冲突按每条规则独立解决，四种选项：
  - **Replace（替换）**：用新规则覆盖旧规则
  - **Ignore（忽略）**：保留旧规则，丢弃新规则
  - **Rename Old→_old（重命名旧）**：保留两条，旧的加 `_old` 后缀
  - **Rename New→_new（重命名新）**：保留两条，新的加 `_new` 后缀

### 切换语言

1. 进入**设置**页面
2. 点击语言卡片
3. 选择**跟随系统**、**English** 或 **Chinese**

## 构建

```bash
./gradlew assembleDebug
```