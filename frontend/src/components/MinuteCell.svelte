<script>
  export let departure;
  export let showIcon = false;
  export let showAbbreviation = false;
  export let abbreviation = '';
  export let isLirrMode = false;
</script>

<div class="minute-cell" class:lirr={isLirrMode}>
  <span class="minute-value" style={departure.dstRepeat ? 'color: red' : ''}>{String(departure.minute).padStart(2, '0')}</span>
  <span class="icon-area">
    {#if isLirrMode}
      {#if showAbbreviation && abbreviation}
        <span
          class="headsign-abbr"
          style="background-color: #{departure.routeColor || '999'}; color: #{departure.routeTextColor || 'FFF'};"
        >{abbreviation}</span>
      {:else}
        <span class="icon-placeholder"></span>
      {/if}
    {:else if showIcon}
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
    align-self: stretch;
    width: 40px;
    min-width: 40px;
    padding: 2px 1px;
    position: relative;
  }

  .minute-cell.lirr {
    width: 46px;
    min-width: 46px;
  }

  .minute-value {
    font-size: 0.9rem;
    font-weight: 500;
    line-height: 1.2;
  }

  .icon-area {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
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

  .headsign-abbr {
    display: block;
    width: 100%;
    height: 100%;
    font-size: 0.45rem;
    font-weight: 700;
    line-height: 14px;
    text-align: center;
    border-radius: 2px;
  }
</style>
