/**
 * Returns true if the stopId belongs to LIRR (prefixed with "LI_").
 *
 * @param {string} stopId
 * @returns {boolean}
 */
export function isLirr(stopId) {
  return typeof stopId === 'string' && stopId.startsWith('LI_');
}

/**
 * City terminal stop names used for baseColor computation on inbound rows.
 */
export const CITY_TERMINALS = [
  'Penn Station',
  'Grand Central',
  'Atlantic Terminal',
  'Jamaica',
  'Woodside',
  'Hunterspoint Avenue',
  'Long Island City',
];

/**
 * Abbreviation map for LIRR headsigns displayed in MinuteCell.
 */
export const HEADSIGN_ABBREVIATIONS = {
  'Amagansett': 'AGT',
  'Atlantic Terminal': 'ATL',
  'Babylon': 'BAB',
  'Far Rockaway': 'FRY',
  'Floral Park': 'FPK',
  'Freeport': 'FPT',
  'Grand Central': 'GCT',
  'Great Neck': 'GNK',
  'Greenport': 'GPT',
  'Hampton Bays': 'HBY',
  'Hempstead': 'HEM',
  'Hicksville': 'HVL',
  'Hunterspoint Avenue': 'HPA',
  'Huntington': 'HUN',
  'Jamaica': 'JAM',
  'Long Beach': 'LBH',
  'Long Island City': 'LIC',
  'Massapequa': 'MQA',
  'Montauk': 'MTK',
  'Oyster Bay': 'OBY',
  'Patchogue': 'PGE',
  'Penn Station': 'NYP',
  'Port Jefferson': 'PJN',
  'Port Washington': 'PWS',
  'Riverhead': 'RHD',
  'Ronkonkoma': 'RON',
  'Seaford': 'SFD',
  'Shinnecock Hills': 'SHC',
  'Smithtown': 'STN',
  'Southampton': 'SHN',
  'Speonk': 'SPK',
  'Wantagh': 'WGH',
  'West Hempstead': 'WHD',
  // Bus variants — same abbr as base
  'Huntington (Bus)': 'HUN',
  'Montauk (Bus)': 'MTK',
  'Patchogue (Bus)': 'PGE',
  'Port Jefferson (Bus)': 'PJN',
  'Smithtown (Bus)': 'STN',
  'Southampton (Bus)': 'SHN',
};
