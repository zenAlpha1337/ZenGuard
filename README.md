# ZenGuard

Modern Android VPN client powered by **sing-box**, built with **Kotlin + Jetpack Compose + Material Design 3** (Dynamic Color & Expressive spring animations).

## Key Features
- **sing-box Core (`libbox`)**: VLESS (Reality/Vision), VMess, Trojan, Shadowsocks, Hysteria2, TUIC.
- **Flexible Proxy Modes**:
  - `VPN (TUN)` via Android `VpnService` (non-root)
  - `Root + TProxy` (`iptables` / `nftables` + `ip rule` / `ip route` via `libsu`, UID split tunneling & loop protection)
  - `Root + TUN` & `Local Proxy Only`
- **Material Design 3 Expressive UI**: Dynamic Color (Material You), morphing connect button, spring physics, live Bezier traffic chart (`Canvas`), animated ping badges.
- **Samsung One UI Now Bar Integration**:
  - Native Android 16 Live Updates (`POST_PROMOTED_NOTIFICATIONS`)
  - Built-in **LSPosed / Xposed hook** (`NowBarHook`) for `com.android.systemui` & `com.samsung.android.app.aodservice` to display VPN status, active mode, and quick toggle directly in One UI Now Bar.

## Architecture & Roadmap
- [x] **Stage 1**: Project scaffold, MD3 theme & animations, navigation, Now Bar & LSPosed hook base
- [x] **Stage 2**: `sing-box` config generator, `CoreEngine`, `ZenVpnService` + `LibboxBridge`
- [x] **Stage 3**: Root engine (`RootShellManager` via `libsu`), `TProxyManager` (`iptables`/`nftables`), `RootEngineController`
- [x] **Stage 4**: URI parsers (`vless`, `vmess`, `trojan`, `ss`, `hy2`, `tuic`), `SubscriptionManager`, parallel TCP ping, `ProfileRepository`
- [x] **Stage 5**: Expressive MD3 screens (`Home`, `Profiles`, `Routing`, `Settings`) & live `TrafficChart`

## Authors & Co-authors
- **[@zenAlpha1337](https://github.com/zenAlpha1337)** — Creator & Maintainer
- **Antigravity** (Google Deepmind AI Assistant) — Co-author (architecture, implementation & MD3 UI)
