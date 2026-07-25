# tools

Manual, run-by-hand utilities. Nothing here is part of a source set, and nothing
here runs during a build — these are single-file programs launched with the JDK's
source launcher.

## font/GenerateGlyphWidths.java

Derives Minecraft glyph advances from the vanilla bitmap font sheets and writes
`src/main/resources/thunderlbs/font/vanilla-widths.txt`, the table
`GlyphWidthTable` loads at runtime to measure text for `<pad:...>`.

```
java tools/font/GenerateGlyphWidths.java \
    --assets "D:\Server Development\MCSymbols" \
    --minecraft 26.2 \
    --out src/main/resources/thunderlbs/font/vanilla-widths.txt
```

`--assets` accepts either the in-jar layout (`assets/minecraft/font/...`,
`assets/minecraft/textures/font/...`) or a flattened extract (`font/...`,
`textures/...`).

### Provenance

The committed table was generated from `D:\Server Development\MCSymbols`, whose
`ascii.png`, `nonlatin_european.png`, `accented.png` and
`font/include/default.json` are byte-identical to those in
`minecraft-26.2-client.jar`. Those assets are **not** in this repository and
must never become a build or CI dependency: regenerating is a deliberate,
occasional act.

### How an advance is derived

Per `bitmap` provider in `font/include/default.json`:

```
cellW = sheetWidth / maxCodepointsPerRow
cellH = sheetHeight / rowCount
scale = declaredHeight / cellH          (declaredHeight defaults to 8)
ink   = rightmost cell column with any non-transparent pixel, + 1
adv   = ink == 0 ? 0 : (int) (0.5 + ink * scale) + 1
```

Left bearing is ignored, exactly as the client ignores it. `font/include/space.json`
then overrides `U+0020` and `U+200C`, because `font/default.json` references it
before `include/default.json` and vanilla keeps the first usable glyph.

The generator **self-checks against 36 known vanilla advances and refuses to
write** if any disagrees, so a wrong asset directory or a changed sheet layout
fails loudly rather than producing a table that is subtly wrong everywhere.

### After regenerating

Run `./gradlew test`. `GlyphWidthTableTest` asserts the same known advances
independently of the assets, so it catches a truncated or corrupted table. Review
the diff too — the file is plain text specifically so that glyph drift between
Minecraft versions is visible in review.

ThunderLBs supports Minecraft 1.19.4 through 1.21.8. ASCII advances have been
unchanged for over a decade; the non-Latin sheets occasionally gain characters.
Generating from the newest available assets is intentional: a character that a
1.19.4 client cannot render is not one a server operator can put in a format
string anyway. `formatting.padding.glyph-widths` is the escape hatch for
anything measured wrongly.
