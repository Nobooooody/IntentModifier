# Intent Modifier - 规划

[English documentation](./roadmap.md)

## 项目概述

Intent Modifier 是一个安卓 Xposed 模块，用于拦截应用启动并实时修改 intent，实现高级应用启动和 intent 重定向功能。

## 未来功能

### 1. 灵活的规则引擎（支持 Java 代码）

实现强大的规则引擎，允许直接编写 Java 代码以获得最佳性能和灵活性：

- **Java 代码条件**
  - 在条件字段直接编写 Java 表达式
  - 通过内置 `intent` 对象访问 Intent 属性
  - 示例：`intent.getPackage().contains("com.baidu")`
  - 内置变量：`intent`（原始 Intent），`result`（修改后的 Intent）

- **Java 代码操作**
  - 支持完整 Java 代码，包括 if/else、for 循环等控制流
  - 使用 Java 代码进行动态转换
  - 直接调用 Intent 的 setter 方法
  - 使用 Java String API 进行字符串操作
  - 带 if/else 的示例：
    ```java
    if (intent.getPackage().contains("com.baidu")) {
        result.setPackage(intent.getPackage().replace("com.baidu", "com.custom.baidu"));
    } else if (intent.getPackage().contains("com.google")) {
        result.setAction("android.intent.action.VIEW");
    }
    ```
  - 修改 intent 的 API：`setAction()`, `setPackage()`, `setComponent()`, `setData()`, `setType()`, `addCategory()`, `removeCategory()`, `addFlags()`, `removeFlags()`, `putExtra()`, `removeExtra()`

- **执行方式**
  - **方案 A：JavaCompiler** - 将用户代码片段包装成类模板，在规则保存时使用 `javax.tools.JavaCompiler` 编译成字节码，运行时通过 ClassLoader 加载。性能最佳，但会增加 APK 体积。
  - **方案 B：反射解析器** - 将表达式如 `intent.getPackage().contains("xxx")` 解析成方法链，运行时通过反射逐个调用。实现简单，对于 intent 拦截场景性能可接受。
  - **方案 C：脚本引擎（可选）** - 集成 QuickJS 等轻量引擎，在性能和灵活性之间取得平衡。

- **高级功能**
  - 调用系统 API：`ComponentName`、`Uri`、`Bundle` 操作
  - 访问 Android 系统服务（需适当权限）

- **Intent 字段匹配**
  - 按 action 匹配（如 `android.intent.action.MAIN`）
  - 按 component/package 匹配
  - 按 data URI 模式匹配（支持正则）
  - 按 extras 匹配（键值条件）

- **条件转换**
  - IF 条件 → THEN 操作
  - 多规则优先级排序
  - 规则分组管理

- **高级转换**
  - 修改 intent action
  - 修改/重定向到不同 component
  - 添加/删除/修改 extras
  - 修改 data URI
  - 添加 categories 或 flags

### 2. 用户界面增强

- 规则模板（常用场景预定义）
- 带版本控制的规则导出/导入
- 规则测试/预览模式
- 详细 intent 日志查看器
- 规则开关控制

### 3. Hook 方法扩展

- 支持更多启动器 hook 方法
- 自定义 hook 注入点
- 按应用选择 hook 方式

### 4. 额外的 Intent 修改

- 支持所有 Intent extras 类型
- MIME 类型操作
- Intent flags 管理
- Category 管理

### 6. 性能优化

- 优化 UI 渲染
- 减少应用启动时间
- 改善列表滚动流畅度
- 规则列表延迟加载

### 5. 备份与同步

- 云端备份集成
- 规则版本控制和回滚
- 设备间规则同步