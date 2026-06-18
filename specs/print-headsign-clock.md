# Spec: Print layout, headsign pill filtering, and 12/24h clock toggle

## Feature 1 — Print layout improvements

### Goal
When the user prints the stop page, the output should show a readable date and omit the destination picker UI.

### Changes

**Date formatting in print**
- App.svelte renders a hidden-in-screen, visible-in-print `<div class="print-date">` element between the Header and the DatePicker.
- Its content is the `date` prop formatted as a long date string: e.g. `"Thursday, June 18, 2026"`.
- Format using `new Intl.DateTimeFormat('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })` on a date parsed from the ISO string. Parse as `new Date(year, month-1, day)` (not `new Date(isoString)`) to avoid UTC offset issues.
- In screen mode: `display: none`. In `@media print`: `display: block`.

**Hide destination picker in print**
- Add `@media print { display: none; }` to `DestinationPicker.svelte`'s `.destination-picker` root element style.

### Acceptance criteria
- [ ] Printing a LIRR stop page does not show the DestinationPicker.
- [ ] The formatted date (e.g. "Thursday, June 18, 2026") appears in the print output.
- [ ] The screen layout is unchanged.

### Files changed
- `frontend/src/App.svelte` — add `.print-date` element and styles
- `frontend/src/components/DestinationPicker.svelte` — add print media query

### Tests
Print CSS is not testable in jsdom. No automated tests for this feature.

---

## Feature 2 — Headsign pills reflect the visible timetable

### Goal
Headsign pills in the Header should only show headsigns present in the currently displayed (filtered) departures, not all headsigns in the raw data.

### Current behaviour
`visibleHeadsigns` in Header is `headsigns.filter(h => selectedHeadsigns.has(h))`. This works for non-LIRR (HeadsignFilter toggles). In LIRR mode, `selectedHeadsigns` is never modified by the DestinationPicker, so all headsigns always appear regardless of the active destination filter.

### Changes

**Extract `filterDepartures` to `timetable.js`**
New exported function:
```js
export function filterDepartures(departures, { isLirrMode, lirrDestinationMode, lirrSelectedHeadsign, selectedHeadsigns }) {
  if (!isLirrMode) {
    return departures.filter(d => !d.headsign || selectedHeadsigns.has(d.headsign));
  }
  if (lirrDestinationMode === 'specific' && lirrSelectedHeadsign) {
    return departures.filter(d => d.downstreamStops && d.downstreamStops.includes(lirrSelectedHeadsign));
  }
  if (lirrDestinationMode === 'outbound') {
    return departures.filter(d => d.directionId === '0');
  }
  return departures.filter(d => d.directionId === '1');
}
```

**App.svelte**
- Import `filterDepartures` from `./lib/timetable.js`.
- Compute `$: filteredDepartures = filterDepartures(departures, { isLirrMode: lirrMode, lirrDestinationMode, lirrSelectedHeadsign, selectedHeadsigns })`.
- Pass `departures={filteredDepartures}` to `<Header>` instead of raw `{departures}`.

**Header.svelte**
- No new props. The `departures` prop it already receives is now pre-filtered by App.
- Replace `$: visibleHeadsigns = hasDirection ? [] : headsigns.filter(h => selectedHeadsigns.has(h))` with:
  ```js
  $: visibleHeadsigns = hasDirection ? [] : [...new Set(departures.map(d => d.headsign).filter(Boolean))];
  ```
- `headsignColor()` continues to iterate `departures` — now over filtered departures, which is correct: pill color reflects the dominant route among the trains you're actually seeing.
- The `headsigns` and `selectedHeadsigns` props are no longer used in `visibleHeadsigns` computation and can be removed if nothing else uses them.

**Timetable.svelte**
- Import `filterDepartures` and replace the inline `$: filteredDepartures` reactive block with a call to it.

### Acceptance criteria
- [ ] In non-LIRR mode, headsign pills only show headsigns with at least one visible departure (i.e. not toggled off via HeadsignFilter).
- [ ] In LIRR inbound mode, pills show all inbound headsigns.
- [ ] In LIRR outbound mode, pills show all outbound headsigns.
- [ ] In LIRR specific-destination mode (e.g. "Greenlawn"), pills show only the headsigns of departures that serve Greenlawn.
- [ ] Existing pill colour logic is unchanged.

