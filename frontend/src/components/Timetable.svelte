<script>
  import MinuteCell from './MinuteCell.svelte';
  import { groupByHour, computeRowColor, computeRouteIconVisibility, computeHeadsignAbbreviationVisibility } from '../lib/timetable.js';
  import { HEADSIGN_ABBREVIATIONS, CITY_TERMINALS } from '../lib/lirr.js';

  export let departures = [];
  export let routes = [];
  export let agencyColor = null;
  export let selectedHeadsigns = new Set();
  export let isLirrMode = false;
  // For LIRR: 'inbound' | 'outbound' | 'specific'
  export let lirrDestinationMode = 'inbound';
  // For LIRR specific destination
  export let lirrSelectedHeadsign = null;

  $: filteredDepartures = (() => {
    if (!isLirrMode) {
      return departures.filter((d) => !d.headsign || selectedHeadsigns.has(d.headsign));
    }
    if (lirrDestinationMode === 'specific' && lirrSelectedHeadsign) {
      return departures.filter((d) => d.headsign === lirrSelectedHeadsign);
    }
    if (lirrDestinationMode === 'outbound') {
      return departures.filter((d) => d.directionId === '0');
    }
    // inbound (default)
    return departures.filter((d) => d.directionId === '1');
  })();

  // Determine base color for row tinting
  $: baseColor = (() => {
    if (isLirrMode && lirrDestinationMode === 'inbound') {
      // Inbound: 1 routeId >2/3 → that color; ≥2/3 city-terminal headsigns → #4D5357; else #0039A6
      const routeIds = filteredDepartures.map((d) => d.routeId);
      const total = routeIds.length;
      if (total > 0) {
        const counts = {};
        for (const id of routeIds) counts[id] = (counts[id] || 0) + 1;
        const [topId, topCount] = Object.entries(counts).reduce((a, b) => b[1] > a[1] ? b : a);
        if (topCount > (2 / 3) * total) {
          const route = routes.find((r) => r.id === topId);
          if (route && route.color) return route.color;
        }
        const cityTerminalCount = filteredDepartures.filter((d) => CITY_TERMINALS.includes(d.headsign)).length;
        if (cityTerminalCount >= (2 / 3) * total) return '4D5357';
      }
      return '0039A6';
    }
    const routeIds = [...new Set(filteredDepartures.map((d) => d.routeId))];
    if (routeIds.length === 1) {
      const route = routes.find((r) => r.id === routeIds[0]);
      if (route && route.color) return route.color;
    }
    return agencyColor || 'CCCCCC';
  })();

  $: iconVisibility = computeRouteIconVisibility(filteredDepartures);
  $: headsignAbbrevVisibility = computeHeadsignAbbreviationVisibility(filteredDepartures);

  $: grouped = groupByHour(filteredDepartures);

  // All hours from min to max, inclusive
  $: allHours = (() => {
    const hours = Object.keys(grouped).map(Number);
    if (hours.length === 0) return [];
    const min = Math.min(...hours);
    const max = Math.max(...hours);
    const result = [];
    for (let h = min; h <= max; h++) result.push(h);
    return result;
  })();
</script>

<div class="timetable">
  {#if allHours.length === 0}
    <p class="no-service">No scheduled service for this date.</p>
  {:else}
    <table class="timetable-table">
      <tbody>
        {#each allHours as hour}
          {@const rowDepartures = (grouped[String(hour)] || []).sort((a, b) => a.minute - b.minute)}
          {@const bgColor = computeRowColor(baseColor, null, hour)}
          <tr class="timetable-row" style="background-color: {bgColor};">
            <td class="hour-cell">{String(hour).padStart(2, '0')}</td>
            <td class="minutes-cell">
              {#each rowDepartures as dep}
                <MinuteCell
                  departure={dep}
                  showIcon={isLirrMode ? false : (iconVisibility[dep.routeId] || false)}
                  showAbbreviation={isLirrMode ? (headsignAbbrevVisibility[dep.headsign] || false) : false}
                  abbreviation={isLirrMode ? (HEADSIGN_ABBREVIATIONS[dep.headsign] || '') : ''}
                  {isLirrMode}
                />
              {/each}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</div>

<style>
  .timetable {
    overflow-x: auto;
  }

  .timetable-table {
    border-collapse: collapse;
    width: 100%;
    min-width: 400px;
  }

  .timetable-row {
    border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  }

  .hour-cell {
    width: 48px;
    min-width: 48px;
    padding: 4px 8px;
    font-size: 1rem;
    font-weight: 700;
    color: #000;
    text-align: center;
    vertical-align: top;
    border-right: 2px solid #ddd;
  }

  .minutes-cell {
    padding: 4px 8px;
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
    vertical-align: top;
  }

  .no-service {
    padding: 24px 16px;
    color: #555;
    font-size: 1rem;
  }
</style>
