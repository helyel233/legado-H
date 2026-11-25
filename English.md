# LegadoH

[English](English.md) · [中文](README.md)

<p align="center"><img width="128" height="128" src="docs/legadoh_icon.svg" alt="LegadoH"></p>

<p align="center">
  <a href="https://github.com/helyel233/legado-H/releases"><img alt="Release" src="https://img.shields.io/github/v/release/helyel233/legado-H?label=release"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
</p>

<p align="center"><b>LegadoH</b> — a customizable Android reader for your own book sources and UI, built on <a href="https://github.com/Rimchars/legado">Reading Archive</a> and ultimately on <a href="https://github.com/gedoor/legado">Legado</a>.</p>

---

## About

LegadoH ships with no books and no book sources: online books come from sources you add yourself, and local books can be imported as TXT or EPUB. On top of everything inherited from Reading Archive and upstream Legado, it keeps pushing on **typesetting quality, the appearance system, reading tools and data safety** — native and independent EPUB rendering, page templates, mountable appearance packages, paragraph comments and highlight rules, read-aloud and media, an AI assistant, and backups covering sources, bookshelf, reading settings and assets.

The current version is **2.0.1**, the first release of the 2.0 appearance system. See the [changelog](CHANGELOG.md) for details and [Releases](https://github.com/helyel233/legado-H/releases) for APKs.

## Lineage

LegadoH is not a from-scratch project. It inherits a continuously evolving fork line:

```
gedoor/legado (Legado 3.0)
  └─ Luoyacheng/legado
       └─ Rimchars/legado ("Reading Archive")
            └─ helyel233/legado-H (LegadoH, this project)
```

- **Inherited from Reading Archive**: the independent EPUB layout engine, the page/loading template system, Reeden highlight rules (`.red`), the shared reading asset library, advanced titles and visual resource packages, and network/DNS routing. LegadoH continues on that codebase.
- **Inherited from upstream Legado**: custom book sources, replacement and purify rules, TTS, RSS, the built-in web service, WebDAV backup and other fundamentals from Legado 3.0 and its community forks.
- **What LegadoH adds**: independent branding and releases (helyel233/legado-H), an independent package name and signing setup (`io.legado.app.LegadoH`), and ongoing ports from community forks — including [legadoC](https://github.com/CCSSNE/legadoC)'s paragraph-comment snapshot system, cache download coordinator, bookshelf collections with homepage modules, and inline illustrations with audio blocks, plus interaction and tooling features from [Legado_Max](https://github.com/Suml-1/Legado_Max) — while deepening typesetting, the appearance system and data safety in its own direction.

## The 2.0 appearance system

2.0 gathers appearance settings that used to be scattered across theme, top bar, bottom bar, glass and corner entry points into a single **Appearance Center**. Appearance is no longer a set of unrelated switches: it is organized as **packages** that can be created, cloned, imported and exported, mounted into day/night slots. Switching between day and night switches the atmosphere with the slot, while geometry stays global.

| Package | Contents | Mounting |
| --- | --- | --- |
| **Art resource package** | 12-item theme palette; wallpapers for the main screen, top bar, bottom bar, sidebar, book details, panels and welcome screen (7 slots); body fonts; top/bottom bar icons; cover collection | Day / night slots |
| **Parameter package** | Atmosphere: material mode, glass strength, blur/tint/shell/stroke scales, surface and dialog opacity, wallpaper blur. Geometry: global corner scale, card shadow, spatial density and per-region overrides | Atmosphere follows day/night; geometry is global |
| **Bookshelf cover package** | Bookshelf cover collection, apply mode (random/sequence/mixed/fallback only), day/night default covers and group covers | Day / night slots |

- **Four material modes**: solid, clear, frosted and liquid glass. Every host shares one material parameter table; the top bar, bottom bar, bookshelf cards and dialogs can override their own mode independently, without affecting page-level material.
- **Token compiler**: colours, corners, opacity, shadows and spatial density are derived per scope in cascading order, and shared by both the Compose and XML rendering paths so the same look is not re-implemented per host.
- **Package management**: create, clone, import, export, rename and delete. Cloning is copy-on-write and stores only deltas; export materializes a self-contained ZIP; duplicate names are rejected and identical content is deduplicated by hash; deleting a base package still referenced by others materializes its dependants first, so nothing breaks.
- **Live preview and drafts**: the Appearance Center includes a preview card with quick sliders for corners, opacity and glass strength; edits stay in a draft and are not written back to the active configuration until confirmed.
- **E-ink as an independent axis**: e-ink is a standalone rendering-degradation switch, orthogonal to follow-system / day / night, instead of occupying one theme mode.
- **No day/night cross-contamination**: day and night snapshots carry `isNight` explicitly, so capture, apply and restore all write to the matching slot.

## Features

| Area | Highlights |
| --- | --- |
| Reading | Book-source search, explore and source switching; local TXT/EPUB; table of contents and chapter caching; bookmarks, progress, reading records and goals; paragraph comments |
| Bookshelf | List/grid layouts, groups, tags and smart groups, batch management, immersive details, bookshelf cover packages |
| Home and explore | Modular home page, rankings, bookshelf collections, custom explore modules and suites |
| Typesetting | Native and EPUB engines, page/loading templates, header/footer templates, horizontal and vertical text, page-turn animations, inline illustrations and audio blocks |
| Highlights and rules | `.red` highlights, image pattern backgrounds, replacement/purify rules, page HTML/CSS/JavaScript, regex test runs |
| Appearance | Appearance Center, art/parameter/cover packages, four material modes, four-region overrides, day/night slots, e-ink, advanced-title Lottie, bubble packs |
| Read-aloud and media | System and network TTS, multi-role reading with AI-selected music, floating read-aloud ball, comics and video, playback caching |
| AI | AI assistant with tool calling, MCP servers, Tavily web search, image generation and gallery; outbound-content consent, encrypted keys, rate limiting and usage stats |
| Automation and data | Scheduled tasks, cache management and export, backup/restore (sources, bookshelf, settings, assets), WebDAV, S3, rule recycle bin, delete undo |
| Network and remote | DNS/DoH, per-feature routing, domain exceptions and benchmarking, LAN web service, optional public web access (Cloudflare Worker relay) |

[Detailed feature notes (written during the Reading Archive era; the native/EPUB typesetting comparison still applies)](docs/features.md) · [Web and Content Provider API](api.md)

## Other highlights

- **"APIs used by this source" scan**: one tap in the book-source editor lists every API used by the rules (`java.xxx`, `cookie.xxx`, `ajax(` and more), grouped by API with locations and counts, plus search, jump-to-field and copy.
- **Bookshelf tag management**: the management page shows how many books each tag marks, with tag-bar visibility, global rename, batch removal, and automatic cleanup of member-book references on deletion.
- **Header/footer templates**: all six slots accept placeholders (`{书名}` book, `{章节名}` chapter, `{时间}` time, `{电量}` battery, `{页码}` page, `{总页数}` pages, `{进度}` progress, etc.) in any combination.
- **Built-in reading templates and asset library**: Minecraft, Asuka, Lord of the Mysteries, Doraemon, magazine and classic vertical templates work out of the box and can be copied and edited in HTML/CSS/JavaScript; images and fonts live in a shared library used by both highlight rules and page templates.
- **Selection magnifier and fast scroller**: a custom magnifier appears while long-pressing to select text for more precise cross-page selection; bookshelf and table-of-contents lists get a fast scroller for long lists.
- **Regex test runs and debug logs**: replacement rules can be test-run first; debug logs are grouped by app, network, rules, book sources, RSS, Toast, validation, crashes and reader, with filtering, stack details, export and a debug floating ball.
- **Rule recycle bin and delete undo**: seven rule types are binned before deletion and kept for 7 days by default; deleting a book source offers an undo bar, and undoing also clears the matching recycle-bin copy.
- **Empty-state guidance and background readability**: an empty bookshelf guides you to import sources or add local books; with a custom background image, text across the app gets automatic shadow and translucent backing so busy artwork stays readable.
- **Haptic feedback**: long-press and similar actions go through one component-level haptics entry, so feedback is consistent across pages.

## Download

The latest version is **2.0.1**. Get APKs from [GitHub Releases](https://github.com/helyel233/legado-H/releases) (tag prefix `H-`); the in-app "Me → About → Check for updates" entry points to the same page.

- **Package names**: release `io.legado.app.LegadoH`, debug `io.legado.app.debug`. It can be installed **side by side** with the old "Reading Archive" (`io.legado.app.Archive`), with fully independent data.
- **APKs**: the release build targets arm64-v8a (LegadoH signature); every Release also ships a full-ABI debug APK (debug signature, including armeabi-v7a) for non-arm64 devices.
- **Migration**: sources, bookshelf and reading settings can be restored from an old WebDAV or local backup. New backups also cover appearance packages, fonts and covers, with post-restore reconciliation to avoid dangling references.

## Building from source

JDK 17 and Android SDK 36 are required (`minSdk 21`, `targetSdk 36`).

```bash
git clone https://github.com/helyel233/legado-H.git
cd legado-H
./gradlew :app:assembleAppDebug        # Debug APK, package io.legado.app.debug
./gradlew :app:testAppDebugUnitTest    # JVM unit tests
```

- Build variants are `appDebug` / `appRelease` (flavor `app`).
- The release signing key is kept locally by the maintainer (`.legadoh/keystore.properties`, not committed). Without `RELEASE_STORE_FILE` and related properties, the release variant must be signed before installation.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/` | The Android app: reader, bookshelf, Appearance Center, AI, backups, web service and settings |
| `modules/book/` | UMD and EPUB parsing libraries (`me.ag2s.umdlib` / `me.ag2s.epublib`) |
| `modules/rhino/` | Rhino wrapper used by JavaScript rules in book sources |
| `baselineprofile/` | Baseline Profile generator for cold-start and scrolling paths |
| `tools/` | Static gates, the architecture guide and reader-template test scripts |
| `docs/` | Feature, design, release and development documentation |

## Development and gates

When picking up the codebase, run `python3 tools/architecture_guide.py` first: it generates a guide from the live source (top-level hubs, policy classes, terminology mapping, implicit-contract anchors) without writing a file, so it cannot go stale.

- [Development playbook](docs/development-playbook.md) · [Change safety net](docs/change-safety.md) · [Release process](release-process.md)
- [UI architecture v4 and the package system](docs/ui-architecture-v4-plan.md) · [Glass material adoption plan](docs/glass-appwide-adoption-plan.md) · [EPUB direct engine](docs/epub-direct-engine.md)
- [Page templates](docs/reader-templates.md) · [Network and DNS](docs/doh-network.md) · [Visual resource packages](docs/visual-resource-packages.md) · [Paragraph rules and bubble-pack import](docs/online-package-import.md) · [Public web relay](docs/public-web-relay.md)

On pushes to `main` and on pull requests, CI ([`verify.yml`](.github/workflows/verify.yml)) compiles the app, runs JVM unit tests, and runs static gates for settings mapping, resource localisation, glass bit-operation governance, day/night key pairing and the UI layout-token baseline. Lint currently reports only and does not block builds.

## Disclaimer

- This project is for learning and communication only; please delete it within 24 hours of downloading.
- The project bundles, stores and distributes no book content; all online content comes from third-party sources configured by the user.
- Users are responsible for any direct or indirect consequences of using this project. Please support authors and respect copyright.

## Credits

Thanks to [gedoor/legado](https://github.com/gedoor/legado), [Luoyacheng/legado](https://github.com/Luoyacheng/legado) and their contributors; thanks to [Rimchars/legado](https://github.com/Rimchars/legado) ("Reading Archive") — this project is built directly on its codebase; thanks to [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) for the community improvements LegadoH builds on. Thanks to Mingyue for the scheduled-task contribution.

The following features are ported from community forks, with special thanks:

- [CCSSNE/legadoC](https://github.com/CCSSNE/legadoC) ("Reading Color"): the paragraph-comment snapshot and offline-comment system, the cache download coordinator, bookshelf collections with homepage modules, and inline illustrations with audio blocks are ported from it, with source notes kept in the code.
- [skxingyu/legado-sk](https://github.com/skxingyu/legado-sk) ("SK"): automatic backup on shelf changes, restore-overwrites-shelf option, and the current-source hint in the change-source dialog.
- [joestar817/legado_NG](https://github.com/joestar817/legado_NG) ("NG"): per-book independent reading presets.
- [GEd520/legados](https://github.com/GEd520/legados) ("Cichen"): the debug floating ball and the WebViewPool use-and-destroy scope isolation.

Rhino, Jsoup, OkHttp, Glide, Miuix, Paged.js, Lottie, Media3 and other open-source components are used by this project; each keeps its own license. See the [third-party license notices](app/src/main/assets/LICENSE.md).

[Changelog](CHANGELOG.md) · [History notes](docs/changelog/2026-07.md) · [Upstream changelog](docs/changelog/upstream-2022.md)

## License

LegadoH is open source under the [GPL-3.0](LICENSE) license.

Copyright © 2026 LegadoH contributors
