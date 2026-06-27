<script>
  import { createEventDispatcher } from 'svelte';
  import { jaHeadsign } from '../lib/locale.js';

  export let headsigns = [];
  export let destinations = [];
  export let lang = 'en';

  const dispatch = createEventDispatcher();

  // 'inbound' | 'outbound' | null (when specific destination is selected)
  let directionMode = 'inbound';
  // The selected specific headsign, or null
  let selectedHeadsign = null;

  let searchText = '';

  $: displayList = destinations.length > 0 ? destinations : headsigns;
  $: filteredHeadsigns = displayList.filter((h) => {
    const jaName = lang === 'ja' ? jaHeadsign(h, h) : null;
    const query = searchText.toLowerCase();
    return h.toLowerCase().includes(query) || (jaName && jaName.includes(query));
  });

  function selectDirection(dir) {
    directionMode = dir;
    selectedHeadsign = null;
    searchText = '';
    dispatch('change', { mode: dir, headsign: null });
  }

  function selectHeadsign(h) {
    directionMode = null;
    selectedHeadsign = h;
    searchText = '';
    dispatch('change', { mode: 'specific', headsign: h });
  }
</script>

<div class="destination-picker">
  <div class="direction-buttons">
    <button
      class="direction-btn"
      class:active={directionMode === 'inbound'}
      on:click={() => selectDirection('inbound')}
    >
      {lang === 'ja' ? '都心方面' : 'Inbound'}
    </button>
    <button
      class="direction-btn"
      class:active={directionMode === 'outbound'}
      on:click={() => selectDirection('outbound')}
    >
      {lang === 'ja' ? '郊外方面' : 'Outbound'}
    </button>
  </div>

  <div class="destination-search">
    <input
      type="text"
      placeholder="Search destinations..."
      bind:value={searchText}
      on:input
    />
    {#if searchText}
      <div class="destination-dropdown">
        {#if filteredHeadsigns.length === 0}
          <div class="dropdown-empty">No results</div>
        {/if}
        {#each filteredHeadsigns as headsign}
          <button class="dropdown-item" on:click={() => selectHeadsign(headsign)}>{lang === 'ja' ? jaHeadsign(headsign, headsign) : headsign}</button>
        {/each}
      </div>
    {/if}
  </div>
</div>

<style>
  .destination-picker {
    padding: 8px 16px;
    border-bottom: 1px solid #e0e0e0;
  }

  @media print {
    .destination-picker {
      display: none;
    }
  }

  .direction-buttons {
    display: flex;
    gap: 8px;
    margin-bottom: 8px;
  }

  .direction-btn {
    padding: 4px 12px;
    border: 1px solid #ccc;
    border-radius: 4px;
    background: #fff;
    cursor: pointer;
    font-size: 0.85rem;
  }

  .direction-btn.active {
    background: #0039a6;
    color: #fff;
    border-color: #0039a6;
  }

  .destination-search {
    margin-bottom: 8px;
    position: relative;
  }

  .destination-search input {
    width: 100%;
    padding: 4px 8px;
    font-size: 0.9rem;
    border: 1px solid #ccc;
    border-radius: 4px;
  }

  .destination-dropdown {
    position: absolute;
    top: 100%;
    left: 0;
    right: 0;
    z-index: 100;
    background: #fff;
    border: 1px solid #ccc;
    border-radius: 4px;
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.12);
  }

  .dropdown-item {
    display: block;
    width: 100%;
    padding: 6px 10px;
    border: none;
    background: none;
    cursor: pointer;
    font-size: 0.9rem;
    text-align: left;
  }

  .dropdown-item:hover {
    background: #f0f4ff;
  }

  .dropdown-empty {
    padding: 6px 10px;
    font-size: 0.9rem;
    color: #999;
  }
</style>
