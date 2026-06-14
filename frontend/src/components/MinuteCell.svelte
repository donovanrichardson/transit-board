<script>
  export let departure;
  export let showIcon = false;
</script>

<div class="minute-cell">
  <span class="minute-value">{String(departure.minute).padStart(2, '0')}</span>
  <span class="icon-area">
    {#if showIcon}
      <span
        class="route-icon"
        style="background-color: #{departure.routeColor || '999999'}; color: #{departure.routeTextColor || 'FFFFFF'};"
      >
        {departure.routeShortName || departure.routeId}
      </span>
    {:else}
      <!-- placeholder keeps cell size consistent -->
      <span class="icon-placeholder"></span>
    {/if}
  </span>
</div>

<style>
  .minute-cell {
    display: inline-flex;
    flex-direction: column;
    align-items: center;
    width: 40px;
    min-width: 40px;
    padding: 2px 1px;
    position: relative;
  }

  .minute-value {
    font-size: 0.9rem;
    font-weight: 500;
    line-height: 1.2;
  }

  .icon-area {
    height: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .route-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    font-size: 0.5rem;
    font-weight: 700;
    line-height: 1;
  }

  .icon-placeholder {
    display: inline-block;
    width: 14px;
    height: 14px;
  }
</style>
