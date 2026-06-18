<script>
  import { createEventDispatcher } from 'svelte';

  export let headsigns = [];
  export let destinations = [];

  const dispatch = createEventDispatcher();

  // 'inbound' | 'outbound' | null (when specific destination is selected)
  let directionMode = 'inbound';
  // The selected specific headsign, or null
  let selectedHeadsign = null;

  let searchText = '';

  $: displayList = destinations.length > 0 ? destinations : headsigns;
  $: filteredHeadsigns = displayList.filter((h) =>
    h.toLowerCase().includes(searchText.toLowerCase())
  );

  function selectDirection(dir) {
    directionMode = dir;
    selectedHeadsign = null;
    dispatch('change', { mode: dir, headsign: null });
  }

  function selectHeadsign(h) {
    directionMode = null;
    selectedHeadsign = h;
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
      All Inbound
    </button>
    <button
      class="direction-btn"
      class:active={directionMode === 'outbound'}
      on:click={() => selectDirection('outbound')}
    >
      All Outbound
    </button>
  </div>

  <div class="destination-search">
    <input
      type="text"
      placeholder="Search destinations..."
      bind:value={searchText}
      on:input
    />
  </div>

  <div class="destination-list">
    {#each filteredHeadsigns as headsign}
      <button
        class="destination-btn"
        class:active={selectedHeadsign === headsign}
        on:click={() => selectHeadsign(headsign)}
      >
        {headsign}
      </button>
    {/each}
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

  .direction-btn,
  .destination-btn {
    padding: 4px 12px;
    border: 1px solid #ccc;
    border-radius: 4px;
    background: #fff;
    cursor: pointer;
    font-size: 0.85rem;
  }

  .direction-btn.active,
  .destination-btn.active {
    background: #0039a6;
    color: #fff;
    border-color: #0039a6;
  }

  .destination-search {
    margin-bottom: 8px;
  }

  .destination-search input {
    width: 100%;
    padding: 4px 8px;
    font-size: 0.9rem;
    border: 1px solid #ccc;
    border-radius: 4px;
  }

  .destination-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
</style>
