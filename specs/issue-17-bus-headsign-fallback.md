# Spec: Bus headsigns fall back to their rail headsign's abbreviation

Tracker: transit-board#17

## Goal
A headsign of the form `"<Base> (Bus)"` that has no explicit entry in `headsign-abbreviations.json` (frontend lib) should resolve to the abbreviation of `"<Base>"` (its rail counterpart) if that entry exists, instead of being treated as unlabeled. This closes a gap where e.g. `"Great Neck (Bus)"`, `"Port Washington (Bus)"`, and `"West Hempstead (Bus)"` render as "Unlabeled" in the UI and are flagged as missing by the gtfs-updater coverage check, purely because no one added a redundant `"X (Bus)": "<same code as X>"` entry for them.

## Scope

### In scope
- `lirr.js` (frontend lib) — add a new exported lookup function `getHeadsignAbbreviation(headsign)` that implements: explicit entry in the JSON wins; otherwise if the headsign matches `^(.*) \(Bus\)$` and the capture group has an entry, return that; otherwise return `undefined` (unchanged "not found" behavior). `HEADSIGN_ABBREVIATIONS` (the raw imported JSON object) stays exported as-is, but is no longer read directly by the two UI consumers.
- `Header.svelte` (frontend components) — `effectiveAbbr()` uses the new lookup function instead of `HEADSIGN_ABBREVIATIONS[headsign]` directly, so the pill abbreviation and sort order pick up the fallback.
- `Timetable.svelte` (frontend components) — the `abbreviation={...}` expression passed to `MinuteCell` uses the new lookup function instead of `HEADSIGN_ABBREVIATIONS[dep.headsign] || ''`.
- `gtfs-updater/updater.py` — `check_headsign_coverage()` treats a GTFS headsign as "known" if it has an explicit entry in the JSON *or* it matches `^(.*) \(Bus\)$` and its capture group has an entry. Only headsigns satisfying neither are reported in `missing_headsigns`. `total_mapped` keeps its meaning (literal count of keys in the JSON); only the missing-set calculation changes.
- `headsign-abbreviations.json` (frontend lib) — no required change under this spec (see Open questions).

### Out of scope
- Any change to `computeHeadsignAbbreviationVisibility` / `computeRouteIconVisibility` in `timetable.js` (frontend lib) — these only count occurrences and are agnostic to how an abbreviation value is produced.
- Any change to `check_id_allowlist` or other coverage-adjacent checks in `gtfs-updater/updater.py`.
- Adding a `"Valley Stream"` rail entry, or deleting redundant `(Bus)` entries — deferred (see Open questions).
- Any regex beyond `^(.*) \(Bus\)$` (no other suffixes, repeated suffixes, or case-insensitivity).
- Localization (`jaHeadsign`) — unaffected.

## Behavior

### Resolution order (both frontend and gtfs-updater)
1. If `headsign` is an exact key in `headsign-abbreviations.json`, use that value. (Explicit entries always win, including existing redundant `(Bus)` entries like `"Babylon (Bus)": "BAB"`.)
2. Else, if `headsign` matches `^(.*) \(Bus\)$`, take the capture group as `base`. If `base` is a key in the JSON, use that value.
3. Else, unresolved (frontend: renders "Unlabeled"; gtfs-updater: included in `missing_headsigns`).

### Examples (post-change, against the current JSON file)
| Input headsign | Resolution | Result |
|---|---|---|
| `"Great Neck (Bus)"` | no explicit entry; base `"Great Neck"` exists | `GNK` |
| `"Port Washington (Bus)"` | no explicit entry; base `"Port Washington"` exists | `PWS` |
| `"West Hempstead (Bus)"` | no explicit entry; base `"West Hempstead"` exists | `WHD` |
| `"Babylon (Bus)"` | explicit entry exists | `BAB` (explicit wins) |
| `"Valley Stream (Bus)"` | no explicit entry; base `"Valley Stream"` does not exist | unresolved (unchanged) |
| `"Great Neck"` | explicit entry exists | `GNK` (unchanged) |
| `"Foo Bar"` | no suffix, no entry | unresolved (unchanged) |

### Frontend lookup function signature
```js
// lirr.js (frontend lib)
export function getHeadsignAbbreviation(headsign) {
  // returns the abbreviation string, or undefined if unresolved
}
```
- `Header.svelte` `effectiveAbbr()`: replace `HEADSIGN_ABBREVIATIONS[headsign] || null` with `getHeadsignAbbreviation(headsign) || null` (the visibility-gated structure stays the same, only the lookup is swapped).
- `Timetable.svelte`: replace `HEADSIGN_ABBREVIATIONS[dep.headsign] || ''` with `getHeadsignAbbreviation(dep.headsign) || ''`.

### gtfs-updater coverage check
In `check_headsign_coverage()`, replace the direct set difference `gtfs_headsigns - known_headsigns` with a check that, for each headsign in `gtfs_headsigns` not in `known_headsigns`, also checks whether it matches `^(.*) \(Bus\)$` with the capture group present in `known_headsigns`; only headsigns failing both go into `missing`. `total_gtfs_headsigns` and `total_mapped` keep their current definitions.

