import { describe, it, expect } from 'vitest';
import {
  groupByHour,
  computeRowColor,
  computeRouteIconVisibility,
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
