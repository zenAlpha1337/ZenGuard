# sing-box Core Binary (`libbox.aar`)

This directory is the designated location for `libbox.aar`, the Android archive containing the native Go `sing-box` core compiled via `gomobile`.

---

## 1. Quick Start

Place the compiled `libbox.aar` file directly into this directory:
```
app/libs/libbox.aar
```

ZenGuard will automatically discover and bind to it at runtime via `LibboxBridge`.

> **Note:** If `libbox.aar` is not present, ZenGuard will automatically run in **stub simulation mode**. In stub mode, the `VpnService` TUN interface is still established, and traffic statistics / notifications function with realistic simulated telemetry. The project will compile and run cleanly without errors even before this file is added.

---

## 2. Building `libbox.aar` from Source

To build `libbox.aar` from the official `SagerNet/sing-box` repository:

### Prerequisites
- **Go**: 1.22 or newer
- **Android NDK**: r26b or newer (set `ANDROID_NDK_HOME`)
- **gomobile**: installed via `go install golang.org/x/mobile/cmd/gomobile@latest`

### Steps

1. **Clone the repository:**
   ```bash
   git clone --recurse-submodules https://github.com/SagerNet/sing-box.git
   cd sing-box
   ```

2. **Initialize gomobile:**
   ```bash
   gomobile init
   ```

3. **Build the Android AAR:**
   ```bash
   go run ./cmd/internal/build_aar \
     -target android \
     -tags with_gvisor,with_quic,with_dhcp,with_wireguard,with_ech,with_utls
   ```
   *(Alternatively, use `gomobile bind` targeting `io.nekohasekai.libbox`)*

4. **Copy output to ZenGuard:**
   Copy the generated `libbox.aar` into:
   ```
   ZenGuard/app/libs/libbox.aar
   ```

---

## 3. Gradle Configuration

In `app/build.gradle.kts`, ensure file-based library dependencies are enabled:

```kotlin
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
```
