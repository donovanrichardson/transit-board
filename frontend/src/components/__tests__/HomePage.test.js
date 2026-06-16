import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/svelte';
import HomePage from '../HomePage.svelte';

const mockStops = [
  { id: 'LI_102', name: 'Jamaica', stopCode: 'JAM' },
  { id: 'LI_200', name: 'Penn Station', stopCode: 'NYP' },
  { id: 'LI_300', name: 'Babylon', stopCode: 'BAB' },
];

beforeEach(() => {
  vi.resetAllMocks();
});

describe('HomePage.searchFiltersStations', () => {
  it('typing in search input filters the station list', async () => {
    const { getByPlaceholderText, queryByText } = render(HomePage, {
      props: { stops: mockStops },
    });

    const input = getByPlaceholderText('Search stations...');
    await fireEvent.input(input, { target: { value: 'bab' } });

    expect(queryByText('Babylon')).toBeInTheDocument();
    expect(queryByText('Jamaica')).not.toBeInTheDocument();
    expect(queryByText('Penn Station')).not.toBeInTheDocument();
  });
});

describe('HomePage.clickNavigates', () => {
  it('clicking a station navigates to /stop/{stopId}', async () => {
    const assignSpy = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { assign: assignSpy, href: '' },
      writable: true,
    });

    const { getByText } = render(HomePage, { props: { stops: mockStops } });

    const jamaicaLink = getByText('Jamaica');
    await fireEvent.click(jamaicaLink);

    // The link should navigate to /stop/LI_102
    expect(jamaicaLink.closest('a')).toHaveAttribute('href', '/stop/LI_102');
  });
});
