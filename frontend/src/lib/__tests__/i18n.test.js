import { describe, it, expect, vi } from 'vitest';

vi.mock('../lirr-ja.json', () => ({
  default: {
    byStopId: {
      'LI_102': 'ジャマイカ駅',
      'LI_179': 'ロンコンコマ駅',
      'LI_201': 'ポートジェファーソン駅',
    },
    byStopName: {
      'Jamaica': 'ジャマイカ駅',
      'Ronkonkoma': 'ロンコンコマ駅',
      'Port Jefferson': 'ポートジェファーソン駅',
    },
  },
}));

import { t, getScheduleError, getHomeError, getNoStopIdError, sortStops } from '../i18n.js';
import { jaStopName } from '../locale.js';

// AC1: Timetable no-service message
describe('AC1 - Timetable no-service', () => {
  it('returns Japanese no-service message when lang=ja', () => {
    expect(t('noService', 'ja')).toBe('この日は運行がありません。');
  });
  it('returns English no-service message when lang=en', () => {
    expect(t('noService', 'en')).toBe('No scheduled service for this date.');
  });
});

// AC2: DatePicker label
describe('AC2 - DatePicker label', () => {
  it('returns 日付 when lang=ja', () => {
    expect(t('date', 'ja')).toBe('日付');
  });
  it('returns Date when lang=en', () => {
    expect(t('date', 'en')).toBe('Date');
  });
});

// AC3: DestinationPicker placeholder
describe('AC3 - DestinationPicker placeholder', () => {
  it('returns Japanese placeholder when lang=ja', () => {
    expect(t('searchDestinations', 'ja')).toBe('目的地を検索…');
  });
  it('returns English placeholder when lang=en', () => {
    expect(t('searchDestinations', 'en')).toBe('Search destinations...');
  });
});

// AC4: DestinationPicker no-results
describe('AC4 - DestinationPicker no-results', () => {
  it('returns 結果なし when lang=ja', () => {
    expect(t('noResults', 'ja')).toBe('結果なし');
  });
  it('returns No results when lang=en', () => {
    expect(t('noResults', 'en')).toBe('No results');
  });
});

// AC5: HeadsignFilter error
describe('AC5 - HeadsignFilter at-least-one error', () => {
  it('returns Japanese error when lang=ja', () => {
    expect(t('atLeastOneDestination', 'ja')).toBe('少なくとも1つの行先を選択してください。');
  });
  it('returns English error when lang=en', () => {
    expect(t('atLeastOneDestination', 'en')).toBe('At least one destination must be selected.');
  });
});

// AC6: App home error
describe('AC6 - App home load error', () => {
  it('returns Japanese home error when lang=ja', () => {
    expect(getHomeError('ja')).toBe('データを読み込めませんでした。後でもう一度お試しください。');
  });
  it('returns English home error when lang=en', () => {
    expect(getHomeError('en')).toBe('Could not load stations. Try again later.');
  });
});

// AC7: App schedule error (generic)
describe('AC7 - App schedule generic error', () => {
  it('returns Japanese schedule error when lang=ja', () => {
    expect(getScheduleError('500', 'ja')).toBe('時刻表を読み込めませんでした。後でもう一度お試しください。');
  });
  it('returns English schedule error when lang=en', () => {
    expect(getScheduleError('500', 'en')).toBe('Could not load schedule. Try again later.');
  });
});

// AC8: App 404
describe('AC8 - App schedule 404 error', () => {
  it('returns Japanese stop-not-found when lang=ja and status 404', () => {
    expect(getScheduleError('404', 'ja')).toBe('駅が見つかりません。');
  });
  it('returns English stop-not-found when lang=en and status 404', () => {
    expect(getScheduleError('404', 'en')).toBe('Stop not found.');
  });
});

