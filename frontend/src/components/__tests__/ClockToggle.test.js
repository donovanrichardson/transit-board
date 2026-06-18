import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/svelte';
import ClockToggle from '../ClockToggle.svelte';

describe('ClockToggle', () => {
  it('renders 12h and 24h buttons', () => {
    const { getByText } = render(ClockToggle, { props: { clockMode: '12h' } });
    expect(getByText('12h')).not.toBeNull();
    expect(getByText('24h')).not.toBeNull();
  });

  it('active button is the one matching clockMode', () => {
    const { getByText } = render(ClockToggle, { props: { clockMode: '12h' } });
    expect(getByText('12h').classList.contains('active')).toBe(true);
    expect(getByText('24h').classList.contains('active')).toBe(false);
  });

  it('active button updates when clockMode is 24h', () => {
    const { getByText } = render(ClockToggle, { props: { clockMode: '24h' } });
    expect(getByText('24h').classList.contains('active')).toBe(true);
    expect(getByText('12h').classList.contains('active')).toBe(false);
  });

  it('clicking 24h button dispatches change event with clockMode "24h"', async () => {
    const events = [];
    const { getByText, component } = render(ClockToggle, { props: { clockMode: '12h' } });
    component.$on('change', (e) => events.push(e.detail));
    await fireEvent.click(getByText('24h'));
    expect(events).toHaveLength(1);
    expect(events[0].clockMode).toBe('24h');
  });

  it('clicking 12h button dispatches change event with clockMode "12h"', async () => {
    const events = [];
    const { getByText, component } = render(ClockToggle, { props: { clockMode: '24h' } });
    component.$on('change', (e) => events.push(e.detail));
    await fireEvent.click(getByText('12h'));
    expect(events).toHaveLength(1);
    expect(events[0].clockMode).toBe('12h');
  });
});
