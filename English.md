# LegadoH

[English](English.md) · [中文](README.md)

<p align="center"><img width="128" height="128" src="docs/legadoh_icon.svg" alt="LegadoH"></p>

<p align="center"><b>LegadoH</b> — a customizable Android reader for your own content sources, deeply built on <a href="https://github.com/gedoor/legado">Legado</a>.</p>

---

## About

LegadoH ships with no books and no book sources. Add your own online sources in the app, or import local TXT and EPUB files. On top of everything Legado already offers — configurable sources, replacement and purify rules, TTS, RSS, the built-in web service — LegadoH focuses on **typesetting quality, theming and reading tools**:

- **Independent EPUB layout engine**: supports image-based pattern highlights and rich CSS effects, with the default "Camellia" loading template and artwork extending behind the status bar in day/night modes.
- **Page template system**: fixed-frame scrolling templates, unified font/color/header/footer management, horizontal and vertical typesetting.
- **Theme system**: built-in Minecraft, Asuka, Lord of the Mysteries and Doraemon reading themes, advanced-title and header/footer Lottie animations, independently adjustable opacity for the top bar, bottom bar and dialogs.
- **AI-assisted reading**: configurable AI services for source search, book and chapter reading, reading-record queries and web-connected tools.
- **Modernized UI**: Compose bookshelf and explore pages, adaptive launcher icon with a monochrome themed layer, frosted-glass top and bottom bars.

Ongoing improvements from sibling forks are also ported in — such as [legadoC](https://github.com/CCSSNE/legadoC)'s review-snapshot and offline-comment system, cache download coordinator, bookshelf collections with homepage modules and inline illustrations with audio blocks, and [Legado_Max](https://github.com/Suml-1/Legado_Max)'s interaction and tooling features.

## Download

Get the latest APK from [GitHub Releases](https://github.com/helyel233/legado-H/releases) (tag prefix `legadoh-`); the in-app "Me → About → Check for updates" entry points to the same Releases page.

- Package name: `io.legado.app.LegadoH`. It can be installed **side by side** with the old "Reading Archive" (`io.legado.app.Archive`).
- The two versions keep data separate; sources, bookshelf and reading settings can be restored in one tap from an old WebDAV/local backup.

## Features

| Area | Highlights |
| --- | --- |
| Reading | Book sources and local books, native and EPUB typesetting, page-turn animations, reading styles, bookmarks and progress |
| Bookshelf | List and grid layouts, groups, smart tags (hit counts / global rename), batch management, immersive details |
| Typesetting | Independent EPUB engine, page/loading templates, custom header/footer templates with placeholders, horizontal and vertical text |
| Highlights & rules | Local `.red` rules, pattern highlights, regex purify rules, page HTML/CSS/JavaScript |
| Assets & themes | Shared image/font library, day/night themes, backgrounds, advanced-title Lottie, bubble packs, continuous font weight 100–900 |
| TTS & media | System and network TTS, text following, floating playback controls, comic and video entries |
| AI | Configurable AI services, source search, book and chapter reading, reading-record queries and web tools |
| Automation & data | Scheduled tasks, caching, backup and restore, WebDAV, object storage (S3) and container management |
| Network | DNS/DoH selection, per-feature routing, domain exceptions, service configuration and speed tests |

[Full feature guide (Chinese)](docs/features.md)

## Custom highlights

- **Source API usage scanner**: a one-tap entry in the book source editor lists every API used by the rules (`java.xxx`, `cookie.xxx`, `ajax(` and more), grouped by API with usage counts, search, jump-to-field and copy-as-list.
- **Smart bookshelf tags**: the tag bar shows hit counts per tag; tags support global rename, and deleting a tag cleans up leftovers from member books.
- **Custom header/footer templates**: each of the six header/footer slots accepts a template built from placeholders (`{书名}` book, `{章节名}` title, `{时间}` time, `{电量}` battery, `{页码}` page, `{总页数}` pages, `{进度}` progress, etc.).
- **Selection magnifier**: a magnifier shows while long-pressing to select text for more precise cross-page selection.
- **Lint baseline gate**: unified lint config with a checked-in baseline file so existing issues no longer block builds while new issues are caught.

## Documentation

- [Page templates](docs/reader-templates.md)
- [Network and DNS](docs/doh-network.md)
- [Visual resource packages (advanced titles, etc.)](docs/visual-resource-packages.md)
- [Paragraph rules and bubble pack import](docs/online-package-import.md)
- [Web and Content Provider API](api.md)
- [Upstream help documentation](https://www.yuque.com/legado/wiki)

## Building from source

```bash
git clone https://github.com/helyel233/legado-H.git
cd legado-H
./gradlew :app:assembleAppDebug        # Debug build
./gradlew :app:testAppDebugUnitTest    # Unit tests
```

JDK 17 and Android SDK 36 are required. The release signing key is kept locally by the maintainer (`.legadoh/`, not committed); without it the release variant falls back to the CI debug certificate.

## Disclaimer

- This project is for learning and communication only; please delete it within 24 hours of downloading.
- The app includes, stores and distributes no book content; all online content comes from third-party sources configured by the user.
- Users are responsible for any consequences of using this project. Please support authors and respect copyright.

## Credits

Thanks to [gedoor/legado](https://github.com/gedoor/legado), [Luoyacheng/legado](https://github.com/Luoyacheng/legado) and their contributors; thanks to [Rimchars/legado](https://github.com/Rimchars/legado) ("Reading Archive") — this project is built directly on its codebase; thanks to [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) for the community improvements LegadoH builds on. Thanks to Mingyue for the scheduled task contribution.

The following features are ported from community forks, with special thanks:

- [CCSSNE/legadoC](https://github.com/CCSSNE/legadoC) ("Reading Color"): the review-snapshot and offline-comment system, the cache download coordinator, bookshelf collections with homepage modules, and inline illustrations with audio blocks are ported from it, with source notes kept in the code.
- [skxingyu/legado-sk](https://github.com/skxingyu/legado-sk) ("SK"): automatic backup on shelf changes, restore-overwrites-shelf option, and the current-source hint in the change-source dialog.
- [joestar817/legado_NG](https://github.com/joestar817/legado_NG) ("NG"): per-book independent reading presets.
- [GEd520/legados](https://github.com/GEd520/legados) ("Cichen"): the debug floating ball, and the WebViewPool use-and-destroy scope isolation.

Rhino, Jsoup, OkHttp, Glide, Miuix, Paged.js and other open-source components are used by this project; each keeps its own license. See the [third-party license notices](app/src/main/assets/LICENSE.md).

[Changelog](CHANGELOG.md) · [History notes](docs/changelog/2026-07.md) · [Upstream changelog](docs/changelog/upstream-2022.md)

## License

LegadoH is open source under the [GPL-3.0](LICENSE) license.

Copyright © 2026 LegadoH contributors
