import jaData from './lirr-ja.json';

export function jaStopName(stopId, fallback) {
  return jaData.byStopId[stopId] ?? fallback;
}

export function jaHeadsign(stopName, fallback) {
  if (!stopName) return fallback;
  const busSuffix = stopName.endsWith(' (Bus)');
  const base = busSuffix ? stopName.slice(0, -6) : stopName;
  const ja = jaData.byStopName[base];
  if (!ja) return fallback;
  return busSuffix ? `${ja} (バス)` : ja;
}
