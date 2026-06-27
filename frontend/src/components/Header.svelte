<script>
  import { CITY_TERMINALS, HEADSIGN_ABBREVIATIONS } from '../lib/lirr.js';
  import { jaStopName, jaHeadsign } from '../lib/locale.js';

  export let stop = null;
  export let departures = [];
  export let isLirrMode = false;
  export let lirrDestinationMode = 'inbound';
  export let lirrSelectedHeadsign = null;
  export let headsignAbbrevVisibility = null;
  export let lang = 'en';

  // 3-step cascade over all departures to pick the underline color.
  $: lineColor = (() => {
    const byColor = {};
    for (const d of departures) {
      const key = (d.routeColor || '').toUpperCase() || 'NONE';
      if (!byColor[key]) byColor[key] = { count: 0, color: d.routeColor };
      byColor[key].count++;
    }
    const total = Object.values(byColor).reduce((s, v) => s + v.count, 0);
    if (total === 0) return '#666666';

    let topKey = null, topCount = 0;
    for (const [key, v] of Object.entries(byColor)) {
      if (v.count > topCount) { topCount = v.count; topKey = key; }
    }

    if (isLirrMode) {
      if (topCount > (2 / 3) * total && byColor[topKey].color) {
        return '#' + byColor[topKey].color;
      }
      const cityCount = departures.filter((d) => CITY_TERMINALS.includes(d.headsign)).length;
      if (cityCount >= (2 / 3) * total) return '#4D5357';
      return '#0039A6';
    }

    return byColor[topKey] && byColor[topKey].color ? '#' + byColor[topKey].color : '#666666';
  })();

  $: hasSiblings = !isLirrMode && stop && stop.siblingStopIds && stop.siblingStopIds.length > 0;
  $: stopTitle = stop
    ? ((!isLirrMode && stop.direction) ? `${stop.name} (${stop.direction})` : stop.name)
    : 'Loading…';

  function headsignColor(headsign) {
    const byColor = {};
    for (const d of departures) {
      if (d.headsign === headsign) {
        const key = (d.routeColor || '').toUpperCase() || 'NONE';
        if (!byColor[key]) byColor[key] = { count: 0, color: d.routeColor, textColor: d.routeTextColor };
        byColor[key].count++;
      }
    }
    if (isLirrMode) {
      const total = Object.values(byColor).reduce((s, v) => s + v.count, 0);
      if (total === 0) return { bg: '#666666', text: '#FFFFFF' };
      let topKey = null, topCount = 0;
      for (const [key, v] of Object.entries(byColor)) {
        if (v.count > topCount) { topCount = v.count; topKey = key; }
      }
      if (topCount > (2 / 3) * total && byColor[topKey].color) {
        return { bg: '#' + byColor[topKey].color, text: '#' + (byColor[topKey].textColor || 'FFFFFF') };
      }
      const cityCount = departures.filter((d) => d.headsign === headsign && CITY_TERMINALS.includes(d.headsign)).length;
      if (cityCount >= (2 / 3) * total) return { bg: '#4D5357', text: '#FFFFFF' };
      return { bg: '#0039A6', text: '#FFFFFF' };
    }
    let max = 0, best = null;
    for (const v of Object.values(byColor)) {
      if (v.count > max) { max = v.count; best = v; }
    }
    if (!best || !best.color) return { bg: '#666666', text: '#FFFFFF' };
    return { bg: '#' + best.color, text: '#' + (best.textColor || 'FFFFFF') };
  }

  $: hasDirection = !isLirrMode && stop && stop.direction;
  function effectiveAbbr(headsign) {
    if (headsignAbbrevVisibility === null) return HEADSIGN_ABBREVIATIONS[headsign] || null;
    return headsignAbbrevVisibility[headsign] ? HEADSIGN_ABBREVIATIONS[headsign] : null;
  }

  $: visibleHeadsigns = (() => {
    if (hasDirection) return [];
    const unique = [...new Set(departures.map((d) => d.headsign).filter(Boolean))];
    return unique.slice().sort((a, b) => {
      const abbrA = effectiveAbbr(a);
      const abbrB = effectiveAbbr(b);
      if (!abbrA && !abbrB) return 0;
      if (!abbrA) return -1;
      if (!abbrB) return 1;
      return abbrA < abbrB ? -1 : abbrA > abbrB ? 1 : 0;
    });
  })();

  $: directionLabelText = (() => {
    if (lirrDestinationMode === 'specific' && lirrSelectedHeadsign) {
      return `to ${lirrSelectedHeadsign}`;
    }
    if (lirrDestinationMode === 'outbound') {
      return 'Outbound';
    }
    return 'Inbound';
  })();

  $: jaDirectionLabel = (() => {
    if (lang !== 'ja') return null;
    if (lirrDestinationMode === 'specific' && lirrSelectedHeadsign) {
      return jaHeadsign(lirrSelectedHeadsign, lirrSelectedHeadsign);
    }
    return null;
  })();

  $: jaStopTitle = (() => {
    if (lang !== 'ja' || !stop) return null;
    const id = stop.id;
    return jaStopName(id, null);
  })();
