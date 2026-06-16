<script>
  export let stops = [];
  export let loading = false;
  export let error = null;

  let searchText = '';

  $: filteredStops = stops.filter((s) =>
    s.name.toLowerCase().includes(searchText.toLowerCase())
  );
</script>

<div class="home-page">
  <header class="home-header">
    <h1 class="home-title">Transit Board</h1>
    <p class="home-subtitle">Select your origin station</p>
  </header>

  <div class="search-area">
    <input
      type="text"
      placeholder="Search stations..."
      bind:value={searchText}
      on:input
    />
  </div>

  {#if loading}
    <p class="status-message">Loading...</p>
  {:else if error}
    <p class="status-message error-message">Could not load stations. Try again later.</p>
  {:else if filteredStops.length === 0}
    <p class="status-message">No stations found.</p>
  {:else}
    <ul class="station-list">
      {#each filteredStops as stop}
        <li class="station-item">
          <a href="/stop/{stop.id}" class="station-link">{stop.name}</a>
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
