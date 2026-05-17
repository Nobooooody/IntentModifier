# Intent Modifier - Roadmap

[中文文档](./roadmap_cn.md)

## Project Overview

Intent Modifier is an Android Xposed module that intercepts app launches and modifies intents in real-time, enabling advanced app launching and intent redirection capabilities.

## Completed Features

### Rule Engine with Java Code Support ✅

- **Java Code Conditions & Actions** - ECJ compilation → D8 → DEX → DexClassLoader
- **Imports & Members** - Free-form Java code support (import statements, class members)
- **Rule Priority** - Numeric priority ordering (higher = earlier)
- **Rule Import/Export** - JSON clipboard/file export/import with conflict resolution
- **Conflict Resolution** - Per-rule actions: Replace / Ignore / Rename Old→_old / New→_new

### Rule Data Communication (Module → Hooked App)

- **XSharedPreferences** (primary) - Fast, works on native Xposed (LSPosed/EdXposed)
- **ContentProvider** (fallback) - Works on virtualized Xposed (FPA/npatch/etc)
- **Local Caching** - DEX + meta.json cached in target app's `/cache/intent_modifier_rules/`
- **Runtime Loading** - Rules loaded lazily on first hook callback, version-checked each time
- **Full Fallback Chain** - XSharedPreferences → ContentProvider → Local Cache

### Hook Methods

- **Instrumentation** (default) - Hooks `android.app.Instrumentation.execStartActivity`
- **Launcher3** - Hooks `com.android.launcher3.Launcher.startActivitySafely`
- **Custom Class** - Hooks user-specified class's `startActivity` methods

### UI

- **Rules Page** - List with enable/disable/edit/delete, FAB to add, export/import menu
- **Launchers Page** - Per-app hook type selection with add/delete
- **Settings Page** - Language selector with MaterialCard styling
- **App Picker** - Loading spinner, real-time search, refresh button in toolbar

## Pending Features

### 1. User Interface Enhancements

- Rule templates (predefined patterns)
- Rule testing/preview mode
- Detailed intent logging viewer
- Rule groups/organization

### 2. Hook Method Extensions

- More launcher hook methods
- Custom hook point injection
- Per-app hook method selection UI

### 3. Additional Intent Modifications

- Support for all Intent extras types
- MIME type manipulation
- Intent flags management
- Category management

### 4. Backup & Sync

- Cloud backup integration
- Rule versioning and rollback
- Device-to-device rule sync

### 5. Performance Optimization

- Optimize UI rendering
- Improve list scrolling smoothness
- Lazy loading for rule lists

---

## Technical Architecture

### Data Flow

```
[App Side]
  Rule Editor → ModifierRepository (SharedPreferences)
            → RuleCompilationManager (ECJ + D8)
            → Base64 DEX stored in SharedPreferences
            → RuleProvider (ContentProvider) for exported endpoints

[Xposed Side]
  handleLoadPackage → register hook callbacks
                    → (on first hook trigger)
  beforeHookedMethod → ensureRulesLoaded()
                         → tryGetRemoteVersion() [XSP → CP]
                         → compare with local meta.json
                         → download DEX if outdated
                         → loadDexFromFile() → CompiledRules
                    → applyRules() → match conditions, execute actions
```

### Module Structure

```
io.github.nobooooody.intent_modifier
├── XposedInit.kt                    # Hook entry + rule loading + applyRules
├── IntentModifierApp.kt             # Application class (if needed)
├── data/
│   ├── JavaCodeRule.kt              # Rule data model
│   └── ModifierRepository.kt         # SharedPreferences read/write
├── engine/
│   └── RuleCompilationManager.kt     # ECJ → D8 → DEX compilation
├── compiler/
│   ├── JavaEngineSetting.kt         # JDK JAR paths, classpath
│   └── JavaPrintWriter.kt           # Compilation log capture
└── ui/
    ├── MainActivity.kt              # Tab navigation (Rules/Launchers/Settings)
    ├── JavaCodeRuleEditorActivity.kt # Rule editor with imports/members/condition/action
    ├── AppPickerActivity.kt         # App selection with search & refresh
    └── provider/
        └── RuleProvider.kt          # ContentProvider for hash/count/dex/version/meta
```

### Key Files

| File | Purpose |
|------|---------|
| `XposedInit.kt` | Hook registration, rule loading, intent modification |
| `RuleCompilationManager.kt` | Compile Java code snippets to DEX |
| `ModifierRepository.kt` | Persist rules and launcher hooks |
| `JavaCodeRuleEditorActivity.kt` | Edit rule (imports/members/condition/action/priority) |
| `RuleProvider.kt` | ContentProvider endpoints for virtualized Xposed |
| `MainActivity.kt` | Navigation, import/export dialogs, conflict resolution |

### Communication Endpoints (RuleProvider)

| Path | Returns | Used When |
|------|---------|-----------|
| `/meta` | version, hash, count | Full metadata in one query |
| `/dex` | Base64 DEX | Download compiled rules |
| `/version` | Long | Quick version check |
| `/hash` | String | Hash verification |
| `/count` | Int | Rule count for DexClassLoader |

### Known Compatibility

| Xposed Framework | XSP | ContentProvider |
|---|---|---|
| LSPosed / EdXposed | ✅ Works | ✅ Works (fallback) |
| FPA | ❌ Fails | ✅ Works |
| npatch | ❌ Fails | ✅ Works |

### Next Refactoring Tasks

- [ ] Extract `RuleLoader` from `XposedInit` for cleaner separation
- [ ] Move `LauncherHooksLoader` into `data/` package
- [ ] Add unit tests for `RuleCompilationManager`
- [ ] Add unit tests for conflict resolution logic
- [ ] Consolidate string constants into companion object
- [ ] Remove unused imports across all files
- [ ] Add ProGuard/R8 rules for release builds