import { describe, it, expect, vi } from 'vitest';

vi.mock('../lirr-ja.json', () => ({
  default: {
    byStopId: {
      'LI_102': 'ジャマイカ駅',
      'LI_179': 'ロンコンコマ駅',
    },
    byStopName: {
      'Jamaica': 'ジャマイカ駅',
      'Ronkonkoma': 'ロンコンコマ駅',
      'Port Jefferson': 'ポートジェファーソン駅',
    },
  },
}));

import { jaStopName, jaHeadsign } from '../locale.js';

describe('jaStopName', () => {
  it('returns Japanese for a known stop ID', () => {
    expect(jaStopName('LI_102', 'Jamaica')).toBe('ジャマイカ駅');
  });

  it('returns fallback for an unknown stop ID', () => {
    expect(jaStopName('LI_UNKNOWN', 'Fallback')).toBe('Fallback');
  });
});

describe('jaHeadsign', () => {
  it('returns Japanese for a known stop name', () => {
    expect(jaHeadsign('Ronkonkoma', 'Ronkonkoma')).toBe('ロンコンコマ駅');
  });

  it('returns fallback for an unknown stop name', () => {
    expect(jaHeadsign('Unknown Place', 'Unknown Place')).toBe('Unknown Place');
  });

  it('returns Japanese + (バス) for a Bus-suffixed known stop', () => {
    expect(jaHeadsign('Port Jefferson (Bus)', 'Port Jefferson (Bus)')).toBe('ポートジェファーソン駅 (バス)');
  });

  it('returns fallback when stopName is null', () => {
    expect(jaHeadsign(null, 'fallback')).toBe('fallback');
  });
});
