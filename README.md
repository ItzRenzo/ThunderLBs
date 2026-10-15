# ThunderLBs
Better docs soon

## Mixed player and civilization pages

A board can rotate PLAYER and GROUP pages at the same position using its existing
page animations. Each page's optional `type` overrides the board-level `type`;
omitting it keeps the board's type, including on existing GROUP boards.
`formatting.row` and `formatting.relative` can each be overridden per page.
Omitted patterns inherit the board's formatting, which inherits global defaults.
Position, yaw, settings, animations, and page timing remain board-level options.

Select your existing custom provider in `config.yml`:

```yaml
provider: unstablepvpffa
```

In the board's file, keep its existing position, settings, and animation sections,
and configure the pages as follows:

```yaml
type: PLAYER
pages:
  page-1:
    type: PLAYER
    holder: coins
    title: COINS
    color: '#FACC15'
    icon: <glyph:coins_icon>
    value-format: SHORT_NUMBER
    interval: alltime

  page-2:
    type: GROUP
    holder: coins
    title: CIV COINS
    color: '#FACC15'
    icon: <glyph:coins_icon>
    value-format: SHORT_NUMBER
    interval: alltime
    formatting:
      row: '<color:%color%>%position%</color> <white>%player%</white> <pad:130:.:right> <color:%color%>%value%</color>'
      relative: '<color:%color%>%position%</color> <white>%player%</white> <pad:130:.:right> <color:%color%>%value%</color>'
```

The `unstablepvpffa` section in `providers.yml` must contain its actual
PlaceholderAPI patterns for these fields:

| Page type | Top rows | Viewer-relative line | `%player%` |
| --- | --- | --- | --- |
| PLAYER | `name`, `value` | `viewer-rank`, `viewer-value` | Player name |
| GROUP | `group-name`, `group-value` | `group-viewer-rank`, `group-viewer-value` | Civilization name; the relative line uses `group-team` |

Both page types can use `holder: coins`: provider cache keys include the effective
page type as well as holder and interval. PLAYER row formats resolve additional
PlaceholderAPI placeholders against that row's player, so player-head placeholders
from your installed expansion/resource pack keep working. GROUP row formats have
no player context and never look up civilization names as player accounts. Use a
GROUP format without player-head placeholders, as above. Relative-format
PlaceholderAPI placeholders still resolve against the viewer; a missing group-team
name displays `missing-text`.

The holder dialog includes page type and row/relative format overrides. Empty
format fields inherit existing formatting. Commands are also available:

```text
/lb edit <id> holder type <index> <PLAYER|GROUP|inherit>
/lb edit <id> holder row <index> <pattern...|inherit>
/lb edit <id> holder relative <index> <pattern...|inherit>
```

Existing holder `set`, interval changes, reordering, saving, and reloading preserve
page options. `/lb validate` checks page formats and accounts for them when
suggesting a line width.

## PlaceholderAPI in formats

Any PlaceholderAPI placeholder can be written straight into a format, and it
resolves against the player that line is about:

```yaml
formatting:
  row: "<color:%color%>%position%</color> <white>%vault_prefix%%player%</white> <dark_gray>→</dark_gray> <color:%color%>%value%</color>"
```

```
①. [Owner] kittycatgirlxoxo → 1
②. [VIP] Makeinen545        → 1
```

| Format | Resolves against |
| --- | --- |
| `formatting.row` | the player on that row — nobody on a GROUP page or an empty slot |
| `formatting.relative` | the viewer reading the line |
| `formatting.title`, `bar.background`, `bar.foreground` | nobody, so use server-wide placeholders such as `%server_online%` |

All of these can also be set per board in its own file, and placeholders work
there the same way.

ThunderLBs' own tokens — `%color%`, `%position%`, `%player%`, `%value%`,
`%icon%`, `%title%`, `%rank%`, `%holder%` — are never touched by
PlaceholderAPI, which only expands identifiers containing an underscore. Nothing
needs escaping, and a placeholder is expanded *before* the tokens are
substituted, so a value that happens to look like `%server_online%` is drawn
rather than expanded.

Three details worth knowing:

- **Padding still lines up.** Placeholders resolve before anything is measured,
  so a `<pad:...>` column is computed from `[VIP] Makeinen545` and not from
  `%vault_prefix%%player%`.
- **Legacy codes are converted.** Most expansions return `&7[Member]` or
  `§c§lADMIN` rather than MiniMessage; ThunderLBs rewrites those to tags, so they
  are drawn as formatting instead of as literal `&7` characters, and measured as
  formatting too. Each converted run closes its own tags, so a prefix's colour
  cannot bleed into the rest of the row. A placeholder that already returns
  MiniMessage is passed through untouched.
