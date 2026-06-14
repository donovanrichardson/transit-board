import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/svelte';
import HeadsignFilter from '../HeadsignFilter.svelte';

describe('HeadsignFilter.allSelectedByDefault', () => {
  it('all checkboxes are checked on mount', () => {
    const headsigns = ['34 St-Hudson Yards', 'Flushing-Main St'];
    const selected = new Set(headsigns);

    const { getAllByRole } = render(HeadsignFilter, {
      props: { headsigns, selected },
    });

    const checkboxes = getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(2);
    checkboxes.forEach((cb) => {
      expect(cb).toBeChecked();
    });
  });
});

describe('HeadsignFilter.preventEmptySelection', () => {
  it('unchecking the last checkbox re-checks it and shows inline message', async () => {
    const headsigns = ['34 St-Hudson Yards'];
    const selected = new Set(headsigns);

    const { getByRole, findByText } = render(HeadsignFilter, {
      props: { headsigns, selected },
    });

    const checkbox = getByRole('checkbox');
    expect(checkbox).toBeChecked();

    // Try to uncheck the only checkbox
    await fireEvent.click(checkbox);

    // Should still be checked
    expect(checkbox).toBeChecked();

    // Should show inline message
    const message = await findByText('At least one destination must be selected.');
    expect(message).toBeInTheDocument();
  });
});
