# Intent Modifier

[查看中文文档](./README_cn.md)

## What It Does

Intent Modifier hooks into popular Android launchers (Lawnchair, Pixel Launcher, Launcher3) to intercept app launch intents and modify them based on user-defined rules. This allows you to:

- **Customize app launch behavior** - Redirect apps to different activities
- **Modify intent extras** - Add or override extra parameters
- **Change intent actions** - Override the action when launching apps
- **Override data URIs** - Set custom data for specific apps

## Features

- **Material Design 3 UI** - Modern look with dynamic colors from your wallpaper
- **Export/Import rules** - Backup and restore your configuration
  - Export to file or clipboard
  - Import from file or clipboard with conflict resolution options:
    - Replace all (delete old rules)
    - Keep new rules on conflict
    - Keep old rules on conflict
- **Per-app configuration** - Configure different modifications for each app
- **Extras support** - Support for multiple extra types (Boolean, Integer, Long, String, etc.)

## Supported Launchers

- Lawnchair (app.lawnchair, app.lawnchair.play)
- Pixel Launcher (com.google.android.apps.nexuslauncher)
- Launcher3 (com.android.launcher3, com.android.launcher)

## Installation

1. Install the APK
2. Enable the module in LSPosed/Xposed
3. Configure the module scope to include your launcher
4. Open the app to add rules

## Usage

1. Tap the **+ Add App Rule** button to add a new rule
2. Select an app to configure
3. Set the desired modifications:
   - Custom Action
   - Custom Data (URI)
   - Custom Package
   - Custom Class
   - Extras (key-value pairs with type)
4. Toggle the switch to enable/disable each rule

## Building

This project was created using **MiniMax M2.5** as an AI coding assistant (vibe coding approach).

```bash
./gradlew assembleDebug
```

## License

MIT License