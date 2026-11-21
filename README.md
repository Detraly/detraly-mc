# Detraly for Minecraft

![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)
![Spigot 1.21+](https://img.shields.io/badge/Spigot-1.21%2B-orange)
![Fabric 1.21.1 and 26.3](https://img.shields.io/badge/Fabric-1.21.1%20%7C%2026.3-lightgrey)
![NeoForge 1.21.1](https://img.shields.io/badge/NeoForge-1.21.1-orange)
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
- Economy analytics through Vault: balances, transfers, admin actions and wealth distribution (Paper and Spigot, see [Economy](#economy))
- Runs on Bukkit, Spigot, Paper and Folia (Purpur and other forks too) and on Fabric and NeoForge dedicated servers (see [Folia](#folia))

## Installation and Setup

1. Create a server in the [Detraly dashboard](https://dashboard.detraly.com) and copy its link code.
2. Download the jar from Releases tab
3. Put it in `plugins/` (Paper, Spigot) or `mods/` (Fabric, NeoForge) and restart.
4. In the **server console**, not in-game chat, run `detraly link <code>`.
5. Check with `detraly status`.

## Usage and Commands

### Permissions

Admin commands need `detraly.admin` on Paper (operators by default) or op level 4 on Fabric and NeoForge.

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
| `/detraly status` | Shows link, geolocation notice, live connection and economy state |

### Configuration

Paper: `plugins/DetralyMain/config.yml`. Fabric and NeoForge: `config/detraly/config.properties` (same keys).

| Setting | Default | Meaning |
| --- | --- | --- |
| `api-url` | `https://api.detraly.com` | Leave it unless Detraly support says otherwise |
| `behind-proxy` | `false` | Set `true` on a backend behind BungeeCord or Velocity: no IP is sent |
| `live-connection` | `true` | Online status, stats, specs and console output. `false` turns them all off |
| `profiler` | `true` | Paper only. Needs `live-connection` |
| `economy.*` | see [Economy](#economy) | Paper and Spigot only. Needs `live-connection` |

The same folder holds `token.dat` (your API token, do not share it; if it leaks, link again with a new code) and
`optouts.yml` (UUIDs of players who opted out, and of those whose opt-out Detraly has not confirmed yet).

### Economy

Servers that run a [Vault](https://github.com/MilkBowl/VaultAPI) economy (EssentialsX Economy, CMI and any other
Vault provider) can send economy analytics to the dashboard. Economy works on Paper and Spigot (and forks) with Vault.
It is not supported on Fabric or NeoForge yet: there `/detraly status` says so and nothing economy-related runs. It needs the live connection (`live-connection: true`) and a Detraly plan that
includes Economy; the plugin only sends it when the API offers it.

Vault has no events, so Detraly observes the economy from outside. It never changes money, never wraps or replaces
your economy plugin, and reads balances only on the main server thread (at most 1 ms per tick, spread over ticks).

What is sent, and when:

- **Balances** (player name, UUID, balance): when a player joins and leaves, every `poll-seconds` for online players
  (only when the balance changed), and in a slow sweep of every player the server knows (every `sweep-hours`, 5 minutes
  after start at the earliest).
- **Transfers**: when a player runs a pay command (`/pay <player> <amount>` and the configured variants), the plugin
  reads both balances before and 2 ticks after. A transfer is reported only when the money really moved.
- **Admin actions**: `eco give|take|set|reset` (and the configured variants), typed by a player or in the console,
  confirmed the same way. Console actions carry no actor.
- **The economy itself**: the provider's name, currency names, decimals and whether it has banks.

What is never sent: the text of a command (only the label that matched, such as `pay` or `eco`), IP addresses, bank
accounts, or accounts that are not players. Players who ran `/detraly optout` are left out completely: no balances,
and any transfer or admin action that names them is dropped.

If the economy provider is slow (a balance read takes over 5 ms on average, for example with a database-backed
economy), Detraly reads balances less often (up to every 10 minutes) and pauses the sweep, and says so once in the log.
A provider that throws an error never affects the server; it is logged at most once every 10 minutes. The time of the
last sweep is kept in `economy-state.properties` in the plugin folder.

The `economy` section of `config.yml` (all keys optional):

| Setting | Default | Meaning |
| --- | --- | --- |
| `economy.enabled` | `true` | `false` turns economy analytics off |
| `economy.poll-seconds` | `30` | How often online players' balances are re-read (minimum 10) |
| `economy.sweep-hours` | `24` | How often every known player's balance is read (`0` = never) |
| `economy.pay-commands` | `pay`, `epay`, `money pay` | Patterns for pay commands. `{target}` and `{amount}` are read from what the player typed |
| `economy.admin-commands` | `eco`, `economy`, `eeco`, `money` | Patterns for admin commands. `{action}` is `give`, `take`, `set` or `reset` |

`/detraly status` shows an Economy line: the provider and how many players are tracked, `not found` (no Vault economy),
`off`, or `waiting for the API` (the live connection is off or the API does not offer economy).

### Folia

The same Paper jar runs on Folia (it declares `folia-supported`). On Folia:

- Linking, status, opt-out and opt-in, join and leave, console lines and the Pulse connection work as on Paper.
- Economy analytics are not supported (Vault economies are not safe across Folia regions); `/detraly status` says so.
- The tick profiler is not available (there is no single main thread to sample).
- TPS comes from the global region only. Per-tick timing (mspt) is left out, and entity and chunk counts are left out
  when Folia does not allow reading them.
- On Spigot, a console line from a plugin carries the plugin's main class name as its source, where Paper and Folia
  carry the plugin name.

### Environment requirements

- JDK 21 and Maven 3.9+ for the SDK and Paper
- JDK 21 for Fabric 1.21.1 and NeoForge 1.21.1, JDK 25 for Fabric 26.3 (Gradle wrapper included in each)

### Building

```
mvn package                      # bukkit/target/Detraly-Bukkit-<version>.jar
cd fabric && ./gradlew build     # Fabric 1.21.1: fabric/build/libs/Detraly-Fabric-<version>.jar
cd fabric-26.3 && ./gradlew build  # Fabric 26.3: fabric-26.3/build/libs/Detraly-Fabric-<version>.jar
cd neoforge && ./gradlew build   # NeoForge 1.21.1: neoforge/build/libs/Detraly-NeoForge-<version>-mc1.21.1.jar
```

### Layout

```
sdk/      Shared core: API client, queue, pulse, commands (platform-free)
bukkit/   Bukkit, Spigot, Paper and Folia plugin: thin Bukkit adapters over the SDK
fabric/   Fabric 1.21.1 mod: Gradle build that compiles the SDK sources with its own
fabric-26.3/ Fabric 26.3 mod (unobfuscated, Java 25): shares the SDK, has its own copy of the Fabric classes
neoforge/ NeoForge 1.21.1 mod (NeoForge 21.1, Java 21): Gradle build that compiles the SDK sources with its own
builds/   Release jars
```

New platforms (Velocity, Quilt, ...) go in their own folder on top of `sdk/`.

The `*Check` classes under `sdk/src/test` and `bukkit/src/test` are plain programs, not a test framework. Run one
with `java -cp "target/classes:target/test-classes:<dependencies>" <class>` (`;` on Windows;
`mvn dependency:build-classpath` prints the dependencies). It exits with code 1 if a case fails.

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## About

Copyright (C) 2026 The Detraly authors, licensed under the GNU GPL version 3 ([LICENSE](LICENSE)). The license
covers this plugin only, not the Detraly service, API or dashboard. "Detraly" and its logo are not licensed for
use by forks.
