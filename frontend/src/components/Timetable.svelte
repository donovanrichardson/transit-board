<script>
  import MinuteCell from './MinuteCell.svelte';
  import { groupByHour, computeRowColor, computeRouteIconVisibility } from '../lib/timetable.js';

  export let departures = [];
  export let routes = [];
  export let agencyColor = null;
  export let selectedHeadsigns = new Set();

  $: filteredDepartures = departures.filter(
    (d) => !d.headsign || selectedHeadsigns.has(d.headsign)
  );

  // Determine base color for row tinting
  $: baseColor = (() => {
    const routeIds = [...new Set(filteredDepartures.map((d) => d.routeId))];
    if (routeIds.length === 1) {
      const route = routes.find((r) => r.id === routeIds[0]);
      if (route && route.color) return route.color;
    }
    return agencyColor || 'CCCCCC';
  })();

  $: iconVisibility = computeRouteIconVisibility(filteredDepartures);

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
                  showIcon={iconVisibility[dep.routeId] || false}
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
