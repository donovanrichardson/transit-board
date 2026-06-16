import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/svelte';
import DestinationPicker from '../DestinationPicker.svelte';

const headsigns = ['Babylon', 'Jamaica', 'Penn Station', 'Ronkonkoma'];

describe('DestinationPicker.defaultsToAllInbound', () => {
  it('"All Inbound" button is active on mount', () => {
    const { getByText } = render(DestinationPicker, { props: { headsigns } });
    const btn = getByText('All Inbound');
    expect(btn).toHaveClass('active');
  });
});

describe('DestinationPicker.searchFiltersDestinations', () => {
  it('typing in search input filters destination list', async () => {
    const { getByPlaceholderText, queryByText } = render(DestinationPicker, {
      props: { headsigns },
    });

    const input = getByPlaceholderText('Search destinations...');
    await fireEvent.input(input, { target: { value: 'bab' } });

    expect(queryByText('Babylon')).toBeInTheDocument();
    expect(queryByText('Jamaica')).not.toBeInTheDocument();
    expect(queryByText('Penn Station')).not.toBeInTheDocument();
  });
});

describe('DestinationPicker.mutuallyExclusive', () => {
  it('selecting a specific destination deactivates direction buttons', async () => {
    const { getByText } = render(DestinationPicker, { props: { headsigns } });

    const allInbound = getByText('All Inbound');
    expect(allInbound).toHaveClass('active');

    const babylonBtn = getByText('Babylon');
    await fireEvent.click(babylonBtn);

    expect(allInbound).not.toHaveClass('active');
    expect(getByText('All Outbound')).not.toHaveClass('active');
    expect(babylonBtn).toHaveClass('active');
  });
});
