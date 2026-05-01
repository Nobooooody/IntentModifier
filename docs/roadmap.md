# Intent Modifier - Roadmap

[中文文档](./roadmap_cn.md)

## Project Overview

Intent Modifier is an Android Xposed module that intercepts app launches and modifies intents in real-time, enabling advanced app launching and intent redirection capabilities.

## Future Features

### 1. Rule Engine with Pattern Matching

Implement a powerful rule engine that allows users to define complex matching conditions and transformations:

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