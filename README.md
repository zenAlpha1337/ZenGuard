# ZenGuard

Modern Android VPN client powered by **sing-box**, built with **Kotlin + Jetpack Compose + Material Design 3** (Dynamic Color & Expressive spring animations).

## Key Features (Planned & In Progress)
- **sing-box Core (`libbox`)**: VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard, Reality.
- **Flexible Proxy Modes**:
  - `VPN (TUN)` via Android `VpnService` (non-root)
  - `Root + TProxy` (`iptables` / `nftables` + `ip rule` via `libsu`)
  - `Root + TUN` & `Local Proxy Only`
- **Material Design 3 Expressive UI**: Dynamic Color (Material You), morphing connect button, spring physics, live traffic telemetry.
- **Samsung One UI Now Bar Integration**:
  - Native Android 16 Live Updates (`POST_PROMOTED_NOTIFICATIONS`)
  - Optional built-in **LSPosed / Xposed hook** for `SystemUI` & `AODService` to display VPN status, active mode, and quick toggle directly in One UI Now Bar.

## Roadmap
- [x] Stage 1: Project scaffold, MD3 theme & animations, navigation, Now Bar & LSPosed hook base
- [ ] Stage 2: `libbox` integration & `VpnService` (TUN)
- [ ] Stage 3: Root engine (`libsu`) & `TProxy` (`iptables`/`nftables`)
- [ ] Stage 4: URI parsers, subscriptions, latency testing, Split Tunneling & `.srs` routing rules
- [ ] Stage 5: UI/UX polish, live connection inspector & release builds
