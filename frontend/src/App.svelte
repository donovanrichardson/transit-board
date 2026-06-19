<script>
  import { onMount } from 'svelte';
  import { fetchSchedule, fetchStops } from './lib/api.js';
  import { isLirr } from './lib/lirr.js';
  import { filterDepartures, computeHeadsignAbbreviationVisibility } from './lib/timetable.js';
  import Header from './components/Header.svelte';
  import DatePicker from './components/DatePicker.svelte';
  import HeadsignFilter from './components/HeadsignFilter.svelte';
  import DestinationPicker from './components/DestinationPicker.svelte';
  import ClockToggle from './components/ClockToggle.svelte';
  import Timetable from './components/Timetable.svelte';
  import LoadingIndicator from './components/LoadingIndicator.svelte';
  import HomePage from './components/HomePage.svelte';

  // Determine page mode from URL
  function getStopIdFromPath() {
    const match = window.location.pathname.match(/^\/stop\/(.+)$/);
    return match ? decodeURIComponent(match[1]) : null;
  }

  function isHomePage() {
    return window.location.pathname === '/';
  }

  function todayString() {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  const onHome = isHomePage();
  const stopId = getStopIdFromPath();
  const lirrMode = stopId ? isLirr(stopId) : false;

  let date = todayString();

  // Stop page state
  let loading = false;
  let error = null;
  let data = null;

  let selectedHeadsigns = new Set();

  // LIRR destination picker state
  let lirrDestinationMode = 'inbound';
  let lirrSelectedHeadsign = null;

  // Clock mode state — read from localStorage, default '12h'
  let clockMode = (() => {
    try {
      return localStorage.getItem('transitBoard.clockMode') === '24h' ? '24h' : '12h';
    } catch (_) {
      return '12h';
    }
  })();

  $: routes = data ? data.routes : [];
  $: agencyColor = data ? data.agencyColor : null;
  $: departures = data ? data.departures : [];
  $: headsigns = data ? data.headsigns : [];
  $: destinations = data ? data.destinations : [];
  $: stop = data ? data.stop : null;

  $: filteredDepartures = filterDepartures(departures, { isLirrMode: lirrMode, lirrDestinationMode, lirrSelectedHeadsign, selectedHeadsigns });
  $: headsignAbbrevVisibility = computeHeadsignAbbreviationVisibility(filteredDepartures);

  // Home page state
  let homeLoading = false;
  let homeError = null;
  let homeStops = [];

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

  async function loadHomeStops() {
    homeLoading = true;
    homeError = null;
    try {
      const result = await fetchStops('LI');
      homeStops = result.stops || [];
    } catch (e) {
      homeError = 'Could not load stations. Try again later.';
    } finally {
      homeLoading = false;
    }
  }

  onMount(() => {
    if (onHome) {
      loadHomeStops();
    } else if (stopId) {
      loadSchedule();
    }
  });

  function onDateChange(e) {
    date = e.detail.date;
    loadSchedule();
  }

  function onHeadsignChange(e) {
    selectedHeadsigns = e.detail.selected;
  }

  function onDestinationChange(e) {
    const { mode, headsign } = e.detail;
    if (mode === 'specific') {
      lirrDestinationMode = 'specific';
      lirrSelectedHeadsign = headsign;
    } else {
      lirrDestinationMode = mode;
      lirrSelectedHeadsign = null;
    }
  }

  function onClockModeChange(e) {
    clockMode = e.detail.clockMode;
    try {
      localStorage.setItem('transitBoard.clockMode', clockMode);
    } catch (_) {
      // ignore
    }
  }
</script>

<div class="app">
  {#if onHome}
    <HomePage
      stops={homeStops}
      loading={homeLoading}
      error={homeError}
    />
  {:else if stopId}
    <h1 class="board-title">LIRR Departure Board</h1>
    <Header
      {stop}
      departures={filteredDepartures}
      isLirrMode={lirrMode}
      {lirrDestinationMode}
      {lirrSelectedHeadsign}
      {headsignAbbrevVisibility}
    />

    <div class="print-date">
      {(() => {
        const [year, month, day] = date.split('-').map(Number);
        return new Intl.DateTimeFormat('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(year, month - 1, day));
      })()}
    </div>

    <DatePicker {date} on:change={onDateChange} />

    <ClockToggle {clockMode} on:change={onClockModeChange} />

    {#if lirrMode}
      <DestinationPicker
        {headsigns}
        {destinations}
        on:change={onDestinationChange}
      />
    {:else if headsigns.length > 0}
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
        isLirrMode={lirrMode}
        {lirrDestinationMode}
        {lirrSelectedHeadsign}
        {clockMode}
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
    font-family: Helvetica, Arial, sans-serif;
    color: #000;
    background: #fff;
  }

  :global(#schedule-date) {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  }

  .app {
    max-width: 960px;
    margin: 0 auto;
  }

  .board-title {
    text-align: center;
    font-size: 1.5rem;
    font-weight: 700;
    margin: 16px 0 8px;
  }

  .error-message {
    padding: 24px 16px;
    color: #cc0000;
    font-size: 1rem;
  }

  .print-date {
    display: none;
    padding: 8px 16px;
    font-size: 1rem;
  }

  @media print {
    .print-date {
      display: block;
    }
  }
</style>
