import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/svelte';
import Header from '../Header.svelte';

const stop = { id: 'LI_102', name: 'Mineola', direction: null, parentId: null, siblingStopIds: [] };

// Make headsigns set include the headsign under test so pills are rendered
const selectedHeadsigns = new Set(['Penn Station', 'Flushing-Main St', 'Jamaica']);

describe('Header.lirrHeadsignColorDominantRoute', () => {
  it('uses route color when one routeId has >2/3 of departures for that headsign', () => {
    // 3 departures on LI_1 (route color 009B3A), 1 departure on LI_2
    // 3 > (2/3)*4 = 2.67 → dominant
    const departures = [
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Penn Station', routeId: 'LI_2', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
    ];

    const { container } = render(Header, {
      props: { stop, headsigns: ['Penn Station'], selectedHeadsigns, departures, isLirrMode: true },
    });

    const pill = container.querySelector('.headsign-pill');
    expect(pill).not.toBeNull();
    expect(pill.style.backgroundColor).toBe('rgb(0, 155, 58)'); // #009B3A
  });
});

describe('Header.lirrHeadsignColorCityTerminalFallback', () => {
  it('uses #4D5357 when no dominant route but >=2/3 departures have city-terminal headsign', () => {
    // 2 departures on LI_1, 2 on LI_2 → no dominant route (2 = (2/3)*3? No, total=4, 2/(4) = 50% < 2/3)
    // headsign is 'Penn Station' which is a city terminal
    // Penn Station departures: all 4 → 4 >= (2/3)*4 = 2.67 → city terminal fallback
    // But we need NO dominant route first. With 2 on LI_1 and 2 on LI_2 for headsign 'Penn Station':
    // topCount = 2, total = 4, 2 > (2/3)*4=2.67 → FALSE → not dominant
    // city terminal check: all 4 have headsign 'Penn Station' which is in CITY_TERMINALS → 4 >= 2.67 → TRUE
    const departures = [
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Penn Station', routeId: 'LI_2', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
      { headsign: 'Penn Station', routeId: 'LI_2', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
    ];

    const { container } = render(Header, {
      props: { stop, headsigns: ['Penn Station'], selectedHeadsigns, departures, isLirrMode: true },
    });

    const pill = container.querySelector('.headsign-pill');
    expect(pill).not.toBeNull();
    expect(pill.style.backgroundColor).toBe('rgb(77, 83, 87)'); // #4D5357
  });
});

describe('Header.lirrHeadsignColorMtaBlueFallback', () => {
  it('uses #0039A6 when no dominant route and <2/3 city-terminal headsigns', () => {
    // headsign is 'Babylon' (not a city terminal)
    // 2 on LI_1, 2 on LI_2 → not dominant
    // 0 city terminal departures → 0 < 2.67 → MTA blue
    const departures = [
      { headsign: 'Babylon', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Babylon', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Babylon', routeId: 'LI_2', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
      { headsign: 'Babylon', routeId: 'LI_2', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
    ];

    const { container } = render(Header, {
      props: {
        stop,
        headsigns: ['Babylon'],
        selectedHeadsigns: new Set(['Babylon']),
        departures,
        isLirrMode: true,
      },
    });

    const pill = container.querySelector('.headsign-pill');
    expect(pill).not.toBeNull();
    expect(pill.style.backgroundColor).toBe('rgb(0, 57, 166)'); // #0039A6
  });
});

describe('Header.nonLirrHeadsignColorUnchanged', () => {
  it('in non-LIRR mode uses plurality pick (no 2/3 threshold)', () => {
    // 3 on route color FF0000, 2 on 00FF00 → plurality is FF0000
    // even though 3 out of 5 = 60% < 66.7%, in non-LIRR mode it still picks the plurality
    const departures = [
      { headsign: 'Flushing-Main St', routeId: 'R1', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
      { headsign: 'Flushing-Main St', routeId: 'R1', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
      { headsign: 'Flushing-Main St', routeId: 'R1', routeColor: 'FF0000', routeTextColor: 'FFFFFF' },
      { headsign: 'Flushing-Main St', routeId: 'R2', routeColor: '00FF00', routeTextColor: '000000' },
      { headsign: 'Flushing-Main St', routeId: 'R2', routeColor: '00FF00', routeTextColor: '000000' },
    ];

    const { container } = render(Header, {
      props: {
        stop: { id: 'MTA_1', name: 'Times Sq', direction: null, parentId: null, siblingStopIds: [] },
        headsigns: ['Flushing-Main St'],
        selectedHeadsigns: new Set(['Flushing-Main St']),
        departures,
        isLirrMode: false,
      },
    });

    const pill = container.querySelector('.headsign-pill');
    expect(pill).not.toBeNull();
    expect(pill.style.backgroundColor).toBe('rgb(255, 0, 0)'); // #FF0000 (plurality)
  });
});
