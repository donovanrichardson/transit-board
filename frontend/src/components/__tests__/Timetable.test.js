import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/svelte';
import Timetable from '../Timetable.svelte';

const dep = (hour, minute) => ({
  hour,
  minute,
  headsign: 'Penn Station',
  routeId: 'LI_1',
  routeColor: '009B3A',
  routeTextColor: 'FFFFFF',
  directionId: '1',
  downstreamStops: [],
});

describe('Timetable row height', () => {
  it('empty hour rows contain a spacer to prop up row height', () => {
    // hours 10 and 12 have departures; hour 11 is an empty gap
    const { container } = render(Timetable, {
      props: { departures: [dep(10, 5), dep(12, 30)], routes: [], isLirrMode: true },
    });

    const rows = container.querySelectorAll('.timetable-row');
    expect(rows.length).toBe(3);

    const emptyCell = rows[1].querySelector('.minutes-cell');
    expect(emptyCell.querySelector('.row-height-spacer')).not.toBeNull();
  });

  it('populated hour rows do not contain a spacer', () => {
    const { container } = render(Timetable, {
      props: { departures: [dep(10, 5), dep(12, 30)], routes: [], isLirrMode: true },
    });

    const rows = container.querySelectorAll('.timetable-row');

    const populatedCell = rows[0].querySelector('.minutes-cell');
    expect(populatedCell.querySelector('.row-height-spacer')).toBeNull();
  });
});
