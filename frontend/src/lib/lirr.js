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

const BUS_SUFFIX_RE = /^(.*) \(Bus\)$/;

/**
 * Returns the abbreviation for a LIRR headsign, or undefined if unresolved.
 *
 * Resolution order:
 *   1. Explicit entry in headsign-abbreviations.json wins.
 *   2. If headsign ends with " (Bus)", look up the base station name.
 *   3. Otherwise undefined.
 *
 * @param {string} headsign
 * @returns {string|undefined}
 */
export function getHeadsignAbbreviation(headsign) {
  if (Object.prototype.hasOwnProperty.call(HEADSIGN_ABBREVIATIONS, headsign)) {
    return HEADSIGN_ABBREVIATIONS[headsign];
  }
  const match = BUS_SUFFIX_RE.exec(headsign);
  if (match) {
    const base = match[1];
    if (Object.prototype.hasOwnProperty.call(HEADSIGN_ABBREVIATIONS, base)) {
      return HEADSIGN_ABBREVIATIONS[base];
    }
  }
  return undefined;
}
