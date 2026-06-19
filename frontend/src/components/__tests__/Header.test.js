import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/svelte';
import Header from '../Header.svelte';

const stop = { id: 'LI_102', name: 'Mineola', direction: null, parentId: null, siblingStopIds: [] };
const departures = [
  { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
];

describe('Header.directionLabel.inbound', () => {
  it('shows "Inbound" when isLirrMode=true and lirrDestinationMode=inbound', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: true, lirrDestinationMode: 'inbound', lirrSelectedHeadsign: null },
    });
    const label = container.querySelector('.direction-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('Inbound');
  });
});

describe('Header.directionLabel.outbound', () => {
  it('shows "Outbound" when isLirrMode=true and lirrDestinationMode=outbound', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: true, lirrDestinationMode: 'outbound', lirrSelectedHeadsign: null },
    });
    const label = container.querySelector('.direction-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('Outbound');
  });
});

describe('Header.directionLabel.specific', () => {
  it('shows "to Babylon" when isLirrMode=true, mode=specific, headsign=Babylon', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: true, lirrDestinationMode: 'specific', lirrSelectedHeadsign: 'Babylon' },
    });
    const label = container.querySelector('.direction-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('to Babylon');
  });
});

describe('Header.directionLabel.specificNullHeadsign', () => {
  it('shows "Inbound" when mode=specific but lirrSelectedHeadsign=null', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: true, lirrDestinationMode: 'specific', lirrSelectedHeadsign: null },
    });
    const label = container.querySelector('.direction-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('Inbound');
  });
});

describe('Header.directionLabel.hiddenNonLirr', () => {
  it('no .direction-label when isLirrMode=false', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: false },
    });
    expect(container.querySelector('.direction-label')).toBeNull();
  });
});

describe('Header.border.onHeader', () => {
  it('<header> inline style contains border-bottom: 4px solid', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: true, lirrDestinationMode: 'inbound', lirrSelectedHeadsign: null },
    });
    const header = container.querySelector('header.timetable-header');
    expect(header).not.toBeNull();
    expect(header.style.borderBottom).toContain('4px solid');
  });
});

describe('Header.border.notOnStopName', () => {
  it('h1.stop-name has no border-bottom inline style', () => {
    const { container } = render(Header, {
      props: { stop, departures, isLirrMode: true, lirrDestinationMode: 'inbound', lirrSelectedHeadsign: null },
    });
    const h1 = container.querySelector('h1.stop-name');
    expect(h1).not.toBeNull();
    expect(h1.style.borderBottom).toBe('');
  });
});


const stopNonLirr = { id: 'S1', name: 'Test Stop', direction: null, parentId: null, siblingStopIds: [] };
const departuresBabylon = [
  { headsign: 'Babylon', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
];

describe('Header.pills.label', () => {
  it('renders a .pills-label element with text "Trips toward"', () => {
    const { container } = render(Header, {
      props: { stop: stopNonLirr, departures: departuresBabylon, isLirrMode: false },
    });
    const label = container.querySelector('.pills-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('Trips toward');
  });
});

describe('Header.pills.abbreviationAbovePill', () => {
  it('each pill entry has a .pill-abbreviation above it; for Babylon it shows "BAB"', () => {
    const { container } = render(Header, {
      props: { stop: stopNonLirr, departures: departuresBabylon, isLirrMode: false },
    });
    const abbr = container.querySelector('.pill-abbreviation');
    expect(abbr).not.toBeNull();
    expect(abbr.textContent.trim()).toBe('BAB');
  });
});

describe('Header.pills.unlabeledFirst', () => {
  it('headsign with no abbreviation renders "Unlabeled" and appears before labeled ones', () => {
    const deps = [
      { headsign: 'Babylon', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'UnknownPlace', routeId: 'LI_2', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
    ];
    const { container } = render(Header, {
      props: { stop: stopNonLirr, departures: deps, isLirrMode: false },
    });
    const abbrs = container.querySelectorAll('.pill-abbreviation');
    expect(abbrs.length).toBe(2);
    expect(abbrs[0].textContent.trim()).toBe('Unlabeled');
    expect(abbrs[1].textContent.trim()).toBe('BAB');
  });
});

describe('Header.pills.sortedAlphabetically', () => {
  it('two labeled headsigns render in A-Z abbreviation order', () => {
    const deps = [
      { headsign: 'Babylon', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
      { headsign: 'Amagansett', routeId: 'LI_2', routeColor: 'AA0000', routeTextColor: 'FFFFFF' },
    ];
    const { container } = render(Header, {
      props: { stop: stopNonLirr, departures: deps, isLirrMode: false },
    });
    const abbrs = container.querySelectorAll('.pill-abbreviation');
    expect(abbrs.length).toBe(2);
    // AGT (Amagansett) before BAB (Babylon)
    expect(abbrs[0].textContent.trim()).toBe('AGT');
    expect(abbrs[1].textContent.trim()).toBe('BAB');
  });
});

describe('pills.abbrevVisibility.suppressedShowsUnlabeled', () => {
  it('renders "Unlabeled" for Penn Station when headsignAbbrevVisibility marks it false', () => {
    const deps = [
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
    ];
    const { container } = render(Header, {
      props: {
        stop: stopNonLirr,
        departures: deps,
        isLirrMode: false,
        headsignAbbrevVisibility: { 'Penn Station': false },
      },
    });
    const abbr = container.querySelector('.pill-abbreviation');
    expect(abbr).not.toBeNull();
    expect(abbr.textContent.trim()).toBe('Unlabeled');
  });
});

describe('pills.abbrevVisibility.visibleShowsAbbr', () => {
  it('renders "BAB" for Babylon when headsignAbbrevVisibility marks it true', () => {
    const deps = [
      { headsign: 'Babylon', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
    ];
    const { container } = render(Header, {
      props: {
        stop: stopNonLirr,
        departures: deps,
        isLirrMode: false,
        headsignAbbrevVisibility: { 'Babylon': true },
      },
    });
    const abbr = container.querySelector('.pill-abbreviation');
    expect(abbr).not.toBeNull();
    expect(abbr.textContent.trim()).toBe('BAB');
  });
});

describe('pills.abbrevVisibility.missingKeyTreatedAsUnlabeled', () => {
  it('renders "Unlabeled" when headsignAbbrevVisibility is empty for any headsign', () => {
    const deps = [
      { headsign: 'Penn Station', routeId: 'LI_1', routeColor: '009B3A', routeTextColor: 'FFFFFF' },
    ];
    const { container } = render(Header, {
      props: {
        stop: stopNonLirr,
        departures: deps,
        isLirrMode: false,
        headsignAbbrevVisibility: {},
      },
    });
    const abbr = container.querySelector('.pill-abbreviation');
    expect(abbr).not.toBeNull();
    expect(abbr.textContent.trim()).toBe('Unlabeled');
  });
});
