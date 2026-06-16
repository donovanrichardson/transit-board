/**
 * Fetches the list of stops for a given agency.
 *
 * @param {string} agencyId - agency ID (e.g. "LI")
 * @returns {Promise<Object>} parsed JSON response with `stops` array
 * @throws {Error} with status code embedded in message on failure
 */
export async function fetchStops(agencyId) {
  const url = `/api/stops?agency=${encodeURIComponent(agencyId)}`;
  let resp;
  try {
    resp = await fetch(url);
  } catch (e) {
    throw new Error('502');
  }

  if (!resp.ok) {
    throw new Error(String(resp.status));
  }

  return resp.json();
}

/**
 * Fetches schedule data from the API.
 *
 * @param {string} stopId - OBA stop ID
 * @param {string} date   - schedule date in YYYY-MM-DD format
 * @returns {Promise<Object>} parsed JSON response
 * @throws {Error} with status code embedded in message for 400/404/502
 */
export async function fetchSchedule(stopId, date) {
  const url = `/api/schedule?stop=${encodeURIComponent(stopId)}&date=${encodeURIComponent(date)}`;
  let resp;
  try {
    resp = await fetch(url);
  } catch (e) {
    throw new Error('502');
  }

  if (!resp.ok) {
    throw new Error(String(resp.status));
  }

  return resp.json();
}
