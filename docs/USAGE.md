# Intent Modifier - Usage

[中文文档](./USAGE_cn.md)

## Quick Start

### Installation

1. Install the APK
2. Enable the module in LSPosed/Xposed
3. Configure the module scope to include your launcher
4. Open the app to configure rules

### Adding a Rule

1. Go to the **Rules** tab
2. Tap the **+** button
3. Write your Java code rule:
   - **Imports**: Custom `import` statements (e.g., `import android.net.Uri;`)
   - **Members**: Class fields and helper methods
   - **Condition**: Java code that returns `boolean`
   - **Action**: Java code modifying the `result` Intent
4. Set priority (higher = checked first)
5. Tap **Test Compile** to check for errors
6. Tap **Save** to compile and activate

### Rule Fields

| Field | Description |
|-------|-------------|
| Imports | Custom `import` statements for external classes |
| Members | Class fields and helper methods |
| Condition | Java code returning `boolean` — determines if the rule matches |
| Action | Java code modifying the `result` Intent |
| Priority | Higher number = checked first (first match wins) |

### Built-in Variables

In **Condition** and **Action**, you have access to:

- `ctx` — `android.content.Context` — use for logging, Toast, system services
- `intent` — `android.content.Intent` — the **original** Intent being launched
- `result` — `android.content.Intent` — the **modified** Intent (what gets launched)

### Example Rules

**Redirect TikTok to a specific tab:**
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

**Redirect Baidu to a custom package:**
```java
// Condition
return intent.getPackage() != null && intent.getPackage().contains("baidu");

// Action
result.setPackage("com.example.custom");
```

**Log and modify:**
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

### Configuring Launcher Hooks

1. Go to the **Launchers** tab
2. Tap **+**
3. Select a launcher app
4. Choose hook method:
   - **Instrumentation (default)**: Hooks `android.app.Instrumentation.execStartActivity` — works for most apps
   - **Launcher3**: Hooks `com.android.launcher3.Launcher.startActivitySafely` — for Lawnchair/Pixel Launcher variants
   - **Custom**: Hook a user-specified class

### Export / Import

**Export:**
- Tap the menu (⋮) → Export to File or Export to Clipboard

**Import:**
- Tap the menu (⋮) → Import from File or Import from Clipboard
- Conflicts are resolved per-rule with four options:
  - **Replace**: Overwrite old rule with new
  - **Ignore**: Keep old rule, discard new
  - **Rename Old→_old**: Keep both, rename old rule
  - **Rename New→_new**: Keep both, rename new rule

### Changing Language

1. Go to the **Settings** tab
2. Tap the language card
3. Select **Follow System**, **English**, or **Chinese**

## Building

```bash
./gradlew assembleDebug
```