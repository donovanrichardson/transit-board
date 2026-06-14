/**
 * Groups an array of departure objects by their `hour` field.
 * Returns an object keyed by hour (as string), value is array of departures.
 */
export function groupByHour(departures) {
  const groups = {};
  for (const dep of departures) {
    const key = String(dep.hour);
    if (!groups[key]) groups[key] = [];
    groups[key].push(dep);
  }
  return groups;
}

/**
 * Parses a hex color string (without #) into [r, g, b].
 */
function hexToRgb(hex) {
  if (!hex || hex.length < 6) return null;
  const r = parseInt(hex.slice(0, 2), 16);
  const g = parseInt(hex.slice(2, 4), 16);
  const b = parseInt(hex.slice(4, 6), 16);
  return [r, g, b];
}

/**
 * Computes the row background color.
 *
 * @param {string|null} routeColor   - hex without #, or null
 * @param {string|null} agencyColor  - hex without #, or null (fallback)
 * @param {number}      hour         - 24h+ hour value
 * @returns {string} CSS color value
 */
export function computeRowColor(routeColor, agencyColor, hour) {
  // Even hours are always white
  if (hour % 2 === 0) return '#FFFFFF';

  // Determine base color
  const base = routeColor || agencyColor || 'CCCCCC';
  const rgb = hexToRgb(base);
  if (!rgb) return '#FFFFFF';

  return `rgba(${rgb[0]},${rgb[1]},${rgb[2]},0.12)`;
}

/**
 * Computes route icon visibility for each route.
 *
 * Rules:
 * - Single route → no icons (false for all)
 * - One dominant route (>2/3 of departures) → dominant=false, others=true
 * - No dominant route → all=true
 *
 * @param {Array} departures - array with `routeId` field
 * @returns {Object} map of routeId → boolean (true=show icon)
 */
export function computeRouteIconVisibility(departures) {
  const counts = {};
  for (const dep of departures) {
    counts[dep.routeId] = (counts[dep.routeId] || 0) + 1;
  }

  const routeIds = Object.keys(counts);
  const totalCount = departures.length;

  // Single route: no icons
  if (routeIds.length === 1) {
    return { [routeIds[0]]: false };
  }

  // Find max count
  let maxCount = 0;
  let dominantRouteId = null;
  for (const [id, count] of Object.entries(counts)) {
    if (count > maxCount) {
      maxCount = count;
      dominantRouteId = id;
    }
  }

  // Check if dominant route exceeds 2/3
  if (maxCount > (2 / 3) * totalCount) {
    const result = {};
    for (const id of routeIds) {
      result[id] = id !== dominantRouteId;
    }
    return result;
  }

  // No dominant route: all show icons
  const result = {};
  for (const id of routeIds) {
    result[id] = true;
  }
  return result;
}
