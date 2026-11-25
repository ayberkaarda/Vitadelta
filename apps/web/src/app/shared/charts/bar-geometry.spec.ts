import { deltaBarGeometry, meterWidth, scoreBarWidth } from './bar-geometry';

describe('scoreBarWidth', () => {
  it.each([
    [0, 0],
    [-5, 0],
    [Number.NaN, 0],
    [0.4, 2],
    [1, 2],
    [2, 2],
    [53.2, 53.2],
    [68, 68],
    [100, 100],
    [120, 100],
  ])('display %s draws %s %%', (display, width) => {
    expect(scoreBarWidth(display)).toBe(width);
  });
});

describe('deltaBarGeometry', () => {
  it('draws the largest gain over 46 % right of the axis', () => {
    expect(deltaBarGeometry(0.0374, 0.0374)).toEqual({ kind: 'gain', x: 50, width: 46 });
  });

  it('draws losses left of the axis', () => {
    expect(deltaBarGeometry(-0.2667, 0.2667)).toEqual({ kind: 'loss', x: 4, width: 46 });
    const half = deltaBarGeometry(-0.1, 0.2);
    expect(half.kind).toBe('loss');
    expect(half.width).toBeCloseTo(23);
    expect(half.x).toBeCloseTo(27);
  });

  it('keeps tiny non-zero deltas visible at 2 %', () => {
    expect(deltaBarGeometry(0.0001, 10)).toEqual({ kind: 'gain', x: 50, width: 2 });
    expect(deltaBarGeometry(-0.0001, 10)).toEqual({ kind: 'loss', x: 48, width: 2 });
  });

  it('draws a dot for no change', () => {
    expect(deltaBarGeometry(0, 1)).toEqual({ kind: 'zero', x: 50, width: 0 });
  });

  it('never exceeds the half width when maxAbs is missing or too small', () => {
    expect(deltaBarGeometry(0.5, 0).width).toBe(46);
    expect(deltaBarGeometry(0.5, 0.1).width).toBe(46);
  });
});

describe('meterWidth', () => {
  it('scales used against the limit and caps at 100 %', () => {
    expect(meterWidth(3, 5)).toBe(60);
    expect(meterWidth(7, 5)).toBe(100);
    expect(meterWidth(0, 5)).toBe(0);
  });

  it('draws only the track for unlimited plans', () => {
    expect(meterWidth(12, null)).toBe(0);
  });

  it('is full when the limit is zero and something is used', () => {
    expect(meterWidth(1, 0)).toBe(100);
  });
});
