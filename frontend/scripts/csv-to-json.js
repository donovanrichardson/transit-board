#!/usr/bin/env node
// Build script: parse lirr_japanese_stations.csv and emit lirr-ja.json
// Usage: node scripts/csv-to-json.js

import { readFileSync, writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const csvPath = resolve(__dirname, '../../logs/lirr_japanese_stations.csv');
const outPath = resolve(__dirname, '../src/lib/lirr-ja.json');

const raw = readFileSync(csvPath, 'utf-8');
const lines = raw.split('\n');

// Skip header line
const header = lines[0].split(',');
const colIndex = (name) => header.indexOf(name);

const jaIdx = colIndex('japanese_name');
const nameIdx = colIndex('oba_stop_name');
const idIdx = colIndex('oba_stop_id');
const matchIdx = colIndex('match_type');

const byStopId = {};
const byStopName = {};

for (let i = 1; i < lines.length; i++) {
  const line = lines[i].trim();
  if (!line) continue;

  // Simple CSV parse (fields may not contain commas in this file)
  const cols = line.split(',');

  const jaName = cols[jaIdx]?.trim();
  const obaName = cols[nameIdx]?.trim();
  const obaId = cols[idIdx]?.trim();
  const matchType = cols[matchIdx]?.trim();

  // Skip GAP rows and rows with no OBA stop ID
  if (matchType === 'GAP' || !obaId) continue;

  if (jaName && obaId) {
    byStopId[obaId] = jaName;
  }
  if (jaName && obaName) {
    byStopName[obaName] = jaName;
  }
}

const output = JSON.stringify({ byStopId, byStopName }, null, 2);
writeFileSync(outPath, output, 'utf-8');

console.log(`Wrote ${Object.keys(byStopId).length} byStopId entries and ${Object.keys(byStopName).length} byStopName entries to ${outPath}`);
