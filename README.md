# Intent Modifier

[查看中文文档](./README_cn.md)

## What It Does

Intent Modifier is an Android Xposed module that intercepts app launches and modifies intents in real-time using user-defined Java code rules. Write Java to match intents and modify them however you want — redirect to a different activity, change the data URI, add extras, and more.

## Features

- **Java Code Rules** - Write real Java code for condition matching and intent modification
- **Rich Built-in Variables** - `ctx` (Context), `intent` (original), `result` (modified Intent)
- **Custom Imports & Methods** - Define your own imports, fields, and helper methods per rule
- **Rule Priority** - Higher priority rules are evaluated first
- **Material Design 3 UI** - Modern look with dynamic colors
- **Export / Import** - Backup and restore, per-rule conflict resolution (Replace / Ignore / Rename)
- **Cross-framework Compatible** - Works on LSPosed, EdXposed, FPA, npatch via dual delivery (XSharedPreferences + ContentProvider)

## Supported Launchers

- Lawnchair
- Pixel Launcher
- Launcher3
- Any launcher that uses Instrumentation or Launcher3

## Installation

1. Install the APK
2. Enable the module in LSPosed/Xposed
3. Configure the module scope to include your launcher
4. Open the app and add rules

## Quick Usage

1. Go to the **Rules** tab
2. Tap **+**
3. Write Java code:
   - **Condition**: returns `boolean` — if `true`, run the action
   - **Action**: modifies `result` Intent
4. Tap **Test Compile**, then **Save**

Example — redirect TikTok to a specific tab:

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

## Building

```bash
./gradlew assembleDebug
```

## License

MIT License