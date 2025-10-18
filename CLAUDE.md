# Claude Code Notes for Forge

## Building Forge Distribution

### Correct Build Command
The proper way to build Forge with all executables and create the complete distribution package is:

```bash
mvn -U -B clean -P windows-linux install
```

This builds:
- Desktop version (forge.exe and launchers)
- Mobile/Adventure mode (forge-adventure.exe and launchers)
- Adventure Editor
- Complete distribution packages (.tar.bz2 and .jar installer)

## Building Mobile/Adventure Mode

### Module Structure
The mobile/adventure mode consists of several Maven modules:
- **forge-gui-mobile** - Core mobile library (LibGDX-based, Java 17)
- **forge-gui-mobile-dev** - Desktop version of adventure mode
- **forge-gui-android** - Android APK build
- **forge-gui-ios** - iOS build (RoboVM backend)

### Desktop Adventure Mode Only
To build only the desktop version of adventure mode:

```bash
cd forge-gui-mobile-dev
mvn clean package
```

Output: `forge-gui-mobile-dev-{version}-jar-with-dependencies.jar` in `target/`

### Android APK Build
To build the Android debug APK:

```bash
mvn clean install -P android-debug
```

Available Android profiles:
- `android-debug` - Debug APK with debug signing
- `android-release-build` - Release APK signed with keystore
- `android-test-build` - Test APK with embedded debug keystore
- `android-dev-build` - Development APK with renamed package ID

### Technical Details
- Graphics Framework: LibGDX 1.13.5
- Desktop Backend: LWJGL3
- Min Android API: 26 (Android 8.0)
- Target Android SDK: 35
- Compiler: Java 17
