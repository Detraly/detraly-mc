# Detraly for Minecraft

![Bukkit, Spigot and Paper](https://img.shields.io/badge/Bukkit%20%7C%20Spigot%20%7C%20Paper-1.21%20to%2026.3-brightgreen)
![Fabric 26.3](https://img.shields.io/badge/Fabric-26.3-brightgreen)
![NeoForge 26.3](https://img.shields.io/badge/NeoForge-26.3-brightgreen)
![License GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-green)

## What is Detraly?

[Detraly](https://detraly.com) is an analytics dashboard for Minecraft servers: live insights, performance tracking
and player analytics in one place. This plugin links your server to your Detraly account and sends the data the
dashboard shows. It is open source so you can check exactly what it sends.

## Features

- Player joins and leaves, with optional country analytics
- Live server status: TPS, players, CPU, memory and uptime
- Console output in the dashboard, with IP addresses, link codes, player commands and chat removed
- Economy analytics through Vault (Bukkit, Spigot and Paper only): balances, transfers and admin actions

## Installation and Setup

1. Create a server in the [Detraly dashboard](https://dashboard.detraly.com) and copy its link code.
2. Download the jar for your server from the Releases tab of this repository.
3. Put it in the `plugins` folder (Bukkit, Spigot, Paper) or the `mods` folder (Fabric, NeoForge).
4. Restart your server.
5. In the **server console**, run `detraly link <code>`.

You can check the connection any time with `detraly status`.

## Usage and Commands

Admin commands need the `detraly.admin` permission (operators have it by default), or op level 4 on Fabric and NeoForge.

```
/detraly                 Shows help
/detraly link <code>     Links the server (console only)           [admin]
/detraly status          Shows link, live connection and economy   [admin]
/detraly optout          Stops Detraly tracking you on every server
/detraly optin           Removes your opt-out
```

## Settings

Bukkit, Spigot and Paper: `plugins/DetralyMain/config.yml`. Fabric and NeoForge: `config/detraly/config.properties`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `api-url` | `https://api.detraly.com` | Leave it unless Detraly support says otherwise |
| `behind-proxy` | `false` | Set `true` behind BungeeCord or Velocity: no IP is sent |
| `live-connection` | `true` | Online status, stats and console output. `false` turns them off |
| `profiler` | `true` | Bukkit, Spigot and Paper only |
| `economy.enabled` | `true` | Bukkit, Spigot and Paper only |

`token.dat` in the same folder is your API token. Do not share it; if it leaks, link again with a new code.

## Developer Resources

`sdk/` is the shared core, and the other folders are thin adapters for each platform. Contributions are welcome:
see [CONTRIBUTING.md](CONTRIBUTING.md).

```
mvn package                          # bukkit/target/Detraly-Bukkit-<version>.jar (JDK 21)
cd fabric-26.3 && ./gradlew build    # Fabric 26.3 (JDK 25)
cd neoforge-26.3 && ./gradlew build  # NeoForge 26.3 (JDK 25)
```

## About

Copyright (C) 2026 The Detraly authors, licensed under the GNU GPL version 3 ([LICENSE](LICENSE)). The license
covers this plugin only, not the Detraly service, API or dashboard. "Detraly" and its logo are not licensed for
use by forks.
