# Extension formats

Three file types, all JSON, all optional. A broken extension is reported in the log
(tag `rivo`) and skipped — it can never prevent the app from starting.

| Suffix | Schema | Effect |
| --- | --- | --- |
| `*.ui.json` | `rivo.ui/1` | Reorder, filter and extend menus |
| `*.lang.json` | `rivo.lang/1` | Add or override interface strings |
| `*.theme.json` | `rivo.theme/1` | Add a color scheme |

Bundled extensions live in `app/src/main/assets/extensions/{ui,langs,themes}/`. From F7
the same files are also loaded from a user-selected SAF folder, with the user folder taking
precedence over the bundled one.

Files in this directory are examples, not installed extensions: copy them into the assets
tree (or the SAF folder later) to activate them.

---

## `rivo.ui/1` — menu patches

```json
{
  "schema": "rivo.ui/1",
  "menus": {
    "main": {
      "sort": ["search", "feed.home", "3"],
      "filter": "^legacy\\.",
      "dropUnlisted": false,
      "inject": [
        {
          "id": "sites",
          "label": "Sites",
          "icon": "\uE5FF",
          "position": "after:channels",
          "children": [
            { "id": "sites.dm", "label": "Dailymotion", "handler": "app.open_url",
              "payloadUrl": "https://www.dailymotion.com" }
          ]
        }
      ]
    }
  }
}
```

### Menu keys

| Key | Meaning |
| --- | --- |
| `main`, `misc`, `config` | built-in menus |
| `config_choice` | radio-style value picker; takes a `ConfigValue` payload |
| `feed`, `search`, `library`, `playlists`, `channels`, `channel`, `playlist` | data-backed screens (F3) |
| `media_actions`, `channel_actions` | per-item action sheets (F3) |

Unknown menu ids are accepted and simply never resolved until a screen with that id exists.

### `sort`

Ordered list of node ids or 1-based indexes. This is the port of the upstream
`_menu_main "1,2,3,..."` argument: an index refers to the position in the *current* list,
so a mixed list of ids and numbers is legal. Nodes not mentioned keep their relative order
and follow the sorted ones, unless `dropUnlisted` is `true`.

### `filter`

Regular expression tested against each node's `id` and `labelKey`. Matching nodes are
removed. Translated labels are never matched, so a patch behaves identically in every
language.

### `inject`

| Field | Meaning |
| --- | --- |
| `id` | required, unique within the menu |
| `label` / `labelKey` | one is required |
| `icon` / `iconKey` | literal glyph or `.lang` key |
| `detail` | second line |
| `handler` | leaf action id (see below) |
| `targetMenuId` | open a built-in menu instead of running a handler |
| `payloadUrl` | becomes `MenuPayload.Url`, consumed by `app.open_url` / `app.copy_url` / `app.share_url` |
| `position` | `start`, `end` (default), `before:<id>`, `after:<id>`, or a numeric index |
| `keyHint` | right-aligned hint |
| `destructive` | render in the error color |
| `children` | nested nodes; their presence makes this an inline submenu |

### Built-in handler ids

| Id | Action |
| --- | --- |
| `app.open_url` | open the node's URL in a browser |
| `app.copy_url` | copy the URL to the clipboard |
| `app.share_url` | `ACTION_SEND` the URL |
| `app.about` | version/extractor summary |
| `app.back` | pop the frame (exit at root) |
| `app.exit` | finish the activity |
| `config.toggle` | flip the node's `toggleKey` in the config file |
| `config.select` | write `value` for the key in the payload |
| `theme.select` | set `CONFIG_THEME` |
| `lang.select` | set `CONFIG_LANG` |
| `config.open_external` | hand `files/config` to a text editor |

A handler id that does not exist produces the "Action not available yet" message with the
id in it, which is also how third-party handlers behave until the sandbox lands in F7.

---

## `rivo.lang/1` — strings

```json
{
  "schema": "rivo.lang/1",
  "locale": "de",
  "name": "Deutsch",
  "extends": "en",
  "strings": {
    "TXT_MENU_MAIN_SEARCH": "Suchen"
  }
}
```

Keys are the upstream `TXT_*` names. English is always merged underneath, so a partial
translation is valid — anything missing falls back to English, then to the key itself.
Format placeholders use Java's syntax (`%1$s`, `%1$d`). Select the language with
`CONFIG_LANG=de` or the in-app Language menu; `auto` follows the system locale.

`app/src/main/assets/extensions/langs/en.lang.json` is the reference list of every key the
app uses.

---

## `*.theme.json` — color schemes

```json
{
  "id": "gruvbox",
  "name": "Gruvbox",
  "dark": true,
  "colors": {
    "primary": "#fabd2f",
    "onPrimary": "#282828",
    "background": "#1d2021",
    "onBackground": "#ebdbb2",
    "surface": "#282828",
    "onSurface": "#ebdbb2",
    "surfaceVariant": "#3c3836",
    "onSurfaceVariant": "#d5c4a1",
    "outline": "#665c54",
    "error": "#fb4934"
  },
  "terminal": {
    "iconPrimary": "#83a598",
    "iconSecondary": "#d3869b",
    "iconAccent": "#fabd2f",
    "iconError": "#fb4934",
    "previewKey": "#fabd2f",
    "previewValue": "#ebdbb2",
    "previewDivider": "#504945",
    "pointer": "#fe8019"
  }
}
```

`colors` uses Material 3 role names. Unset roles are derived from `background`,
`onBackground`, `surface`, `primary`, `secondary`, `tertiary`, `error` and `outline`, so
the minimal set above (or even just the ten keys in the example) yields a coherent scheme.

`terminal` carries the upstream `THEME_FZF_*` roles: they color the icon column, the
preview pane and the selected-row pointer, exactly as they did in the terminal UI.

Hex accepts `#RGB`, `#RRGGBB` and `#AARRGGBB`; anything else is ignored rather than
rejected, so a typo in one role does not disable the whole theme. Select with
`CONFIG_THEME=gruvbox` or the Theme menu.
