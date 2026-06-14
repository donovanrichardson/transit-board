<script>
  import { onMount } from 'svelte';
  import { fetchSchedule } from './lib/api.js';
  import Header from './components/Header.svelte';
  import DatePicker from './components/DatePicker.svelte';
  import HeadsignFilter from './components/HeadsignFilter.svelte';
  import Timetable from './components/Timetable.svelte';
  import LoadingIndicator from './components/LoadingIndicator.svelte';

  // Extract stop ID from URL path: /stop/{stopId}
  function getStopIdFromPath() {
    const match = window.location.pathname.match(/^\/stop\/(.+)$/);
    return match ? decodeURIComponent(match[1]) : null;
  }

  function todayString() {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  const stopId = getStopIdFromPath();
  let date = todayString();

  let loading = false;
  let error = null;
  let data = null;

  let selectedHeadsigns = new Set();

  $: routes = data ? data.routes : [];
  $: agencyColor = data ? data.agencyColor : null;
  $: departures = data ? data.departures : [];
  $: headsigns = data ? data.headsigns : [];
  $: stop = data ? data.stop : null;

  // Base color for loading indicator
  $: baseColor = (() => {
    if (!data) return '#CCCCCC';
    const routeIds = [...new Set(departures.map((d) => d.routeId))];
    if (routeIds.length === 1) {
      const route = routes.find((r) => r.id === routeIds[0]);
      if (route && route.color) return '#' + route.color;
    }
    if (agencyColor) return '#' + agencyColor;
    return '#CCCCCC';
  })();

  async function loadSchedule() {
    if (!stopId) {
      error = 'No stop ID in URL.';
      return;
    }
    loading = true;
    error = null;
    try {
      data = await fetchSchedule(stopId, date);
      // Initialize all headsigns as selected
      selectedHeadsigns = new Set(data.headsigns);
    } catch (e) {
      const status = e.message;
      if (status === '404') {
        error = 'Stop not found.';
      } else if (status === '400') {
        error = 'Invalid request.';
      } else {
        error = 'Could not load schedule. Try again later.';
      }
    } finally {
      loading = false;
    }
  }

  onMount(() => {
    loadSchedule();
  });

  function onDateChange(e) {
    date = e.detail.date;
    loadSchedule();
  }

  function onHeadsignChange(e) {
    selectedHeadsigns = e.detail.selected;
  }
</script>

<div class="app">
  {#if stopId}
    <Header
      {stop}
      {headsigns}
      {selectedHeadsigns}
      {departures}
    />

    <DatePicker {date} on:change={onDateChange} />

    {#if headsigns.length > 0}
      <HeadsignFilter
        {headsigns}
        selected={selectedHeadsigns}
        on:change={onHeadsignChange}
      />
    {/if}

    {#if loading}
      <LoadingIndicator color={baseColor} />
    {:else if error}
      <p class="error-message">{error}</p>
    {:else if data}
      <Timetable
        {departures}
        {routes}
        {agencyColor}
        {selectedHeadsigns}
      />
    {/if}
  {:else}
    <p class="error-message">No stop ID specified. Navigate to /stop/&lt;stopId&gt;.</p>
  {/if}
</div>

<style>
  :global(*) {
    box-sizing: border-box;
  }

  :global(body) {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    color: #000;
    background: #fff;
  }

  .app {
    max-width: 960px;
    margin: 0 auto;
  }

  .error-message {
    padding: 24px 16px;
    color: #cc0000;
    font-size: 1rem;
  }
</style>
