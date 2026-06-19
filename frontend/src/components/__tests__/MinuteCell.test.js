import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/svelte';
import MinuteCell from '../MinuteCell.svelte';

const departure = {
  minute: 5,
  routeId: 'LI_1',
  routeColor: '009B3A',
  routeTextColor: 'FFFFFF',
  routeShortName: 'LI',
  headsign: 'Babylon',
};

describe('MinuteCell.miniPill.hasBackground', () => {
  it('when showAbbreviation=true and abbreviation="BAB", .headsign-abbr has a non-empty backgroundColor', () => {
    const { container } = render(MinuteCell, {
      props: { departure, showAbbreviation: true, abbreviation: 'BAB', isLirrMode: true },
    });
    const abbr = container.querySelector('.headsign-abbr');
    expect(abbr).not.toBeNull();
    expect(abbr.style.backgroundColor).not.toBe('');
  });
});

describe('MinuteCell.miniPill.usesRouteColor', () => {
  it('background color of .headsign-abbr matches the departure routeColor', () => {
    const { container } = render(MinuteCell, {
      props: { departure, showAbbreviation: true, abbreviation: 'BAB', isLirrMode: true },
    });
    const abbr = container.querySelector('.headsign-abbr');
    expect(abbr).not.toBeNull();
    // routeColor is '009B3A' → rgb(0, 155, 58)
    expect(abbr.style.backgroundColor).toBe('rgb(0, 155, 58)');
  });
});
