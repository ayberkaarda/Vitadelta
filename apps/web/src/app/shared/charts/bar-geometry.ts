/**
 * Width in percent of a score bar fill (design hand-off §5.1): `display` clamped to 0..100,
 * no fill at 0, at least 2 % for any positive score so small values stay visible.
 */
export function scoreBarWidth(display: number): number {
  if (!Number.isFinite(display) || display <= 0) {
    return 0;
  }
  const clamped = Math.min(display, 100);
  return clamped < 2 ? 2 : clamped;
}

export interface DeltaBarGeometry {
  readonly kind: 'gain' | 'loss' | 'zero';
  /** Left edge in percent of the drawing width. */
  readonly x: number;
  /** Width in percent of the drawing width. */
  readonly width: number;
}

/** Half of the drawing width available on each side of the zero axis, minus a margin. */
export const DELTA_HALF_WIDTH = 46;

/**
 * Delta bar geometry (design hand-off §5.2): zero axis at 50 %, `w = 46 * |delta| / maxAbs`
 * with a 2 % minimum for any non-zero delta; gains extend right, losses left.
 */
export function deltaBarGeometry(delta: number, maxAbs: number): DeltaBarGeometry {
  if (!Number.isFinite(delta) || delta === 0) {
    return { kind: 'zero', x: 50, width: 0 };
  }
  const scale =
    Number.isFinite(maxAbs) && maxAbs > 0 ? Math.max(maxAbs, Math.abs(delta)) : Math.abs(delta);
  const width = Math.max(2, (DELTA_HALF_WIDTH * Math.abs(delta)) / scale);
  return delta > 0 ? { kind: 'gain', x: 50, width } : { kind: 'loss', x: 50 - width, width };
}

/** Width in percent of a usage meter fill (design hand-off §5.5); `null` limit = unlimited. */
export function meterWidth(used: number, limit: number | null): number {
  if (limit === null || limit <= 0 || used <= 0) {
    return limit !== null && limit <= 0 && used > 0 ? 100 : 0;
  }
  return Math.min(100, (used / limit) * 100);
}
