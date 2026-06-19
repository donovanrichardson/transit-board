# Spec: Header revamp — dropdown destination search, direction label, border relocation

## Goal
Replace the flat destination button list with a proper search dropdown. Show the current direction/destination in the header (right-aligned). Move the colored border to span the full header width.

## Scope

### In scope
- `frontend/src/components/DestinationPicker.svelte` — remove flat list + direction buttons; keep search input; add dropdown
- `frontend/src/components/Header.svelte` — add `lirrDestinationMode`/`lirrSelectedHeadsign` props; add right-aligned direction label; move border from h1 to header; remove headsign pills
- `frontend/src/App.svelte` — pass new props to Header
- `frontend/src/components/__tests__/Header.test.js` — remove pill tests; add direction label + border tests
- `frontend/src/components/__tests__/DestinationPicker.test.js` — add/update tests for dropdown behaviour

### Out of scope
- `filterDepartures`, `Timetable.svelte`, `HeadsignFilter.svelte` — unchanged
- `lirrDestinationMode` / `lirrSelectedHeadsign` state in App.svelte — kept as-is
- Adding Inbound/Outbound as dropdown items — out of scope; they remain as two buttons above the search input

---

## Behavior

### 1. DestinationPicker — remove flat list, add dropdown

**Remove**: the `.destination-list` div (the always-visible flat grid of destination buttons).

**Keep**: the `.direction-buttons` row (Inbound / Outbound buttons) and the search `<input>`.

**Add**: a `.destination-dropdown` absolutely-positioned list that appears **only when** `searchText` is non-empty. It shows `filteredHeadsigns`. Clicking an item selects it (dispatches `change` with `mode: 'specific'`), clears `searchText`, and closes the dropdown. If `filteredHeadsigns` is empty, the dropdown shows a single disabled item `"No results"`.

The dropdown must be hidden when `searchText` is empty (no dropdown on focus alone). Clicking outside the component clears `searchText` and closes the dropdown (`on:blur` on the input with a small delay to allow click to register, or a `clickoutside` approach — use a `use:clickOutside` action or a wrapper div with `on:focusout`).

Selecting a direction button (Inbound/Outbound) clears `searchText` and closes the dropdown, as it does today.

**Position**: the `.destination-search` wrapper gets `position: relative`. The dropdown is `position: absolute; top: 100%; left: 0; right: 0; z-index: 100` with a white background, border, and shadow.

### 2. Header — new props

Add to `Header.svelte`:

| Prop | Type | Default |
|------|------|---------|
| `lirrDestinationMode` | `string` | `'inbound'` |
| `lirrSelectedHeadsign` | `string \| null` | `null` |

App.svelte passes these alongside existing props.

### 3. Header — right-aligned direction label

When `isLirrMode` is `true`, render inside `.header-top`:

```
lirrDestinationMode === 'specific' && lirrSelectedHeadsign  →  "to {lirrSelectedHeadsign}"
lirrDestinationMode === 'outbound'                          →  "Outbound"
otherwise                                                   →  "Inbound"
```

CSS class `direction-label`. Style: `margin-left: auto; font-size: 1.1rem; font-weight: 500; white-space: nowrap; color: #333`.

Not rendered when `isLirrMode` is `false`.

### 4. Header — remove headsign pills

Remove the `{#if !hasDirection && visibleHeadsigns.length > 0}` block, `headsignColor` function, `visibleHeadsigns` reactive, and `.headsign-pills` / `.headsign-pill` CSS.

### 5. Header — border relocation

- Remove `style="border-bottom: 4px solid {lineColor};"` from `h1.stop-name`.
- Remove `padding-bottom: 4px` from `.stop-name` CSS.
- Remove `border-bottom: 1px solid #e0e0e0` from `.timetable-header` CSS.
- Add `style="border-bottom: 4px solid {lineColor};"` to the `<header class="timetable-header">` element.

### 6. Final markup shapes

