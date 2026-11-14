# Detraly for Minecraft

![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)
![Spigot 1.21+](https://img.shields.io/badge/Spigot-1.21%2B-orange)
![Fabric 1.21.1 and 26.3](https://img.shields.io/badge/Fabric-1.21.1%20%7C%2026.3-lightgrey)
![Java 21 and 25](https://img.shields.io/badge/Java-21%20(1.21)%20%7C%2025%20(26.x)-red)
![License GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-green)

## What is Detraly?

[Detraly](https://detraly.com) is an analytics dashboard for Minecraft servers: live insights, performance
tracking and player analytics in one place. This plugin links your server to your Detraly account and sends the
data the dashboard shows. It is open source so you can check exactly what it sends before you install it.

## Features

- Player joins and leaves, with optional country analytics (players can opt out of Detraly on every server; opting back in removes that opt-out everywhere except servers where the player opted out themselves)
- Live server status: TPS, MSPT, players, CPU, memory, disk and uptime
- Console output in the dashboard, with IP addresses, link codes, player commands and chat removed
- Tick time per plugin (Paper)
- Runs on Paper and Spigot (Purpur and other forks too) and on Fabric dedicated servers

## Installation and Setup

1. Create a server in the [Detraly dashboard](https://dashboard.detraly.com) and copy its link code.
2. Download the jar from Releases tab
3. Put it in `plugins/` (Paper, Spigot) or `mods/` (Fabric) and restart.
4. In the **server console**, not in-game chat, run `detraly link <code>`.
5. Check with `detraly status`.

## Usage and Commands

### Permissions

Admin commands need `detraly.admin` on Paper (operators by default) or op level 4 on Fabric.

### User commands

| Command | What it does |
| --- | --- |
| `/detraly` | Shows help |
| `/detraly optout` | Opts you out of Detraly. This server stops sending your joins, leaves and IP address at once, and Detraly discards them from any other server too. Server console output is not affected and may still mention your name. If Detraly cannot be reached the choice is kept on this server and retried (a few times, then again the next time you join) |
| `/detraly optin` | Removes your Detraly opt-out, so tracking resumes on every server except servers where you ran `/detraly optout` yourself (the choice kept in that server's `optouts.yml`); run `/detraly optin` on each of those too. Detraly must confirm it first; if it cannot (or there is no connection) you stay opted out and are told to try again |

Player privacy notes:

- Joins and leaves are tracked as a pair. If you opt out mid-session, your earlier join may stay on the dashboard
  as an unmatched join until it ages out. If you opt in mid-session, a leave can be recorded without a join.
- Upgrading: entries in an older `optouts.yml` (which meant "no IP on this server, joins still counted") become
  full global opt-outs. They are synced to Detraly the next time that player joins.

### Administrator commands

| Command | What it does |
| --- | --- |
| `/detraly link <code>` | Links the server (console only). A new code replaces the old token |
| `/detraly status` | Shows link, geolocation notice and live connection state |

### Configuration

Paper: `plugins/DetralyMain/config.yml`. Fabric: `config/detraly/config.properties` (same keys).

| Setting | Default | Meaning |
| --- | --- | --- |
| `api-url` | `https://api.detraly.com` | Leave it unless Detraly support says otherwise |
| `behind-proxy` | `false` | Set `true` on a backend behind BungeeCord or Velocity: no IP is sent |
| `live-connection` | `true` | Online status, stats, specs and console output. `false` turns them all off |
| `profiler` | `true` | Paper only. Needs `live-connection` |

The same folder holds `token.dat` (your API token, do not share it; if it leaks, link again with a new code) and
`optouts.yml` (UUIDs of players who opted out, and of those whose opt-out Detraly has not confirmed yet).

### Environment requirements

- JDK 21 and Maven 3.9+ for the SDK and Paper
- JDK 21 for Fabric 1.21.1, JDK 25 for Fabric 26.3 (Gradle wrapper included in each)

### Building

```
mvn package                      # paper/target/Detraly-Paper-<version>.jar
cd fabric && ./gradlew build     # Fabric 1.21.1: fabric/build/libs/Detraly-Fabric-<version>.jar
cd fabric-26.3 && ./gradlew build  # Fabric 26.3: fabric-26.3/build/libs/Detraly-Fabric-<version>.jar
```

### Layout

```
sdk/      Shared core: API client, queue, pulse, commands (platform-free)
paper/    Paper and Spigot plugin: thin Bukkit adapters over the SDK
fabric/   Fabric 1.21.1 mod: Gradle build that compiles the SDK sources with its own
fabric-26.3/ Fabric 26.3 mod (unobfuscated, Java 25): shares the SDK, has its own copy of the Fabric classes
builds/   Release jars
```

New platforms (Velocity, NeoForge, ...) go in their own folder on top of `sdk/`.

The `*Check` classes under `sdk/src/test` and `paper/src/test` are plain programs, not a test framework. Run one
with `java -cp "target/classes:target/test-classes:<dependencies>" <class>` (`;` on Windows;
`mvn dependency:build-classpath` prints the dependencies). It exits with code 1 if a case fails.

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## About

Copyright (C) 2026 The Detraly authors, licensed under the GNU GPL version 3 ([LICENSE](LICENSE)). The license
covers this plugin only, not the Detraly service, API or dashboard. "Detraly" and its logo are not licensed for
use by forks.