### Files changed
- `frontend/src/lib/timetable.js` — add `filterDepartures`
- `frontend/src/lib/__tests__/timetable.test.js` — tests for `filterDepartures`
- `frontend/src/components/Header.svelte` — derive `visibleHeadsigns` from `departures` directly; remove unused props
- `frontend/src/components/Timetable.svelte` — use `filterDepartures` instead of inline logic
- `frontend/src/App.svelte` — compute `filteredDepartures`, pass to Header

### Tests (in `timetable.test.js`)
- `filterDepartures.nonLirr.excludesDeselected` — deselected headsign excluded from result
- `filterDepartures.nonLirr.includesSelected` — selected headsign included
- `filterDepartures.lirr.inbound` — returns only directionId '1' departures
- `filterDepartures.lirr.outbound` — returns only directionId '0' departures
- `filterDepartures.lirr.specific` — returns only departures whose `downstreamStops` includes the selected headsign

---

## Feature 3 — 12/24-hour clock toggle

### Goal
A toggle lets the user switch the hour column between 24-hour (current) and 12-hour display. Defaults to 12-hour. Persists via `localStorage`.

### `formatHourLabel(hour, clockMode)` in `timetable.js`
- `'24h'`: returns `String(hour).padStart(2, '0')` (current behaviour).
- `'12h'` mapping:
  - 0 → `'12a'`, 1–11 → `'{h}a'` (no leading zero), 12 → `'12p'`, 13–23 → `'{h-12}p'`
  - 24 → `'12a'`, 25+ → `'{h-24}a'`
- Hours ≥ 24 wrap around (service-day convention; chronological grid order prevents ambiguity).

### `ClockToggle.svelte` (new component)
- Props: `clockMode` (`'12h'` | `'24h'`).
- Renders two buttons: `12h` and `24h`. Active button has a distinct filled style.
- Dispatches `change` event with `{ clockMode: '12h' | '24h' }` on click.

### `Timetable.svelte`
- New prop: `clockMode = '12h'`.
- Hour cell renders `{formatHourLabel(hour, clockMode)}` instead of `{String(hour).padStart(2, '0')}`.
- Widen `.hour-cell` `min-width` from `48px` to `52px` (accommodates 3-char labels like `'12p'`).

### `App.svelte`
- On mount: read `localStorage.getItem('transitBoard.clockMode')`. Use `'24h'` only if stored value is exactly `'24h'`; otherwise default to `'12h'`. Wrap in `try/catch`.
- Render `<ClockToggle {clockMode} on:change={onClockModeChange} />` between DatePicker and the headsign/destination filter.
- `onClockModeChange`: update state, `localStorage.setItem('transitBoard.clockMode', newValue)` in `try/catch`.
- Pass `{clockMode}` to `<Timetable>`.

### Acceptance criteria
- [ ] First visit (no localStorage): hour column shows 12-hour labels (`'5a'`, `'12p'`).
- [ ] Toggle is visible between DatePicker and headsign/destination controls.
- [ ] Switching to `24h` shows zero-padded 24-hour labels (`'05'`, `'13'`).
- [ ] Switching back to `12h` restores 12-hour labels.
- [ ] Selected mode persists on page reload via `localStorage` key `transitBoard.clockMode`.
- [ ] `localStorage` failure does not throw; defaults to `'12h'`.
- [ ] `formatHourLabel(0, '12h')` → `'12a'`, `formatHourLabel(12, '12h')` → `'12p'`, `formatHourLabel(13, '12h')` → `'1p'`, `formatHourLabel(25, '12h')` → `'1a'`.
- [ ] `formatHourLabel(5, '24h')` → `'05'`, `formatHourLabel(25, '24h')` → `'25'`.

### Files changed
- `frontend/src/lib/timetable.js` — add `formatHourLabel`
- `frontend/src/lib/__tests__/timetable.test.js` — `formatHourLabel` tests
- `frontend/src/components/ClockToggle.svelte` — new component
- `frontend/src/components/__tests__/ClockToggle.test.js` — new test file
- `frontend/src/components/Timetable.svelte` — use `formatHourLabel`, new prop, widen hour cell
- `frontend/src/App.svelte` — clock mode state, localStorage, ClockToggle, pass prop
