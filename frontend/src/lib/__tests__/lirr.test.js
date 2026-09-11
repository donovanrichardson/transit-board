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

  it('has a defined entry for every live GTFS headsign', () => {
    const liveHeadsigns = [
      'Amagansett',
      'Atlantic Terminal',
      'Babylon',
      'Babylon (Bus)',
      'Central Islip',
      'Central Islip (Bus)',
      'Far Rockaway',
      'Farmingdale',
      'Floral Park',
      'Freeport',
      'Glen Cove (Bus)',
      'Grand Central',
      'Great Neck',
      'Greenport',
      'Greenport (Bus)',
      'Greenvale (Bus)',
      'Hampton Bays',
      'Hempstead',
      'Hicksville',
      'Hunterspoint Avenue',
      'Huntington',
      'Huntington (Bus)',
      'Jamaica',
      'Long Beach',
      'Long Island City',
      'Massapequa',
      'Mineola (Bus)',
      'Montauk',
      'Montauk (Bus)',
      'Oyster Bay',
      'Oyster Bay (Bus)',
      'Patchogue',
      'Patchogue (Bus)',
      'Penn Station',
      'Port Jefferson',
      'Port Jefferson (Bus)',
      'Port Washington',
      'Riverhead (Bus)',
      'Ronkonkoma',
      'Ronkonkoma (Bus)',
      'Seaford',
      'Shinnecock Hills',
      'Smithtown',
      'Smithtown (Bus)',
      'Southampton',
      'Southampton (Bus)',
      'Speonk',
      'Speonk (Bus)',
      'Wantagh',
      'West Hempstead',
    ];
    for (const headsign of liveHeadsigns) {
      expect(HEADSIGN_ABBREVIATIONS[headsign], headsign).toBeDefined();
    }
  });

  it('every (Bus) headsign value matches its base station value when base exists', () => {
    for (const key of Object.keys(HEADSIGN_ABBREVIATIONS)) {
      if (key.endsWith(' (Bus)')) {
        const baseKey = key.replace(' (Bus)', '');
        if (baseKey in HEADSIGN_ABBREVIATIONS) {
          expect(HEADSIGN_ABBREVIATIONS[key], key).toBe(HEADSIGN_ABBREVIATIONS[baseKey]);
        }
      }
    }
  });
});
