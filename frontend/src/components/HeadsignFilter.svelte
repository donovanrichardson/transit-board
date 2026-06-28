<script>
  import { createEventDispatcher, tick } from 'svelte';
  import { t } from '../lib/i18n.js';

  export let headsigns = [];
  export let selected = new Set();
  export let lang = 'en';

  const dispatch = createEventDispatcher();

  let errorMessage = '';

  // Internal checkbox states, indexed parallel to headsigns
  let checked = headsigns.map((h) => selected.has(h));

  // When headsigns prop changes, reset checked array
  $: checked = headsigns.map((h) => selected.has(h));

  async function handleChange(event, index) {
    const nowChecked = event.target.checked;

    if (!nowChecked) {
      // Attempting to uncheck
      const stillChecked = checked.filter((_, i) => i !== index && checked[i]);
      if (stillChecked.length === 0) {
        // Prevent unchecking: restore via DOM and reactive array
        event.target.checked = true;
        checked[index] = true;
        // Force re-assignment to trigger reactivity
        checked = [...checked];
        errorMessage = t('atLeastOneDestination', lang);
        return;
      }
    }

    errorMessage = '';
    checked[index] = nowChecked;

    const next = new Set();
    headsigns.forEach((h, i) => {
      if (checked[i]) next.add(h);
    });
    dispatch('change', { selected: next });
  }
</script>

<div class="headsign-filter">
  {#each headsigns as headsign, i}
    <label class="headsign-label">
      <input
        type="checkbox"
        checked={checked[i]}
        on:change={(e) => handleChange(e, i)}
      />
      <span class="headsign-text">{headsign}</span>
    </label>
  {/each}
  {#if errorMessage}
    <p class="filter-error">{errorMessage}</p>
  {/if}
</div>

<style>
  .headsign-filter {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    padding: 8px 0;
  }

  .headsign-label {
    display: flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
    font-size: 0.9rem;
  }

  .headsign-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 200px;
  }

  .filter-error {
    width: 100%;
    color: #cc0000;
    font-size: 0.85rem;
    margin: 4px 0 0;
  }
</style>
