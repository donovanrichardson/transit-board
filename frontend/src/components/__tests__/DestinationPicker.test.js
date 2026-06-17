import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/svelte';
import DestinationPicker from '../DestinationPicker.svelte';

const headsigns = ['Babylon', 'Jamaica', 'Penn Station', 'Ronkonkoma'];
const destinations = ['Merillon Avenue', 'New Hyde Park', 'Penn Station'];

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
      props: { headsigns, destinations },
    });

    const input = getByPlaceholderText('Search destinations...');
    await fireEvent.input(input, { target: { value: 'Penn' } });

    expect(queryByText('Penn Station')).toBeInTheDocument();
    expect(queryByText('Merillon Avenue')).not.toBeInTheDocument();
    expect(queryByText('New Hyde Park')).not.toBeInTheDocument();
  });
});

describe('DestinationPicker.mutuallyExclusive', () => {
  it('selecting a specific destination deactivates direction buttons', async () => {
    const { getByText } = render(DestinationPicker, { props: { headsigns, destinations } });

    const allInbound = getByText('All Inbound');
    expect(allInbound).toHaveClass('active');

    const pennBtn = getByText('Penn Station');
    await fireEvent.click(pennBtn);

    expect(allInbound).not.toHaveClass('active');
    expect(getByText('All Outbound')).not.toHaveClass('active');
    expect(pennBtn).toHaveClass('active');
  });
});

describe('DestinationPicker.displaysDestinationsNotHeadsigns', () => {
  it('renders destinations prop in list, not headsigns', () => {
    const { queryByText } = render(DestinationPicker, {
      props: { headsigns, destinations },
    });

    // destinations should appear
    expect(queryByText('Merillon Avenue')).toBeInTheDocument();
    expect(queryByText('New Hyde Park')).toBeInTheDocument();
    expect(queryByText('Penn Station')).toBeInTheDocument();

    // headsigns that are NOT in destinations should not appear
    expect(queryByText('Babylon')).not.toBeInTheDocument();
    expect(queryByText('Jamaica')).not.toBeInTheDocument();
    expect(queryByText('Ronkonkoma')).not.toBeInTheDocument();
  });
});

describe('DestinationPicker.selectingDestinationDispatchesCorrectEvent', () => {
  it('dispatches { mode: specific, headsign: destinationName } when destination clicked', async () => {
    const events = [];
    const { getByText, component } = render(DestinationPicker, {
      props: { headsigns, destinations },
    });
    component.$on('change', (e) => events.push(e.detail));

    await fireEvent.click(getByText('Penn Station'));

    expect(events).toHaveLength(1);
    expect(events[0]).toEqual({ mode: 'specific', headsign: 'Penn Station' });
  });
});

describe('DestinationPicker.inboundOutboundUnchanged', () => {
  it('inbound button dispatches { mode: inbound }', async () => {
    const events = [];
    const { getByText, component } = render(DestinationPicker, {
      props: { headsigns, destinations },
    });
    component.$on('change', (e) => events.push(e.detail));

    await fireEvent.click(getByText('All Outbound'));
    expect(events[0]).toMatchObject({ mode: 'outbound' });

    await fireEvent.click(getByText('All Inbound'));
    expect(events[1]).toMatchObject({ mode: 'inbound' });
  });
});
