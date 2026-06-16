import { describe, it, expect } from 'vitest';
import { isLirr, CITY_TERMINALS, HEADSIGN_ABBREVIATIONS } from '../lirr.js';

describe('lirr.isLirr', () => {
  it('returns true for LI_ prefixed stop id', () => {
    expect(isLirr('LI_102')).toBe(true);
  });

  it('returns false for non-LIRR stop id', () => {
    expect(isLirr('MTA NYCT_725S')).toBe(false);
  });
});

describe('lirr.cityTerminals', () => {
  it('includes all 7 expected city terminal names', () => {
    const expected = [
      'Penn Station',
      'Grand Central',
      'Atlantic Terminal',
      'Jamaica',
      'Woodside',
      'Hunterspoint Avenue',
      'Long Island City',
    ];
    for (const name of expected) {
      expect(CITY_TERMINALS).toContain(name);
    }
    expect(CITY_TERMINALS).toHaveLength(7);
  });
});

describe('lirr.HEADSIGN_ABBREVIATIONS', () => {
  it('maps Penn Station to NYP', () => {
    expect(HEADSIGN_ABBREVIATIONS['Penn Station']).toBe('NYP');
  });

  it('maps Grand Central to GCT', () => {
    expect(HEADSIGN_ABBREVIATIONS['Grand Central']).toBe('GCT');
  });
});
