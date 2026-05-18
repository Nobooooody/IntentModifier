# Intent Modifier

[View English documentation](./README.md)

## 功能介绍

Intent Modifier 是一个安卓 Xposed 模块，通过用户编写的 Java 代码规则拦截应用启动并实时修改 Intent。编写 Java 代码来匹配 Intent 并随心所欲地修改它们——重定向到不同 Activity、修改 Data URI、添加 Extras 等。

## 功能特性

- **Java 代码规则** - 用真正的 Java 代码编写条件匹配和 Intent 修改逻辑
- **丰富的内置变量** - `ctx`（Context）、`intent`（原始 Intent）、`result`（修改后的 Intent）
- **自定义导入和方法** - 每条规则可定义自己的 imports、字段和辅助方法
- **规则优先级** - 优先级越高的规则越先被检查
- **Material Design 3 UI** - 现代外观，支持动态颜色
- **导出 / 导入** - 备份和恢复，每条规则独立解决冲突（替换 / 忽略 / 重命名）
- **跨框架兼容** - 通过双重传输（XSharedPreferences + ContentProvider）支持 LSPosed、EdXposed、FPA、npatch

## 支持的启动器

- Lawnchair
- Pixel Launcher
- Launcher3
- 任何使用 Instrumentation 或 Launcher3 的启动器

## 安装

1. 安装 APK
2. 在 LSPosed/Xposed 中启用模块
3. 配置模块作用域以包含你的启动器
4. 打开应用添加规则

## 快速使用

1. 进入**规则**页面
2. 点击 **+**
3. 编写 Java 代码：
   - **Condition**：返回 `boolean` — 返回 `true` 时执行 Action
   - **Action**：修改 `result` Intent
4. 点击**测试编译**，然后**保存**

示例 — 重定向抖音到特定 Tab：

```java
// Imports
import android.net.Uri;

// Members
public static String getPkg(Intent i) {
    return i.getPackage() != null ? i.getPackage()
        : i.getComponent() != null ? i.getComponent().getPackageName() : null;
}

// Condition
return "com.ss.android.ugc.aweme".equals(getPkg(intent));

// Action
result.setData(Uri.parse("snssdk1128://land_tab?tabid=homepage_notification"));
```

## 构建

```bash
./gradlew assembleDebug
```

## 许可证

MIT 许可证