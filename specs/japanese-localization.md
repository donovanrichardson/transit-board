# Spec: Japanese Localization Toggle

**Status:** Approved — ready for implementation
**Date:** 2026-06-27

---

## Overview

Add a language toggle (EN / 日本語) to the LIRR Departure Board that switches station names, headsigns, destination labels, and the date display into Japanese. The toggle persists in `localStorage`. Localization data is sourced from an existing CSV (`logs/lirr_japanese_stations.csv`) and bundled into the app as a JSON object at build time.

---

## Scope

### Localized in Japanese mode

| Surface | Display style |
|---|---|
| HomePage station list — each station link | Japanese only (e.g. ロンコンコマ駅) |
| Timetable header — stop name (`h1`) | Dual: Japanese primary, English smaller below |
| Timetable header — direction label ("to …") | Dual: Japanese primary, English smaller below |
| Page title "LIRR Departure Board" | "LIRR 発車時刻表" |
| Header — "Trips toward" label | "駅方面" |
| Header — headsign pills | Japanese name, 駅 suffix stripped (e.g. ロンコンコマ) |
| DestinationPicker — dropdown items | Japanese only |
| DestinationPicker — "Inbound" / "Outbound" buttons | Translated: 都心方面 / 郊外方面 |
| "(Bus)" suffix on headsigns | Translated: (バス) |
| Date display (`.print-date` in App.svelte) | `ja-JP` locale via `Intl.DateTimeFormat` |

### NOT localized (stay English always)

- Headsign abbreviations in MinuteCell (NYP, RON, ATL, etc.)
- UI chrome: the date picker input, loading/error messages, the clock toggle (12h/24h)
- Stop IDs and route IDs
- Any stop whose `oba_stop_id` is absent from the lookup table (silent English fallback — no error, no indicator)

---

## Data layer

### Source file

`logs/lirr_japanese_stations.csv` — 128 rows (plus 1 GAP row for Belmont Park, excluded). Columns: `japanese_name, english_name_wiki, oba_stop_name, oba_stop_id, lat, lon, match_type, notes`.

The key columns for the lookup are `oba_stop_name` (used to match headsigns) and `japanese_name`.

### Build script

Create `frontend/scripts/csv-to-json.js` — a Node.js script that reads `../logs/lirr_japanese_stations.csv` and writes `frontend/src/lib/lirr-ja.json`.

Script behavior:
1. Read and parse the CSV (no external dependencies — use Node built-ins).
2. Skip the header row and any row where `match_type === "GAP"`.
3. Build two lookup objects and write them as a single JSON file:

```json
{
  "byStopId": {
    "LI_1": "アルバートソン駅",
    "LI_4": "アマガンセット駅"
  },
  "byStopName": {
    "Albertson": "アルバートソン駅",
    "Amagansett": "アマガンセット駅",
    "Penn Station": "ペン・ステーション駅"
  }
}
```

- `byStopId` keys on `oba_stop_id` (e.g. `"LI_1"`).
- `byStopName` keys on `oba_stop_name` (e.g. `"Ronkonkoma"`). This is used to look up headsigns, which are stop names but lack a stop ID.
- Both maps store the full `japanese_name` value from the CSV (already includes 駅 suffix where applicable).
- Rows where `oba_stop_id` is empty (such as the GAP row) are excluded.

### Build step integration

Add to `frontend/package.json` scripts:
```json
"gen-ja": "node scripts/csv-to-json.js",
"build": "npm run gen-ja && vite build"
```

The generated file `frontend/src/lib/lirr-ja.json` is committed to the repo after initial generation and regenerated automatically on every build.

### Locale module

Create `frontend/src/lib/locale.js`:

```js
import jaData from './lirr-ja.json';

/**
 * Look up the Japanese name for a stop by its OBA stop ID.
 * Returns the Japanese name, or the English fallback if not found.
 */
export function jaStopName(stopId, fallback) {
  return jaData.byStopId[stopId] ?? fallback;
}

/**
 * Look up the Japanese name for a headsign/destination by its OBA stop name.
 * Returns the Japanese name, or the English fallback if not found.
 * Also translates the "(Bus)" suffix to "(バス)".
 */
export function jaHeadsign(stopName, fallback) {
  if (!stopName) return fallback;
  const busSuffix = stopName.endsWith(' (Bus)');
  const base = busSuffix ? stopName.slice(0, -6) : stopName;
  const ja = jaData.byStopName[base];
  if (!ja) return fallback;
  return busSuffix ? `${ja} (バス)` : ja;
}
```

---

## Language toggle

### Placement

Right side of the header/nav area in `App.svelte`, on the same line as `ClockToggle`. Specifically: after `<ClockToggle>` and before `<DestinationPicker>` in the DOM, floated or flex-pushed to the right side of the control row.

### Markup

```html
<div class="lang-toggle">
  <button class:active={lang === 'en'} on:click={() => setLang('en')}>EN</button>
  <button class:active={lang === 'ja'} on:click={() => setLang('ja')}>日本語</button>
</div>
```

