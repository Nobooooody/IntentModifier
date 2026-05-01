# Intent Modifier - 规划

[English documentation](./roadmap.md)

## 项目概述

Intent Modifier 是一个安卓 Xposed 模块，用于拦截应用启动并实时修改 intent，实现高级应用启动和 intent 重定向功能。

## 未来功能

### 1. 规则引擎与模式匹配

实现强大的规则引擎，允许用户定义复杂的匹配条件和转换：

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