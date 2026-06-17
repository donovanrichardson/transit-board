<script>
  import { CITY_TERMINALS } from '../lib/lirr.js';

  export let stop = null;
  export let headsigns = [];
  export let selectedHeadsigns = new Set();
  export let departures = [];
  export let isLirrMode = false;

  /**
   * Computes the pill background/text color for a given headsign.
   * In LIRR mode: applies 2/3 dominance threshold.
   * In non-LIRR mode: plurality pick (unchanged).
   */
  function headsignColor(headsign) {
    const counts = {};
    for (const d of departures) {
      if (d.headsign === headsign) {
        const id = d.routeId;
        if (!counts[id]) counts[id] = { count: 0, color: d.routeColor, textColor: d.routeTextColor };
        counts[id].count++;
      }
    }

    if (isLirrMode) {
      const total = Object.values(counts).reduce((s, v) => s + v.count, 0);
      if (total === 0) return { bg: '#666666', text: '#FFFFFF' };

      let topId = null;
      let topCount = 0;
      for (const [id, v] of Object.entries(counts)) {
        if (v.count > topCount) {
          topCount = v.count;
          topId = id;
        }
      }

      if (topCount > (2 / 3) * total) {
        const best = counts[topId];
        if (best && best.color) {
          return { bg: '#' + best.color, text: '#' + (best.textColor || 'FFFFFF') };
        }
      }

      const cityTerminalCount = departures.filter(
        (d) => d.headsign === headsign && CITY_TERMINALS.includes(d.headsign)
      ).length;
      if (cityTerminalCount >= (2 / 3) * total) {
        return { bg: '#4D5357', text: '#FFFFFF' };
      }

      return { bg: '#0039A6', text: '#FFFFFF' };
    }

    // Non-LIRR: plurality pick
    let max = 0;
    let best = null;
    for (const v of Object.values(counts)) {
      if (v.count > max) {
        max = v.count;
        best = v;
      }
    }
    if (!best || !best.color) {
      return { bg: '#666666', text: '#FFFFFF' };
    }
    return { bg: '#' + best.color, text: '#' + (best.textColor || 'FFFFFF') };
  }

  $: hasDirection = !isLirrMode && stop && stop.direction;
  $: hasSiblings = !isLirrMode && stop && stop.siblingStopIds && stop.siblingStopIds.length > 0;
  $: stopTitle = stop
    ? ((!isLirrMode && stop.direction) ? `${stop.name} (${stop.direction})` : stop.name)
    : 'Loading…';

  $: visibleHeadsigns = hasDirection
    ? []
    : headsigns.filter((h) => selectedHeadsigns.has(h));
</script>

<header class="timetable-header">
  <div class="header-top">
    <h1 class="stop-name">{stopTitle}</h1>
    {#if hasSiblings}
      {#each stop.siblingStopIds as siblingId}
        <a href="/stop/{siblingId}" class="direction-toggle">
          {siblingId}
        </a>
      {/each}
    {/if}
  </div>

  {#if !hasDirection && visibleHeadsigns.length > 0}
    <div class="headsign-pills">
      {#each visibleHeadsigns as headsign}
        {@const colors = headsignColor(headsign)}
        <span
          class="headsign-pill"
          style="background-color: {colors.bg}; color: {colors.text};"
        >
          {headsign}
        </span>
      {/each}
    </div>
  {/if}
</header>

<style>
  .timetable-header {
    padding: 12px 16px;
    border-bottom: 1px solid #e0e0e0;
  }

  .header-top {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  .stop-name {
    font-size: 1.25rem;
    font-weight: 600;
    margin: 0;
  }

  .direction-toggle {
    display: inline-block;
    padding: 4px 12px;
    background: #0039a6;
    color: #fff;
    border-radius: 4px;
    text-decoration: none;
    font-size: 0.85rem;
    white-space: nowrap;
  }

  .direction-toggle:hover {
    background: #002d8c;
  }

  .headsign-pills {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 8px;
  }

  .headsign-pill {
    padding: 2px 10px;
    border-radius: 12px;
    font-size: 0.8rem;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 200px;
  }
</style>
