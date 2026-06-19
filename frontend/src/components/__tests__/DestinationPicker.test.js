import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/svelte';
import DestinationPicker from '../DestinationPicker.svelte';

const headsigns = ['Babylon', 'Jamaica', 'Penn Station', 'Ronkonkoma'];
const destinations = ['Merillon Avenue', 'New Hyde Park', 'Penn Station'];

describe('DestinationPicker.dropdown.hiddenWhenEmpty', () => {
  it('no .destination-dropdown when searchText is empty', () => {
    const { container } = render(DestinationPicker, { props: { headsigns, destinations } });
    expect(container.querySelector('.destination-dropdown')).toBeNull();
  });
});

describe('DestinationPicker.dropdown.visibleWhenTyping', () => {
  it('shows .destination-dropdown with filtered items when searchText is non-empty', async () => {
    const { container, getByPlaceholderText } = render(DestinationPicker, {
      props: { headsigns },
    });

    const input = getByPlaceholderText('Search destinations...');
    await fireEvent.input(input, { target: { value: 'Bab' } });

    const dropdown = container.querySelector('.destination-dropdown');
    expect(dropdown).not.toBeNull();
    expect(dropdown.textContent).toContain('Babylon');
    expect(dropdown.textContent).not.toContain('Jamaica');
  });
});

describe('DestinationPicker.dropdown.noResults', () => {
  it('shows "No results" in dropdown when search matches nothing', async () => {
    const { container, getByPlaceholderText } = render(DestinationPicker, {
      props: { headsigns, destinations },
    });

    const input = getByPlaceholderText('Search destinations...');
    await fireEvent.input(input, { target: { value: 'zzz' } });

    const dropdown = container.querySelector('.destination-dropdown');
    expect(dropdown).not.toBeNull();
    expect(dropdown.textContent).toContain('No results');
  });
});

describe('DestinationPicker.dropdown.selectItem', () => {
  it('clicking a dropdown item fires change with mode:specific and clears the dropdown', async () => {
    const events = [];
    const { container, getByPlaceholderText, component } = render(DestinationPicker, {
      props: { headsigns },
    });
    component.$on('change', (e) => events.push(e.detail));

    const input = getByPlaceholderText('Search destinations...');
    await fireEvent.input(input, { target: { value: 'Bab' } });

    const btn = container.querySelector('.dropdown-item');
    expect(btn).not.toBeNull();
    await fireEvent.click(btn);

    expect(events).toHaveLength(1);
    expect(events[0]).toEqual({ mode: 'specific', headsign: 'Babylon' });
    expect(container.querySelector('.destination-dropdown')).toBeNull();
  });
});

describe('DestinationPicker.dropdown.clearOnDirectionSelect', () => {
  it('clicking Inbound button clears searchText and closes dropdown', async () => {
    const { container, getByPlaceholderText, getByText } = render(DestinationPicker, {
      props: { headsigns, destinations },
    });

    const input = getByPlaceholderText('Search destinations...');
    await fireEvent.input(input, { target: { value: 'Bab' } });
    expect(container.querySelector('.destination-dropdown')).not.toBeNull();

    await fireEvent.click(getByText('Inbound'));
    expect(container.querySelector('.destination-dropdown')).toBeNull();
  });
});