Style to match `ClockToggle`: same pill/button group appearance, `0039a6` active color.

### State management

- `lang` is a module-level reactive variable in `App.svelte`, initialized from `localStorage.getItem('transitBoard.lang')` — default `'en'`.
- On change, write to `localStorage.setItem('transitBoard.lang', lang)`.
- Pass `lang` as a prop down to every component that needs it: `HomePage`, `Header`, `DestinationPicker`.
- The toggle appears on both the home page and the stop/timetable page.

### Behavior on toggle

- Switch is immediate — no loading state, no page reload.
- All names already in the DOM rerender via reactive Svelte props.
- `filteredStops` in `HomePage` must re-evaluate when `lang` changes to ensure search continues to work (see AC-12).

---

## Component-by-component changes

### `frontend/scripts/csv-to-json.js` (new file)

Build script. Reads `../../logs/lirr_japanese_stations.csv` relative to its own location, outputs `../src/lib/lirr-ja.json`. See Data layer section above.

### `frontend/src/lib/lirr-ja.json` (generated file)

Output of the build script. Committed to the repo. Do not edit by hand.

### `frontend/src/lib/locale.js` (new file)

Exports `jaStopName(stopId, fallback)` and `jaHeadsign(stopName, fallback)`. See Data layer section above.

### `frontend/src/App.svelte`

1. Add `lang` state variable initialized from `localStorage`, default `'en'`.
2. Add `setLang(l)` function that sets `lang` and persists to `localStorage`.
3. Add `<LangToggle {lang} on:change={onLangChange} />` component (or inline buttons — either is fine).
4. Pass `lang` as a prop to `<HomePage>`, `<Header>`, and `<DestinationPicker>`.
5. Update the `print-date` block to use `ja-JP` locale when `lang === 'ja'`:
   ```js
   new Intl.DateTimeFormat(lang === 'ja' ? 'ja-JP' : 'en-US', { ... }).format(...)
   ```

### `frontend/src/components/HomePage.svelte`

1. Accept `export let lang = 'en'`.
2. Import `jaStopName` from `locale.js`.
3. Display name: `lang === 'ja' ? jaStopName(stop.id, stop.name) : stop.name`.
4. Search (`filteredStops`) must match on both English and Japanese names when `lang === 'ja'` so typing either script finds the stop:
   ```js
   $: filteredStops = stops.filter((s) => {
     const jaName = jaStopName(s.id, s.name);
     const query = searchText.toLowerCase();
     return s.name.toLowerCase().includes(query) || jaName.includes(query);
   });
   ```
   (Japanese text matching is case-insensitive by nature; `.toLowerCase()` on query is sufficient.)

### `frontend/src/components/Header.svelte`

1. Accept `export let lang = 'en'`.
2. Import `jaStopName` and `jaHeadsign` from `locale.js`.
3. **Stop name (`h1`)** — when `lang === 'ja'` and `stop` has a match:
   - Render Japanese name in the existing `h1` at existing font size.
   - Render English name (`stop.name`) in a `<span class="stop-name-en">` immediately below, at `0.8rem`, `#666`, `font-weight: 400`.
   - When no Japanese match exists (`jaStopName` returns the English fallback), render only the English name with no `stop-name-en` span.
4. **Direction label** (`directionLabelText`) — when `lang === 'ja'` and mode is `specific`:
   - Replace "to X" with `to ${jaHeadsign(lirrSelectedHeadsign, lirrSelectedHeadsign)}` as the primary text.
   - Add English name in smaller text below (same dual treatment as stop name).
   - When mode is `inbound` or `outbound`, direction label stays English ("Inbound" / "Outbound") — these are not localized.
5. **Page title** — in `Header.svelte` and App.svelte's `board-title`, render "LIRR 発車時刻表" when `lang === 'ja'`, "LIRR Departure Board" otherwise.
6. **Headsign pills** — when `lang === 'ja'`:
   - Render the "Trips toward" label as "駅方面".
   - Render the Japanese name inside each pill with the 駅 suffix stripped (e.g. ロンコンコマ, not ロンコンコマ駅). Helper: `jaHeadsign(headsign, headsign).replace(/駅$/, '')`. No English text in the pill.

### `frontend/src/components/DestinationPicker.svelte`

1. Accept `export let lang = 'en'`.
2. Import `jaHeadsign` from `locale.js`.
3. **Dropdown items**: render `lang === 'ja' ? jaHeadsign(h, h) : h` in each `.dropdown-item` button. Japanese only.
4. **Direction buttons**: when `lang === 'ja'`, render "都心方面" for inbound and "郊外方面" for outbound. English otherwise.
5. **Search matching**: when `lang === 'ja'`, `filteredHeadsigns` must match on both English and Japanese:
   ```js
   $: filteredHeadsigns = displayList.filter((h) => {
     const jaName = lang === 'ja' ? jaHeadsign(h, h) : null;
     const query = searchText.toLowerCase();
     return h.toLowerCase().includes(query) || (jaName && jaName.includes(query));
   });
   ```

