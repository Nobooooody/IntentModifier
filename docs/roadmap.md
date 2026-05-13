# Intent Modifier - Roadmap

[中文文档](./roadmap_cn.md)

## Project Overview

Intent Modifier is an Android Xposed module that intercepts app launches and modifies intents in real-time, enabling advanced app launching and intent redirection capabilities.

## Future Features

### 1. Flexible Rule Engine with Java Code Support

Implement a powerful rule engine allowing direct Java code execution for maximum performance and flexibility:

- **Java Code Conditions**
  - Write Java expressions directly in condition field
  - Access to Intent properties via built-in `intent` object
  - Example: `intent.getPackage().contains("com.baidu")`
  - Built-in variables: `intent` (the original Intent), `result` (modified Intent)

- **Java Code Actions**
  - Full Java code support including if/else, for loops, etc.
  - Dynamic transformations using Java code
  - Direct call to Intent setter methods
  - String operations using Java String API
  - Example with if/else:
    ```java
    if (intent.getPackage().contains("com.baidu")) {
        result.setPackage(intent.getPackage().replace("com.baidu", "com.custom.baidu"));
    } else if (intent.getPackage().contains("com.google")) {
        result.setAction("android.intent.action.VIEW");
    }
    ```
  - Modify intent APIs: `setAction()`, `setPackage()`, `setComponent()`, `setData()`, `setType()`, `addCategory()`, `removeCategory()`, `addFlags()`, `removeFlags()`, `putExtra()`, `removeExtra()`

- **Execution Options**
  - **Option A: JavaCompiler** - Wrap user code snippets into a class template, compile to bytecode at rule save time using `javax.tools.JavaCompiler`, load via ClassLoader at runtime. Best performance but increases APK size.
  - **Option B: Reflection-based parser** - Parse expressions like `intent.getPackage().contains("xxx")` into method chains, invoke via reflection at runtime. Simpler implementation, acceptable performance for intent interception.
  - **Option C: Script engine (optional)** - Integrate lightweight engines like QuickJS for balance between performance and flexibility.

- **Advanced Features**
  - Call system APIs: `ComponentName`, `Uri`, `Bundle` manipulation
  - Access to Android system services (optional, with proper permissions)

- **Intent Field Matching**
  - Match by action (e.g., `android.intent.action.MAIN`)
  - Match by component/package
  - Match by data URI patterns (regex support)
  - Match by extras (key-value conditions)

- **Conditional Transformations**
  - IF conditions → THEN actions
  - Multiple rules with priority ordering
  - Rule groups for organization

- **Advanced Transformations**
  - Modify intent action
  - Modify/redirect to different component
  - Add/remove/modify extras
  - Modify data URI
  - Add categories or flags

### 2. User Interface Enhancements

- Rule templates (predefined patterns for common use cases)
- Rule export/import with versioning
- Rule testing/preview mode
- Detailed intent logging viewer
- Rule enable/disable toggle per rule

### 3. Hook Method Extensions

- Support more launcher hook methods
- Custom hook point injection
- Per-app hook method selection

### 4. Additional Intent Modifications

- Support for all Intent extras types
- MIME type manipulation
- Intent flags management
- Category management

### 6. Performance Optimization

- Optimize UI rendering
- Reduce app startup time
- Improve list scrolling smoothness
- Lazy loading for rule lists

### 5. Backup & Sync

- Cloud backup integration
- Rule versioning and rollback
- Device-to-device rule sync