## Edge cases
- Repeated suffix (theoretical `"X (Bus) (Bus)"`): the anchored greedy regex strips only the final suffix once; capture group `"X (Bus)"` would need its own entry. No recursive stripping. No such headsign exists today.
- Empty or whitespace-only base: capture group matches no real key, falls through to unresolved.
- No `" (Bus)"` suffix: step 2 is skipped — identical to current behavior.
- Explicit `(Bus)` entry with a code different from its base: step 1 short-circuits, explicit value wins.
- Case sensitivity: exact-string matching, same as today.
- `check_headsign_coverage`'s malformed-JSON / missing-file / missing-column early-return paths are unchanged.

## Acceptance criteria
- [ ] `getHeadsignAbbreviation('Great Neck (Bus)')` returns `'GNK'`.
- [ ] `getHeadsignAbbreviation('Port Washington (Bus)')` returns `'PWS'`.
- [ ] `getHeadsignAbbreviation('West Hempstead (Bus)')` returns `'WHD'`.
- [ ] `getHeadsignAbbreviation('Babylon (Bus)')` still returns `'BAB'` via the explicit entry.
- [ ] `getHeadsignAbbreviation('Valley Stream (Bus)')` returns falsy.
- [ ] `getHeadsignAbbreviation('Great Neck')` still returns `'GNK'`.
- [ ] `getHeadsignAbbreviation('Totally Unknown Place (Bus)')` returns falsy.
- [ ] Header pill for `"Great Neck (Bus)"` shows `GNK` instead of `Unlabeled` (component-level test).
- [ ] Timetable minute-cell abbreviation for a departure with headsign `'Great Neck (Bus)'` renders `GNK` instead of an empty string.
- [ ] `check_headsign_coverage()` with GTFS headsigns `'Great Neck (Bus)'`, `'Port Washington (Bus)'`, `'West Hempstead (Bus)'` and a JSON containing only their rail bases produces no missing entries for those three.
- [ ] `check_headsign_coverage()` with GTFS headsign `'Valley Stream (Bus)'` and no `"Valley Stream"` entry still reports it missing.
- [ ] All existing tests in `lirr.test.js` (frontend lib tests), `Header.test.js` (frontend component tests), and `gtfs-updater/test_updater.py` still pass with no existing assertion weakened.

## Tests to write

### Frontend (vitest)
- `lirr.test.js` (frontend lib tests):
  - resolves Great Neck (Bus) to GNK via base fallback
  - resolves Port Washington (Bus) to PWS via base fallback
  - resolves West Hempstead (Bus) to WHD via base fallback
  - prefers explicit entry over fallback for Babylon (Bus)
  - returns undefined for Valley Stream (Bus) (no base entry)
  - returns undefined for a headsign with no suffix and no entry
  - returns the direct value for a plain rail headsign
- `Header.test.js` (frontend component tests):
  - pill abbreviation shows GNK for headsign "Great Neck (Bus)" with no explicit JSON entry

### gtfs-updater (unittest, extending `gtfs-updater/test_updater.py`)
- `test_check_headsign_coverage_bus_headsign_resolves_via_base_fallback`: JSON `{"Great Neck": "GNK"}`, trips contain `"Great Neck (Bus)"` → no missing headsigns.
- `test_check_headsign_coverage_bus_headsign_without_base_still_missing`: JSON without `"Valley Stream"`, trips contain `"Valley Stream (Bus)"` → `["Valley Stream (Bus)"]`.
- `test_check_headsign_coverage_explicit_bus_entry_still_satisfies_even_if_base_missing`: JSON `{"Foo (Bus)": "FOO"}`, trips contain `"Foo (Bus)"` → no missing headsigns.
- `test_check_headsign_coverage_non_bus_headsign_unaffected`: a non-suffixed headsign with no entry is still reported missing.

## Files that will change
- `lirr.js` (frontend lib)
- `Header.svelte` (frontend components)
- `Timetable.svelte` (frontend components)
- `lirr.test.js` (frontend lib tests)
- `Header.test.js` (frontend component tests)
- `gtfs-updater/updater.py`
- `gtfs-updater/test_updater.py`

## Open questions
1. **Add a `"Valley Stream"` rail entry (candidate code `VSM`)?** `"Valley Stream (Bus)"` stays missing after this fix because there is no `"Valley Stream"` entry to fall back to. Leaving it out is a safe default.
2. **Delete the now-redundant explicit `(Bus)` entries whose code equals the base code?** (Babylon, Central Islip, Greenport, Huntington, Montauk, Oyster Bay, Patchogue, Port Jefferson, Riverhead, Ronkonkoma, Smithtown, Southampton, Speonk.) `Glen Cove (Bus)`, `Greenvale (Bus)` and `Mineola (Bus)` have no rail base and must stay. Removal is pure cleanup with no behavioral effect; leaving them is harmless.