### `frontend/src/lib/lirr.js`

No functional changes required. The `HEADSIGN_ABBREVIATIONS` map keys stay as English strings (they are looked up by English headsign at all times).

### `frontend/src/components/Timetable.svelte`

No changes. Timetable cells display departure times and headsign abbreviations (e.g. RON, NYP) — abbreviations are not localized per spec.

---

## Acceptance criteria

### Toggle behavior

1. A language toggle with "EN" and "日本語" buttons is visible on both the home page and the stop timetable page, right-aligned in the header control row.
2. Clicking "日本語" switches the UI to Japanese mode; clicking "EN" switches back. The active button is visually highlighted (matching ClockToggle style).
3. The language preference is persisted in `localStorage` as `transitBoard.lang`. Reloading the page restores the last-selected language.
4. The toggle works independently of `clockMode` — switching language does not reset clock mode and vice versa.

### HomePage

5. In Japanese mode, each station link in the station list displays the Japanese name (e.g. ロンコンコマ駅) with no English name shown.
6. In English mode, station links display the English name as before.
7. Typing in the search box in Japanese mode matches stations by both their English name and their Japanese name (e.g. typing "ロン" surfaces Ronkonkoma; typing "ron" also surfaces it).
8. A station with no Japanese translation (if any exist) silently falls back to its English name in Japanese mode; no error or empty string is shown.

### Timetable header — stop name

9. In Japanese mode, the stop name `h1` shows the Japanese name at normal weight/size (e.g. ロンコンコマ駅).
10. A smaller English name appears immediately below (e.g. "Ronkonkoma") in `0.8rem` muted text.
11. If the stop has no Japanese translation, only the English name is rendered (no empty secondary line).

### Timetable header — direction label

12. When a specific destination is selected and `lang === 'ja'`, the direction label ("to X") shows the Japanese destination name as the primary text with the English name in smaller text below.
13. When mode is inbound or outbound, the direction label stays "Inbound" / "Outbound" in both language modes.

### Page title

14. In Japanese mode, the page title reads "LIRR 発車時刻表" on both the home page and the timetable page. In English mode it reads "LIRR Departure Board" as before.

### Headsign pills

15. In Japanese mode, the "Trips toward" label reads "駅方面".
16. In Japanese mode, each headsign pill displays the Japanese name with the 駅 suffix stripped (e.g. ロンコンコマ). Exception: Penn Station (ペンシルベニア駅) and Grand Central (グランド・セントラル駅) retain their 駅 suffix. No 方面, no English text in the pill.
17. In English mode, headsign pills and the "Trips toward" label display as before.
18. A headsign with no Japanese translation silently falls back to its English name.

### DestinationPicker

19. In Japanese mode, the direction buttons show "都心方面" (inbound) and "郊外方面" (outbound).
20. In Japanese mode, dropdown items display only the Japanese destination name.
21. Typing a Japanese string in the search box matches Japanese destination names; typing an English string also matches (bilingual search).
22. A destination with no Japanese translation silently falls back to its English name in the dropdown.

### Bus suffix

23. In Japanese mode, headsigns ending in " (Bus)" are rendered with "(バス)" instead (e.g. "ポート・ジェファーソン駅 (バス)").

### Date display

24. In Japanese mode, the `.print-date` line renders in `ja-JP` locale format (e.g. "2026年6月27日土曜日").
25. In English mode, the date renders in `en-US` format as before.

### Data layer

26. `frontend/scripts/csv-to-json.js` can be run with `node scripts/csv-to-json.js` from `frontend/` and produces `src/lib/lirr-ja.json` with `byStopId` and `byStopName` objects.
27. The GAP row (Belmont Park, no `oba_stop_id`) is excluded from both maps.
28. `npm run build` runs `gen-ja` first; the build succeeds with the generated JSON imported.
29. `locale.js` unit tests: `jaStopName` returns Japanese for a known stop ID, returns the fallback for an unknown ID. `jaHeadsign` returns Japanese for a known stop name, handles the `(Bus)` suffix, returns the fallback for an unknown name.

---

## Open questions

None — all design decisions are resolved.

---

## Files created or modified

| File | Change |
|---|---|
| `frontend/scripts/csv-to-json.js` | New — build script |
| `frontend/src/lib/lirr-ja.json` | New — generated; committed |
| `frontend/src/lib/locale.js` | New — lookup helpers |
| `frontend/src/lib/__tests__/locale.test.js` | New — unit tests for locale.js |
| `frontend/src/App.svelte` | Add lang state, toggle, prop threading, date locale |
| `frontend/src/components/HomePage.svelte` | Render Japanese names, bilingual search |
| `frontend/src/components/Header.svelte` | Dual stop name, dual direction label, Japanese pills |
| `frontend/src/components/DestinationPicker.svelte` | Japanese items, bilingual search, 上り/下り |
| `frontend/package.json` | Add `gen-ja` script; update `build` script |