- **Bold counts, italic does not.** Bold widens every glyph by one pixel in
  Minecraft's font and is included in the width; italic shears glyphs in place
  without advancing the cursor, so it adds nothing — which is also how the client
  draws it.

Without PlaceholderAPI installed, formats behave exactly as before: nothing is
scanned and nothing is replaced. If a placeholder shows up on the board
verbatim, its expansion is not installed.

## Column padding

Rows are ragged by default — `1. Bob x50` and `2. Alexander x9` share no column.
Put a `<pad:...>` tag in any format and ThunderLBs measures the visible text
before it in Minecraft font pixels, then fills up to a column with a character of
your choice:

```yaml
formatting:
  row: "<color:%color%>%position%.</color> <white>%player%</white><pad:150:.><color:%color%>%value%</color>"
```

```
1. Makeinen545..............x50 (MAX)
2. Catspire.................x50 (MAX)
3. Bo.......................x49
```

### Syntax

`<pad[:TARGET[:FILLER[:MODE[:STYLE]]]]>` — every argument is optional and falls
back to the matching key under `formatting.padding` in `config.yml`.

| Argument | Meaning | Default |
| --- | --- | --- |
| `TARGET` | column in pixels (1–4096), or `+N` for N pixels past the current cursor | `150` |
| `FILLER` | the repeated characters; quote it with `'…'` if it contains `:` `<` `>` or spaces | `.` |
| `MODE` | `fill` — text after the tag *starts* at the column; `right` — text after the tag *ends* at it | `fill` |
| `STYLE` | a colour name, `#RRGGBB`, a gradient, or `%color%` for the page's holder colour | `dark_gray` |

Examples:

```yaml
# Dots up to 150 px, then the value.
row: "<white>%player%</white><pad:150:.>%value%"
# Spaces instead, with the value's right edge flush at 180 px.
row: "<white>%player%</white><pad:180:' ':right>%value%"
# 20 px of gap in the page's own colour, wherever the name happens to end.
row: "<white>%player%</white><pad:+20:' ':fill:%color%>%value%"
```

Write `\<pad:150>` for a literal tag. Text already past `TARGET` is left alone:
no filler is inserted and nothing is ever truncated, so a long name simply
pushes its value to the right instead of being cut off.

### Two things to know

1. **`render.line-width` has to clear your column.** Text past it wraps onto a
   second line, and a `<pad:150:…>` column is only where the value *starts* — the
   line needs 150 px plus whatever follows. The default is 250 px, which fits the
   default 150 px column; raise both together if you move the column out. Set it
   in `config.yml`, or per board with `/lb edit <id> linewidth <pixels>` — tab
   completion offers the width that board actually needs. `/lb validate <id>`
   warns when a format outgrows it and names the number to use, and `/lb doctor`
   prints the fit for every board.
2. **Rows get widened to a common width for you.** A text display always centres
   its text on the hologram's anchor, so two rows of different total width begin
   at different x and a column that is exact *within* each row still looks ragged
   on screen. ThunderLBs therefore pads every row of a page out to the width of
   the widest one using invisible trailing spaces, which makes the centring
   cancel out. `render.alignment` cannot substitute for this — it only arranges
   lines *within* one display, and each row is a single line, so left, center and
   right all render identically for rows.

   ```yaml
   formatting:
     padding:
       # auto (widest row on the page), a fixed pixel width, or off
       row-width: auto
   ```

   `auto` needs no tuning but shifts the whole board slightly whenever the widest
   row changes; a fixed width never shifts, so pick one past your longest row if
   you want the board nailed down. `auto` only applies when the row format really
   contains a `<pad:...>` tag, so boards without one are untouched.

### Custom glyphs

Resource-pack characters have no width ThunderLBs can know, so it assumes
`formatting.padding.fallback-advance` (8 px) and `/lb validate` says the column
may be off. Give it the real advances:

```yaml
formatting:
  padding:
    glyph-widths:
      "U+E001": 12
      "❤": 9
```

Reload afterwards. Board scale does not matter — targets are font pixels, applied
before the display transform, so scaling a board keeps its columns aligned.

## License

Copyright (C) 2026 thunderredstar

ThunderLBs is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version (`GPL-3.0-or-later`). See [LICENSE.md](LICENSE.md) for the complete
GPLv3 license terms.