**DestinationPicker** (simplified):
```html
<div class="destination-picker">
  <div class="direction-buttons">
    <button class:active={directionMode === 'inbound'} on:click={() => selectDirection('inbound')}>Inbound</button>
    <button class:active={directionMode === 'outbound'} on:click={() => selectDirection('outbound')}>Outbound</button>
  </div>
  <div class="destination-search">
    <input type="text" placeholder="Search destinations..." bind:value={searchText} />
    {#if searchText}
      <div class="destination-dropdown">
        {#if filteredHeadsigns.length === 0}
          <div class="dropdown-empty">No results</div>
        {#each filteredHeadsigns as headsign}
          <button class="dropdown-item" on:click={() => selectHeadsign(headsign)}>{headsign}</button>
        {/each}
      </div>
    {/if}
  </div>
</div>
```

**Header** (simplified):
```html
<header class="timetable-header" style="border-bottom: 4px solid {lineColor};">
  <div class="header-top">
    <h1 class="stop-name">{stopTitle}</h1>
    {#if hasSiblings}…{/if}
    {#if isLirrMode}
      <span class="direction-label">{directionLabelText}</span>
    {/if}
  </div>
</header>
```

---

## Edge cases

| Scenario | Behavior |
|---|---|
| `searchText` empty | No dropdown shown |
| Search matches nothing | Dropdown shows "No results" (disabled) |
| `mode='specific'`, `lirrSelectedHeadsign=null` | Header shows "Inbound" |
| Click Inbound/Outbound button | Clears search, closes dropdown |
| `isLirrMode=false` | No direction label in header |
| `departures` empty | `lineColor` → `#666666` |

---

## Acceptance criteria

- [ ] Flat destination button list is gone from DestinationPicker
- [ ] Typing in the search input reveals a dropdown of matching destinations
- [ ] Empty search = no dropdown
- [ ] Selecting a dropdown item fires `change` with `mode:'specific'` and clears search
- [ ] Inbound/Outbound buttons remain and still work
- [ ] Header shows "Inbound" / "Outbound" / "to X" right-aligned (LIRR mode only)
- [ ] `.timetable-header` has 4px colored border spanning full width
- [ ] `h1.stop-name` has no border-bottom
- [ ] No `.headsign-pill` elements
- [ ] All tests pass

---

## Tests

### DestinationPicker.test.js

- `dropdown.hiddenWhenEmpty`: render with destinations, `searchText=''` → no `.destination-dropdown`
- `dropdown.visibleWhenTyping`: set `searchText='Bay'` → `.destination-dropdown` exists, items filtered
- `dropdown.noResults`: `searchText='zzz'` → dropdown shows "No results"
- `dropdown.selectItem`: click dropdown item → `change` event fired with `{mode:'specific', headsign: ...}`, dropdown closes
- `dropdown.clearOnDirectionSelect`: click Inbound button while search has text → search cleared, dropdown gone

### Header.test.js (remove old pill tests, add):

- `directionLabel.inbound`: `isLirrMode=true`, `mode='inbound'` → `.direction-label` = `"Inbound"`
- `directionLabel.outbound`: `isLirrMode=true`, `mode='outbound'` → `.direction-label` = `"Outbound"`
- `directionLabel.specific`: `isLirrMode=true`, `mode='specific'`, headsign=`'Babylon'` → `"to Babylon"`
- `directionLabel.specificNullHeadsign`: `isLirrMode=true`, `mode='specific'`, headsign=`null` → `"Inbound"`
- `directionLabel.hiddenNonLirr`: `isLirrMode=false` → no `.direction-label`
- `border.onHeader`: `<header>` inline style contains `border-bottom: 4px solid`
- `border.notOnStopName`: `h1.stop-name` has no `border-bottom` in inline style
- `headsignPills.removed`: no `.headsign-pill` elements

---

## Files that will change

- `frontend/src/components/DestinationPicker.svelte` — remove flat list; add dropdown
- `frontend/src/components/Header.svelte` — direction label; border relocation; remove pills
- `frontend/src/App.svelte` — pass `lirrDestinationMode` + `lirrSelectedHeadsign` to Header
- `frontend/src/components/__tests__/Header.test.js` — remove pill tests; add new tests
- `frontend/src/components/__tests__/DestinationPicker.test.js` — add dropdown tests
