/** Translations keyed by string ID. */

const EN = {
  noService: 'No scheduled service for this date.',
  date: 'Date',
  searchDestinations: 'Search destinations...',
  noResults: 'No results',
  atLeastOneDestination: 'At least one destination must be selected.',
  couldNotLoadStations: 'Could not load stations. Try again later.',
  couldNotLoadSchedule: 'Could not load schedule. Try again later.',
  stopNotFound: 'Stop not found.',
  invalidRequest: 'Invalid request.',
  noStopId: 'No stop ID specified. Navigate to /stop/<stopId>.',
  loading: 'Loading…',
  selectOriginStation: 'Select your origin station',
  searchStations: 'Search stations...',
  noStationsFound: 'No stations found.',
  tripsToward: 'Trips toward',
};

const JA = {
  noService: 'この日は運行がありません。',
  date: '日付',
  searchDestinations: '目的地を検索…',
  noResults: '結果なし',
  atLeastOneDestination: '少なくとも1つの行先を選択してください。',
  couldNotLoadStations: 'データを読み込めませんでした。後でもう一度お試しください。',
  couldNotLoadSchedule: '時刻表を読み込めませんでした。後でもう一度お試しください。',
  stopNotFound: '駅が見つかりません。',
  invalidRequest: '無効なリクエストです。',
  noStopId: '停留所IDが指定されていません。/stop/<stopId> にアクセスしてください。',
  loading: '読み込み中…',
  selectOriginStation: '出発駅を選択',
  searchStations: '駅を検索…',
  noStationsFound: '駅が見つかりません。',
  tripsToward: '行先',
};

/**
 * Look up a translation string.
 * @param {string} key - key from the EN/JA tables
 * @param {string} lang - 'en' | 'ja'
 * @returns {string}
 */
export function t(key, lang) {
  if (lang === 'ja') return JA[key] ?? EN[key];
  return EN[key];
}

/**
 * Return the schedule-load error message for the given HTTP status and lang.
 * @param {string} status - e.g. '404', '400', '500'
 * @param {string} lang
 * @returns {string}
 */
export function getScheduleError(status, lang) {
  if (status === '404') return t('stopNotFound', lang);
  if (status === '400') return t('invalidRequest', lang);
  return t('couldNotLoadSchedule', lang);
}

/**
 * Return the home-stop-load error message.
 * @param {string} lang
 * @returns {string}
 */
export function getHomeError(lang) {
  return t('couldNotLoadStations', lang);
}

/**
 * Return the "no stop ID in URL" error message.
 * @param {string} lang
 * @returns {string}
 */
export function getNoStopIdError(lang) {
  return t('noStopId', lang);
}

/**
 * Sort stops array by Japanese phonetic order (五十音順) when lang='ja'.
 * Stops with no Japanese name sort after those that have one.
 * When lang != 'ja', returns the array unchanged (same reference).
 *
 * @param {Array<{id: string, name: string}>} stops
 * @param {string} lang
 * @param {(id: string, fallback: string|null) => string|null} jaStopNameFn
 * @returns {Array}
 */
export function sortStops(stops, lang, jaStopNameFn) {
  if (lang !== 'ja') return stops;
  return [...stops].sort((a, b) => {
    const ja_a = jaStopNameFn(a.id, null);
    const ja_b = jaStopNameFn(b.id, null);
    if (ja_a && ja_b) return ja_a.localeCompare(ja_b, 'ja');
    if (ja_a) return -1;
    if (ja_b) return 1;
    return 0;
  });
}