// AC9: App 400
describe('AC9 - App schedule 400 error', () => {
  it('returns Japanese invalid-request when lang=ja and status 400', () => {
    expect(getScheduleError('400', 'ja')).toBe('無効なリクエストです。');
  });
  it('returns English invalid-request when lang=en and status 400', () => {
    expect(getScheduleError('400', 'en')).toBe('Invalid request.');
  });
});

// AC10: App no-stop-ID message
describe('AC10 - App no-stop-ID error', () => {
  it('returns Japanese no-stop-id message when lang=ja', () => {
    expect(getNoStopIdError('ja')).toBe('停留所IDが指定されていません。/stop/<stopId> にアクセスしてください。');
  });
  it('returns English no-stop-id message when lang=en', () => {
    expect(getNoStopIdError('en')).toBe('No stop ID specified. Navigate to /stop/<stopId>.');
  });
});

// AC11: Header loading state
describe('AC11 - Header loading text', () => {
  it('returns 読み込み中… when lang=ja', () => {
    expect(t('loading', 'ja')).toBe('読み込み中…');
  });
  it('returns Loading… when lang=en', () => {
    expect(t('loading', 'en')).toBe('Loading…');
  });
});

// AC12: HomePage subtitle
describe('AC12 - HomePage subtitle', () => {
  it('returns Japanese subtitle when lang=ja', () => {
    expect(t('selectOriginStation', 'ja')).toBe('出発駅を選択');
  });
  it('returns English subtitle when lang=en', () => {
    expect(t('selectOriginStation', 'en')).toBe('Select your origin station');
  });
});

// AC13: HomePage search placeholder
describe('AC13 - HomePage search placeholder', () => {
  it('returns Japanese placeholder when lang=ja', () => {
    expect(t('searchStations', 'ja')).toBe('駅を検索…');
  });
  it('returns English placeholder when lang=en', () => {
    expect(t('searchStations', 'en')).toBe('Search stations...');
  });
});

// AC14: HomePage no-stations message
describe('AC14 - HomePage no-stations', () => {
  it('returns Japanese no-stations message when lang=ja', () => {
    expect(t('noStationsFound', 'ja')).toBe('駅が見つかりません。');
  });
  it('returns English no-stations message when lang=en', () => {
    expect(t('noStationsFound', 'en')).toBe('No stations found.');
  });
});

// AC15: Pills label
describe('AC15 - Pills label', () => {
  it('returns 行先 when lang=ja', () => {
    expect(t('tripsToward', 'ja')).toBe('行先');
  });
  it('returns Trips toward when lang=en', () => {
    expect(t('tripsToward', 'en')).toBe('Trips toward');
  });
});

// AC17: Station collation — sortStops
describe('AC17 - Station collation in Japanese', () => {
  const stops = [
    { id: 'LI_179', name: 'Ronkonkoma' },   // ロンコンコマ駅
    { id: 'LI_102', name: 'Jamaica' },        // ジャマイカ駅
    { id: 'LI_201', name: 'Port Jefferson' }, // ポートジェファーソン駅
    { id: 'LI_999', name: 'Unknown Stop' },   // no Japanese name → sorts last
  ];

  it('sorts by Japanese name in 五十音順 when lang=ja; stops without ja name sort last', () => {
    const sorted = sortStops(stops, 'ja', jaStopName);
    // ジャマイカ (ja), ポートジェファーソン (po), ロンコンコマ (ro) — Japanese phonetic order
    expect(sorted[0].id).toBe('LI_102');  // ジャマイカ
    expect(sorted[1].id).toBe('LI_201'); // ポートジェファーソン
    expect(sorted[2].id).toBe('LI_179'); // ロンコンコマ
    expect(sorted[3].id).toBe('LI_999'); // no ja name → last
  });

  it('preserves original order when lang=en', () => {
    const sorted = sortStops(stops, 'en', jaStopName);
    expect(sorted.map((s) => s.id)).toEqual(['LI_179', 'LI_102', 'LI_201', 'LI_999']);
  });
});
