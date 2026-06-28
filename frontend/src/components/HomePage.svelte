<script>
  import { jaStopName } from '../lib/locale.js';
  import { t, sortStops, normalizeKana } from '../lib/i18n.js';

  export let stops = [];
  export let loading = false;
  export let error = null;
  export let lang = 'en';

  let searchText = '';

  $: filteredStops = stops.filter((s) => {
    const jaName = lang === 'ja' ? jaStopName(s.id, s.name) : s.name;
    const query = normalizeKana(searchText.toLowerCase());
    return s.name.toLowerCase().includes(query) || normalizeKana(jaName).includes(query);
  });

  $: sortedStops = sortStops(filteredStops, lang, jaStopName);
</script>

<div class="home-page">
  <header class="home-header">
    <h1 class="home-title">{lang === 'ja' ? 'LIRR 発車時刻表' : 'LIRR Departure Board'}</h1>
    <p class="home-subtitle">{t('selectOriginStation', lang)}</p>
  </header>

  <div class="search-area">
    <input
      type="text"
      placeholder={t('searchStations', lang)}
      bind:value={searchText}
      on:input
    />
  </div>

  {#if loading}
    <p class="status-message">{t('loading', lang)}</p>
  {:else if error}
    <p class="status-message error-message">{error}</p>
  {:else if sortedStops.length === 0}
    <p class="status-message">{t('noStationsFound', lang)}</p>
  {:else}
    <ul class="station-list">
      {#each sortedStops as stop}
        <li class="station-item">
          <a href="/stop/{stop.id}" class="station-link">{lang === 'ja' ? jaStopName(stop.id, stop.name) : stop.name}</a>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .home-page {
    max-width: 600px;
    margin: 0 auto;
    padding: 24px 16px;
  }

  .home-header {
    margin-bottom: 24px;
    text-align: center;
  }

  .home-title {
    font-size: 2rem;
    font-weight: 700;
    margin: 0 0 8px;
  }

  .home-subtitle {
    font-size: 1rem;
    color: #555;
    margin: 0;
  }

  .search-area {
    margin-bottom: 16px;
  }

  .search-area input {
    width: 100%;
    padding: 8px 12px;
    font-size: 1rem;
    border: 1px solid #ccc;
    border-radius: 4px;
  }

  .station-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .station-item {
    border-bottom: 1px solid #e0e0e0;
  }

  .station-link {
    display: block;
    padding: 12px 4px;
    text-decoration: none;
    color: #000;
    font-size: 1rem;
  }

  .station-link:hover {
    background: #f5f5f5;
  }

  .status-message {
    padding: 16px 4px;
    color: #555;
    font-size: 1rem;
  }

  .error-message {
    color: #cc0000;
  }
</style>
