# Spec: Remaining Japanese Localizations

## Translation table

| English string | Japanese translation | Notes |
|---|---|---|
| `No scheduled service for this date.` | `この日は運行がありません。` | |
| `Date` | `日付` | |
| `Search destinations...` | `目的地を検索…` | |
| `No results` | `結果なし` | |
| `At least one destination must be selected.` | `少なくとも1つの行先を選択してください。` | 行先 is standard transit term for headsign |
| `Could not load stations. Try again later.` | `データを読み込めませんでした。後でもう一度お試しください。` | |
| `Could not load schedule. Try again later.` | `時刻表を読み込めませんでした。後でもう一度お試しください。` | |
| `Stop not found.` | `駅が見つかりません。` | |
| `Invalid request.` | `無効なリクエストです。` | |
| `No stop ID specified. Navigate to /stop/<stopId>.` | `停留所IDが指定されていません。/stop/<stopId> にアクセスしてください。` | URL fragment stays in English |
| `Loading…` | `読み込み中…` | |
| `Select your origin station` | `出発駅を選択` | |
| `Search stations...` | `駅を検索…` | Home page search input placeholder |
| `No stations found.` | `駅が見つかりません。` | |
| `Trips toward` (pills label) | `行先` | User-specified; replaces previous 駅方面 |

---

## Per-component changes

### 1. `Timetable.svelte`
- Add `export let lang = 'en';`
- No-service message: `{lang === 'ja' ? 'この日は運行がありません。' : 'No scheduled service for this date.'}`
- Caller `App.svelte`: pass `{lang}` to `<Timetable>`

### 2. `DatePicker.svelte`
- Add `export let lang = 'en';`
- Label: `{lang === 'ja' ? '日付' : 'Date'}`
- Caller `App.svelte`: pass `{lang}` to `<DatePicker>`

### 3. `DestinationPicker.svelte`
- `lang` prop already exists
- Placeholder: `placeholder={lang === 'ja' ? '目的地を検索…' : 'Search destinations...'}`
- Empty dropdown: `{lang === 'ja' ? '結果なし' : 'No results'}`

### 4. `HeadsignFilter.svelte`
- Add `export let lang = 'en';`
- Error: `errorMessage = lang === 'ja' ? '少なくとも1つの行先を選択してください。' : 'At least one destination must be selected.';`
- Caller `App.svelte`: pass `{lang}` to `<HeadsignFilter>`

### 5. `App.svelte` — error strings
- `lang` already in scope
- `loadSchedule` catch: localize all three cases (404, 400, generic)
- `loadHomeStops` catch: localize home error
- Terminal `{:else}` branch: localize no-stop-ID message
- Note: errors stored as strings at assignment time; lang-switch after error fires does not update — acceptable

### 6. `Header.svelte`
- `lang` already exists
- Reactive `stopTitle`: `stop ? ... : (lang === 'ja' ? '読み込み中…' : 'Loading…')`
- Pills label: change `駅方面` → `{lang === 'ja' ? '行先' : 'Trips toward'}`
- **Pill display redesign** (see below)

### 7. `HomePage.svelte`
- `lang` prop already exists
- Subtitle: `{lang === 'ja' ? '出発駅を選択' : 'Select your origin station'}`
- Search placeholder: `placeholder={lang === 'ja' ? '駅を検索…' : 'Search stations...'}`
- No-stations message: `{lang === 'ja' ? '駅が見つかりません。' : 'No stations found.'}`
- Error message: `{lang === 'ja' ? 'データを読み込めませんでした。後でもう一度お試しください。' : 'Could not load stations. Try again later.'}`
- **Station list collation in ja mode** (see below)

---

## Pill display redesign

Currently (ja mode): pill shows Japanese name with 駅 stripped; no English.

**New design**: English name inside the pill (same as en mode); full Japanese name with 駅 included shown below the pill in smaller text.

Scope: only when `lang === 'ja'` and a Japanese name exists. If no Japanese name, show only the English pill (no sub-label).

Changes in `Header.svelte` pill rendering:
- Keep pill content as the English headsign (no change to the colored span)
- Below each `.pill-entry`, add a `<span class="pill-ja-name">` when `lang === 'ja'` and `jaHeadsign(headsign, null)` is non-null, showing the full Japanese name (do NOT strip 駅)
- The `.pill-entry` flex column already exists; add the Japanese sub-label after the colored pill span

---

## Station list Japanese collation

Currently: station list is displayed in the order returned by the OBA API (by stop ID, roughly numeric).

**When `lang === 'ja'`**: sort the station list in Japanese phonetic order (五十音順, gojūon-jun) by the Japanese name. Use `String.prototype.localeCompare` with locale `'ja'`.

Stations with no Japanese name (those where `jaStopName` returns null/fallback) should sort after those with Japanese names, using their English name as a fallback sort key.

Changes in `HomePage.svelte`: apply sort to `homeStops` when `lang === 'ja'` before rendering.

---

## Acceptance criteria

1. Timetable: when `lang === 'ja'` and no departures, shows `この日は運行がありません。`
2. DatePicker: when `lang === 'ja'`, label reads `日付`
3. DestinationPicker: when `lang === 'ja'`, placeholder reads `目的地を検索…`
4. DestinationPicker: when `lang === 'ja'` and no search results, shows `結果なし`
5. HeadsignFilter: when `lang === 'ja'` and user unchecks last item, error reads `少なくとも1つの行先を選択してください。`
6. App home error: when `lang === 'ja'` and station fetch fails, shows `データを読み込めませんでした。後でもう一度お試しください。`
7. App schedule error: when `lang === 'ja'` and schedule fetch fails (non-400/404), shows `時刻表を読み込めませんでした。後でもう一度お試しください。`
8. App 404: when `lang === 'ja'` and API returns 404, shows `駅が見つかりません。`
9. App 400: when `lang === 'ja'` and API returns 400, shows `無効なリクエストです。`
10. App no-stop-ID: when `lang === 'ja'` and URL has no stop ID, shows `停留所IDが指定されていません。/stop/<stopId> にアクセスしてください。`
11. Header loading: when `lang === 'ja'` and stop not yet loaded, h1 shows `読み込み中…`
12. HomePage subtitle: when `lang === 'ja'`, subtitle reads `出発駅を選択`
13. HomePage search placeholder: when `lang === 'ja'`, placeholder reads `駅を検索…`
14. HomePage no-stations: when `lang === 'ja'` and list is empty, shows `駅が見つかりません。`
15. Pills label: when `lang === 'ja'`, pills label reads `行先` (not `駅方面`)
16. Pills display: when `lang === 'ja'`, each pill shows the English headsign inside the colored pill, with the full Japanese name (including 駅) displayed below the pill in smaller text; if no Japanese name exists for a headsign the sub-label is omitted
17. Station collation: when `lang === 'ja'`, station list is sorted in 五十音順 by Japanese name; stations without a Japanese name sort last
