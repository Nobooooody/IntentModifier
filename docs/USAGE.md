# Intent Modifier - Usage

[中文文档](./usage_cn.md)

## Quick Start

### Installation

1. Install the APK
2. Enable the module in LSPosed/Xposed
3. Configure the module scope to include your launcher
4. Open the app to configure rules

### Adding a Rule

1. Tap the **+** button on the Rules page
2. Select an app to configure
3. Set the desired modifications:
   - **Custom Action**: Override the intent action (e.g., `android.intent.action.VIEW`)
   - **Custom Data**: Set a custom data URI
   - **Custom Package**: Redirect to a different app
   - **Custom Class**: Override the activity class
   - **Flags**: Set intent flags (e.g., `270532608`)
   - **Categories**: Add intent categories
   - **MIME Type**: Set the MIME type
   - **Extras**: Add key-value pairs with specific types

### Understanding Intent Fields

| Field | Description | Example |
|-------|-------------|---------|
| Action | The intent action | `android.intent.action.MAIN` |
| Data | URI for the intent | `content://com.example.app` |
| Package | Target app package | `com.android.settings` |
| Class | Target activity class | `.MainActivity` |
| Flags | Activity launch flags | `270532608` |
| Category | Intent category | `android.intent.category.LAUNCHER` |
| Type | MIME type | `image/png` |

### Extra Types

Supported extra value types:

- **Boolean** - `true` or `false`
- **Integer** - Whole numbers (e.g., `123`)
- **Long** - Large numbers (e.g., `9223372036854775807`)
- **Float** - Decimal numbers (e.g., `3.14`)
- **String** - Text values
- **URI** - Content or file URIs

### Array Types

- **BooleanArray** - Multiple boolean values
- **IntegerArray** - Multiple integers
- **LongArray** - Multiple long values
- **FloatArray** - Multiple floats
- **StringArray** - Multiple strings
- **ByteArray**, **CharArray**, **ShortArray**, **DoubleArray**

### Configuring Launcher Hooks

1. Go to the **Launchers** page
2. Add the launcher app
3. Select hook method:
   - **Instrumentation (default)**: Hooks `android.app.Instrumentation.execStartActivity`
   - **Launcher3**: Hooks `com.android.launcher3.Launcher.startActivitySafely`

### Export/Import

**Export:**
- Tap menu → Export to File or Export to Clipboard

**Import:**
- Tap menu → Import from File or Import from Clipboard
- Choose conflict resolution:
  - **Replace All**: Delete old rules
  - **Keep New**: Overwrite on conflict
  - **Keep Old**: Skip on conflict

### Changing Language

1. Go to **Settings** page
2. Select language:
   - **Follow System**
   - **English**
   - **Chinese**