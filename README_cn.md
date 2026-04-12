# Intent Modifier

[View English documentation](./README.md)

## 功能介绍

Intent Modifier 会钩入主流 Android 启动器（Lawnchair、Pixel Launcher、Launcher3），拦截应用启动的 Intent，并根据用户定义的规则进行修改。这允许您：

- **自定义应用启动行为** - 将应用重定向到不同的 Activity
- **修改 Intent Extras** - 添加或覆盖额外参数
- **更改 Intent Action** - 覆盖启动应用时的 Action
- **覆盖 Data URI** - 为特定应用设置自定义数据

## 功能特性

- **Material Design 3 UI** - 现代外观，支持动态颜色（从壁纸提取颜色）
- **导出/导入规则** - 备份和恢复配置
  - 导出到文件或剪贴板
  - 从文件或剪贴板导入，支持冲突解决：
    - 替换全部（删除旧规则）
    - 冲突时保留新规则
    - 冲突时保留旧规则
- **按应用配置** - 为每个应用配置不同的修改
- **Extras 支持** - 支持多种类型（Boolean、Integer、Long、String 等）

## 支持的启动器

- Lawnchair (app.lawnchair, app.lawnchair.play)
- Pixel Launcher (com.google.android.apps.nexuslauncher)
- Launcher3 (com.android.launcher3, com.android.launcher)

## 安装

1. 安装 APK
2. 在 LSPosed/Xposed 中启用模块
3. 配置模块作用域以包含您的启动器
4. 打开应用添加规则

## 使用方法

1. 点击 **+ 添加应用规则** 按钮添加新规则
2. 选择要配置的应用
3. 设置所需的修改：
   - 自定义 Action
   - 自定义 Data (URI)
   - 自定义 Package
   - 自定义 Class
   - Extras（键值对及其类型）
4. 使用开关启用/禁用每条规则

## 构建

本项目使用 **MiniMax M2.5** 作为 AI 编程助手完成（vibe coding 方式）。

```bash
./gradlew assembleDebug
```

## 许可证

MIT 许可证