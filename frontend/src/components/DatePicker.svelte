<script>
  import { createEventDispatcher } from 'svelte';
  import { t } from '../lib/i18n.js';

  export let date = '';
  export let lang = 'en';

  const dispatch = createEventDispatcher();

  function onChange(e) {
    dispatch('change', { date: e.target.value });
  }
</script>

<div class="date-picker">
  <label for="schedule-date">{t('date', lang)}</label>
  <input
    id="schedule-date"
    type="date"
    value={date}
    on:change={onChange}
  />
</div>

<style>
  .date-picker {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 16px;
  }

  @media print {
    .date-picker {
      display: none;
    }
  }

  label {
    font-size: 0.9rem;
    font-weight: 500;
  }

  input[type="date"] {
    padding: 4px 8px;
    border: 1px solid #ccc;
    border-radius: 4px;
    font-size: 0.9rem;
  }
</style>
