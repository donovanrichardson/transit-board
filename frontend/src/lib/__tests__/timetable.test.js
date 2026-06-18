import { describe, it, expect } from 'vitest';
import {
  groupByHour,
  computeRowColor,
  computeRouteIconVisibility,
  computeHeadsignAbbreviationVisibility,
  filterByDirection,
  filterDepartures,
  formatHourLabel,
} from '../timetable.js';

describe('timetable.groupByHour', () => {
  it('groups departures by hour', () => {
    const departures = [
      { hour: 5, minute: 10, routeId: 'A', headsign: 'X' },
      { hour: 5, minute: 20, routeId: 'A', headsign: 'X' },
      { hour: 6, minute: 5, routeId: 'A', headsign: 'X' },
      { hour: 6, minute: 30, routeId: 'A', headsign: 'X' },
      { hour: 25, minute: 15, routeId: 'A', headsign: 'X' },
    ];

    const result = groupByHour(departures);

    expect(result).toHaveProperty('5');
    expect(result).toHaveProperty('6');
    expect(result).toHaveProperty('25');
    expect(result['5']).toHaveLength(2);
    expect(result['6']).toHaveLength(2);
    expect(result['25']).toHaveLength(1);
  });

  it('returns empty object for empty departures', () => {
    expect(groupByHour([])).toEqual({});
  });
});

describe('timetable.computeRowColor', () => {
  it('single route color odd hour returns rgba tint', () => {
    // route color "0039A6" = rgb(0, 57, 166)
    const color = computeRowColor('0039A6', null, 5);
    expect(color).toBe('rgba(0,57,166,0.12)');
  });

  it('single route color even hour returns white', () => {
    const color = computeRowColor('0039A6', null, 6);
    expect(color).toBe('#FFFFFF');
  });

  it('null route color falls back to agencyColor for odd hour', () => {
    const color = computeRowColor(null, 'FF0000', 3);
    expect(color).toBe('rgba(255,0,0,0.12)');
  });

  it('null route color and null agencyColor falls back to CCCCCC for odd hour', () => {
    const color = computeRowColor(null, null, 1);
    expect(color).toBe('rgba(204,204,204,0.12)');
  });

  it('even hour is always white regardless of color', () => {
    expect(computeRowColor('FF0000', null, 0)).toBe('#FFFFFF');
    expect(computeRowColor('FF0000', null, 24)).toBe('#FFFFFF');
    expect(computeRowColor('FF0000', null, 26)).toBe('#FFFFFF');
  });
});

describe('timetable.routeIconVisibility - dominant route (>2/3)', () => {
  it('dominant route cells show no icon; minority cells show icon', () => {
    // Route A: 7 departures (70%), Route B: 3 departures (30%)
    // total=10, maxCount=7, 7 > (2/3)*10 = 6.67 → A is dominant
    const departures = [
      ...Array(7).fill({ routeId: 'A' }),
      ...Array(3).fill({ routeId: 'B' }),
    ];

    const result = computeRouteIconVisibility(departures);

    expect(result['A']).toBe(false);  // dominant route: no icon
    expect(result['B']).toBe(true);   // minority route: show icon
  });
});

describe('timetable.routeIconVisibilityAllShown - no dominant route', () => {
  it('no route exceeds 2/3 means all routes show icons', () => {
    // Route A: 5, Route B: 5 → neither exceeds 2/3 of 10
    const departures = [
      ...Array(5).fill({ routeId: 'A' }),
      ...Array(5).fill({ routeId: 'B' }),
    ];

    const result = computeRouteIconVisibility(departures);

    expect(result['A']).toBe(true);
    expect(result['B']).toBe(true);
  });
});

describe('timetable.routeIconVisibilitySingleRoute - one route, no icons', () => {
  it('single route means no icons on any cell', () => {
    const departures = Array(5).fill({ routeId: 'A' });

    const result = computeRouteIconVisibility(departures);

    expect(result['A']).toBe(false);
  });
});

describe('timetable.computeHeadsignAbbreviationVisibility', () => {
  it('single unique headsign: all cells false (no abbreviation)', () => {
    const departures = Array(5).fill({ headsign: 'Penn Station' });
    const result = computeHeadsignAbbreviationVisibility(departures);
    for (const dep of departures) {
      expect(result['Penn Station']).toBe(false);
    }
  });

  it('dominant headsign >2/3: minority cells show abbreviation, dominant does not', () => {
    // Penn Station: 7, Jamaica: 3; total 10; 7 > 6.67 → Penn Station dominant
    const departures = [
      ...Array(7).fill({ headsign: 'Penn Station' }),
      ...Array(3).fill({ headsign: 'Jamaica' }),
    ];
    const result = computeHeadsignAbbreviationVisibility(departures);
    expect(result['Penn Station']).toBe(false);
    expect(result['Jamaica']).toBe(true);
  });

  it('no dominant headsign: all cells show abbreviation', () => {
    const departures = [
      ...Array(5).fill({ headsign: 'Penn Station' }),
      ...Array(5).fill({ headsign: 'Jamaica' }),
    ];
    const result = computeHeadsignAbbreviationVisibility(departures);
    expect(result['Penn Station']).toBe(true);
    expect(result['Jamaica']).toBe(true);
  });
});

