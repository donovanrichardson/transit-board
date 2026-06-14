<script>
  export let color = '#CCCCCC';
</script>

<div class="loading-wrapper">
  <div class="spinner" style="border-left-color: {color};"></div>
</div>

<style>
  .loading-wrapper {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 48px;
  }

  .spinner {
    width: 40px;
    height: 40px;
    border: 4px solid transparent;
    border-left-color: #CCCCCC;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