</script>

<header class="timetable-header" style="border-bottom: 4px solid {lineColor};">
  <div class="header-top">
    <h1 class="stop-name">
      {#if lang === 'ja' && jaStopTitle}
        {jaStopTitle}
        <span class="stop-name-en">{stopTitle}</span>
      {:else}
        {stopTitle}
      {/if}
    </h1>
    {#if hasSiblings}
      {#each stop.siblingStopIds as siblingId}
        <a href="/stop/{siblingId}" class="direction-toggle">
          {siblingId}
        </a>
      {/each}
    {/if}
    {#if isLirrMode}
      <span class="direction-label">
        {#if lang === 'ja' && jaDirectionLabel}
          {jaDirectionLabel}
          <span class="direction-label-en">{directionLabelText}</span>
        {:else}
          {directionLabelText}
        {/if}
      </span>
    {/if}
  </div>
</header>

{#if !hasDirection && visibleHeadsigns.length > 0}
  <div class="headsign-pills">
    <p class="pills-label">{lang === 'ja' ? '駅方面' : 'Trips toward'}</p>
    <div class="pills-row">
      {#each visibleHeadsigns as headsign}
        {@const colors = headsignColor(headsign)}
        {@const abbr = effectiveAbbr(headsign) || null}
        <div class="pill-entry">
          {#if visibleHeadsigns.length > 1}
            <div class="pill-abbreviation">{abbr ?? 'Unlabeled'}</div>
          {/if}
          <span
            class="headsign-pill"
            style="background-color: {colors.bg}; color: {colors.text};"
          >
            {lang === 'ja' ? (() => { const ja = jaHeadsign(headsign, headsign); return (headsign === 'Penn Station' || headsign === 'Grand Central') ? ja : ja.replace(/駅$/, ''); })() : headsign}
          </span>
        </div>
      {/each}
    </div>
  </div>
{/if}

<style>
  .timetable-header {
    padding: 12px 16px;
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

  .stop-name-en {
    display: block;
    font-size: 0.8rem;
    font-weight: 400;
    color: #666;
  }

  .direction-label-en {
    display: block;
    font-size: 0.8rem;
    font-weight: 400;
    color: #666;
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

  .direction-label {
    margin-left: auto;
    font-size: 1.1rem;
    font-weight: 500;
    white-space: nowrap;
    color: #333;
  }

  .headsign-pills {
    padding: 8px 16px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .pills-label {
    margin: 0 0 6px 0;
    font-size: 1.1rem;
    font-weight: 500;
    color: #333;
  }

  .pills-row {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: flex-end;
    justify-content: center;
  }

  .pill-entry {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .pill-abbreviation {
    font-size: 0.65rem;
    font-weight: 700;
    color: #333;
    margin-bottom: 2px;
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