describe('timetable.filterDepartures', () => {
  const nonLirrDeps = [
    { headsign: 'Flushing-Main St', routeId: 'R1' },
    { headsign: 'Jamaica', routeId: 'R2' },
    { headsign: null, routeId: 'R3' },
  ];

  it('filterDepartures.nonLirr.excludesDeselected', () => {
    const selected = new Set(['Flushing-Main St']);
    const result = filterDepartures(nonLirrDeps, {
      isLirrMode: false,
      lirrDestinationMode: 'inbound',
      lirrSelectedHeadsign: null,
      selectedHeadsigns: selected,
    });
    expect(result.some((d) => d.headsign === 'Jamaica')).toBe(false);
  });

  it('filterDepartures.nonLirr.includesSelected', () => {
    const selected = new Set(['Flushing-Main St']);
    const result = filterDepartures(nonLirrDeps, {
      isLirrMode: false,
      lirrDestinationMode: 'inbound',
      lirrSelectedHeadsign: null,
      selectedHeadsigns: selected,
    });
    expect(result.some((d) => d.headsign === 'Flushing-Main St')).toBe(true);
    // null headsign passes through
    expect(result.some((d) => d.headsign === null)).toBe(true);
  });

  const lirrDeps = [
    { headsign: 'Penn Station', directionId: '1', downstreamStops: ['Greenlawn', 'Penn Station'] },
    { headsign: 'Penn Station', directionId: '1', downstreamStops: ['Penn Station'] },
    { headsign: 'Babylon', directionId: '0', downstreamStops: ['Babylon'] },
    { headsign: 'Ronkonkoma', directionId: '0', downstreamStops: ['Greenlawn', 'Ronkonkoma'] },
  ];

  it('filterDepartures.lirr.inbound', () => {
    const result = filterDepartures(lirrDeps, {
      isLirrMode: true,
      lirrDestinationMode: 'inbound',
      lirrSelectedHeadsign: null,
      selectedHeadsigns: new Set(),
    });
    expect(result).toHaveLength(2);
    result.forEach((d) => expect(d.directionId).toBe('1'));
  });

  it('filterDepartures.lirr.outbound', () => {
    const result = filterDepartures(lirrDeps, {
      isLirrMode: true,
      lirrDestinationMode: 'outbound',
      lirrSelectedHeadsign: null,
      selectedHeadsigns: new Set(),
    });
    expect(result).toHaveLength(2);
    result.forEach((d) => expect(d.directionId).toBe('0'));
  });

  it('filterDepartures.lirr.specific', () => {
    const result = filterDepartures(lirrDeps, {
      isLirrMode: true,
      lirrDestinationMode: 'specific',
      lirrSelectedHeadsign: 'Greenlawn',
      selectedHeadsigns: new Set(),
    });
    expect(result).toHaveLength(2);
    result.forEach((d) => expect(d.downstreamStops).toContain('Greenlawn'));
  });
});

describe('timetable.formatHourLabel', () => {
  it('formatHourLabel 24h pads with zeros', () => {
    expect(formatHourLabel(5, '24h')).toBe('05');
    expect(formatHourLabel(25, '24h')).toBe('25');
  });

  it('formatHourLabel 12h midnight (0) → "12a"', () => {
    expect(formatHourLabel(0, '12h')).toBe('12a');
  });

  it('formatHourLabel 12h noon (12) → "12p"', () => {
    expect(formatHourLabel(12, '12h')).toBe('12p');
  });

  it('formatHourLabel 12h afternoon (13) → "1p"', () => {
    expect(formatHourLabel(13, '12h')).toBe('1p');
  });

  it('formatHourLabel 12h next-day hour (25) → "1a"', () => {
    expect(formatHourLabel(25, '12h')).toBe('1a');
  });

  it('formatHourLabel 12h next-day midnight (24) → "12a"', () => {
    expect(formatHourLabel(24, '12h')).toBe('12a');
  });
});

describe('timetable.filterByDirection', () => {
  const departures = [
    { headsign: 'Penn Station', directionId: '1' },
    { headsign: 'Penn Station', directionId: '1' },
    { headsign: 'Babylon', directionId: '0' },
    { headsign: 'Ronkonkoma', directionId: '0' },
  ];

  it('inbound: filters to directionId === "1"', () => {
    const result = filterByDirection(departures, '1');
    expect(result).toHaveLength(2);
    result.forEach((d) => expect(d.directionId).toBe('1'));
  });

  it('outbound: filters to directionId === "0"', () => {
    const result = filterByDirection(departures, '0');
    expect(result).toHaveLength(2);
    result.forEach((d) => expect(d.directionId).toBe('0'));
  });
});
