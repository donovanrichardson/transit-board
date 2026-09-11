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

import headsignAbbreviations from './headsign-abbreviations.json';

/**
 * Abbreviation map for LIRR headsigns displayed in MinuteCell.
 */
export const HEADSIGN_ABBREVIATIONS = headsignAbbreviations;
