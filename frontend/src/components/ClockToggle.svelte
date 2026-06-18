<script>
  import { createEventDispatcher } from 'svelte';

  export let clockMode = '12h';

  const dispatch = createEventDispatcher();

  function select(mode) {
    dispatch('change', { clockMode: mode });
  }
</script>

<div class="clock-toggle">
  <button
    class="clock-btn"
    class:active={clockMode === '12h'}
    on:click={() => select('12h')}
  >12h</button>
  <button
    class="clock-btn"
    class:active={clockMode === '24h'}
    on:click={() => select('24h')}
  >24h</button>
</div>

<style>
  .clock-toggle {
    display: flex;
    gap: 4px;
    padding: 6px 16px;
  }

  @media print {
    .clock-toggle {
      display: none;
    }
  }

  .clock-btn {
    padding: 3px 10px;
    border: 1px solid #ccc;
    border-radius: 4px;
    background: #fff;
    cursor: pointer;
    font-size: 0.85rem;
  }

  .clock-btn.active {
    background: #0039a6;
    color: #fff;
    border-color: #0039a6;
  }
</style>
