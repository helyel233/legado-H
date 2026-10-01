# Legado-H

[English](English.md) · [中文](README.md)

Legado-H is a fork of Reading Archive. It inherits the deep customizations of the Legado branch maintained by Lyc — the EPUB layout engine, page/loading templates and the theme system — and adopts improvements from the community fork [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max).

The app does not include books or book sources. Add your own sources or import local TXT and EPUB files.

## Downloads

- [GitHub Releases](https://github.com/helyel233/legado-H/releases)

## What this fork adds

On top of the Archive deep-dive features (EPUB layout engine, page/loading templates, theme system), Legado-H adopts improvements from Legado_Max:

- **Source API usage scanner**: a one-tap entry in the book source editor lists every API used by the source rules (`java.xxx`, `cookie.xxx`, `ajax(` and more), grouped by API with usage counts, search, jump-to-field and copy-as-list.
- **Smart bookshelf tags**: tag bar shows hit counts per tag; tags can be renamed globally (updating the `customTag` of all matching books and synced config lists); deleting a tag cleans up leftovers from member books.
- **Custom header/footer templates**: each of the six header/footer slots supports a custom template built from placeholders (`{书名}` book, `{章节名}` title, `{时间}` time, `{电量}` battery, `{电量百分比}` battery %, `{页码}` page, `{总页数}` pages, `{进度}` progress).
- **Lint baseline gate**: unified lint config with a checked-in baseline file so existing issues no longer block builds while new issues are caught.
- **Continuous font weight 100–900**: body text weight is no longer limited to regular/bold and can be fine-tuned (backwards compatible with old configs).
- **Selection magnifier**: a magnifier shows while long-pressing to select text for more precise cross-page selection.

Existing highlights:

- Independent EPUB layout engine and loading templates (Camellia by default) with day/night palettes and artwork extending behind the status bar.
- Page template system: fixed-frame scrolling templates, unified font/color/header/footer management, horizontal and vertical typesetting.
- Theme system: Minecraft, Asuka, Lord of the Mysteries and Doraemon built-in themes, advanced title and header/footer Lottie animations.

## Features

- Read from configurable book sources or local files, with bookmarks, chapter caching and reading progress.
- Organize books with groups, smart tags, batch management and an immersive details page.
- Use native rendering or EPUB rendering for ordinary text, with separate layout settings.
- Import local Reeden `.red` highlight rules, search and edit rules, and select fonts and background images.
- Customize first and continuation pages with HTML, CSS and JavaScript. Scrolling templates keep the frame fixed while the text scrolls.
- Share locally imported images and fonts between highlight rules and page templates.
- Use system or network TTS, text following, floating playback controls, and comic or video entry points.
- Configure AI services and reading tools, scheduled tasks, backups, WebDAV and object storage.
- Configure DNS/DoH providers, routing by feature, domain exceptions and DNS measurements.

## Documentation

- [Full feature guide (Chinese)](docs/features.md)
- [Page templates](docs/reader-templates.md)
- [Network and DNS](docs/doh-network.md)
- [Changelog](CHANGELOG.md)
- [API](api.md)

## Credits

Thanks to [gedoor/legado](https://github.com/gedoor/legado), [Luoyacheng/legado](https://github.com/Luoyacheng/legado) and their contributors; thanks to [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) for the community improvements Legado-H builds on. Thanks to Mingyue for the scheduled task contribution.

See [LICENSE](LICENSE) and the [third-party license notices](app/src/main/assets/LICENSE.md).
