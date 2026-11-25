/** One drawing primitive of a 20 x 20 stroke icon. */
export type IconShape =
  | { readonly d: string }
  | { readonly circle: readonly [cx: number, cy: number, r: number]; readonly dashed?: boolean }
  | { readonly rect: readonly [x: number, y: number, width: number, height: number, rx: number] };

const RING = { circle: [10, 10, 7.25] } as const;

/**
 * Stroke icons on a `0 0 20 20` grid (design hand-off §2.6). Rendered by `IconComponent` with
 * `stroke: currentColor`, round caps and joins, no fill.
 */
export const ICONS = {
  lock: [{ rect: [4.75, 9, 10.5, 8, 1.5] }, { d: 'M7 9V6.5a3 3 0 0 1 6 0V9' }],
  warn: [{ d: 'M10 3.25 17.5 16.25h-15Z' }, { d: 'M10 8.25v3.25M10 13.9v.01' }],
  shield: [{ d: 'M10 3 16 5.5v4c0 3.5-2.5 6-6 7.5-3.5-1.5-6-4-6-7.5v-4Z' }],
  info: [RING, { d: 'M10 9v4.25M10 6.6v.01' }],
  clock: [RING, { d: 'M10 6.25V10l2.5 1.75' }],
  nostock: [{ d: 'M3.5 6.5 10 3.25l6.5 3.25v7L10 16.75 3.5 13.5Z' }, { d: 'm3 3 14 14' }],
  fx: [{ d: 'M4 7h11l-2.5-2.5M16 13H5l2.5 2.5' }],
  flask: [
    {
      d: 'M8 3.5h4M8.75 3.5v4.25L4.5 15a1 1 0 0 0 .9 1.5h9.2a1 1 0 0 0 .9-1.5l-4.25-7.25V3.5M6.5 12h7',
    },
  ],
  tag: [{ d: 'M3.5 3.5h6l7 7-6 6-7-7Z' }, { circle: [7, 7, 1] }],
  ok: [RING, { d: 'm6.75 10.25 2.25 2.25 4.25-4.75' }],
  nok: [RING, { d: 'm7.5 7.5 5 5m0-5-5 5' }],
  approx: [
    RING,
    { d: 'M6.5 8.75c1.2-1 2.3-1 3.5 0s2.3 1 3.5 0M6.5 11.75c1.2-1 2.3-1 3.5 0s2.3 1 3.5 0' },
  ],
  dbl: [RING, { d: 'm7 10.5 3-3 3 3M7 13.5l3-3 3 3' }],
  equal: [RING, { d: 'M7 8.5h6M7 11.5h6' }],
  help: [
    { circle: [10, 10, 7.25], dashed: true },
    { d: 'M8.25 8a1.85 1.85 0 1 1 2.6 1.7c-.55.25-.85.7-.85 1.3v.25M10 13.6v.01' },
  ],
  plus: [{ d: 'M10 4.5v11M4.5 10h11' }],
  minus: [{ d: 'M4.5 10h11' }],
  dimin: [{ d: 'M3.5 16.5C6 9.5 10 6.5 16.5 5.5' }],
  ceil: [{ d: 'M3.5 4.5h13M10 16V8M6.5 11.5 10 8l3.5 3.5' }],
  swap: [{ d: 'M4 7h12l-3-3M16 13H4l3 3' }],
  save: [{ d: 'M5.5 3.5h9v13L10 13.25 5.5 16.5Z' }],
  bell: [
    { d: 'M5.5 8.5a4.5 4.5 0 0 1 9 0c0 4 1.5 5.25 1.5 5.25H4s1.5-1.25 1.5-5.25Z' },
    { d: 'M8.5 16.25a1.6 1.6 0 0 0 3 0' },
  ],
  cmp: [{ rect: [3.5, 4.5, 5, 11, 1] }, { rect: [11.5, 4.5, 5, 11, 1] }],
  ext: [
    {
      d: 'M11.5 3.5h5v5M16.5 3.5 9.5 10.5M14.5 11.5v4a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1h4',
    },
  ],
  key: [{ circle: [7, 12.5, 3.25] }, { d: 'M9.5 10 16 3.5M13.5 6l2 2' }],
  mail: [{ rect: [3, 5, 14, 10, 1.5] }, { d: 'm3.5 6 6.5 5 6.5-5' }],
  refresh: [{ d: 'M16.25 10a6.25 6.25 0 1 1-1.83-4.42' }, { d: 'M16.25 3.75v3.5h-3.5' }],
  trash: [{ d: 'M4 6h12M8 6V4.25h4V6M5.75 6l.75 10.25h7l.75-10.25M8.5 9v4.5M11.5 9v4.5' }],
  upload: [{ d: 'M10 13V3.75M6.5 7.25 10 3.75l3.5 3.5M4 13.5v2.25h12V13.5' }],
  copy: [
    { rect: [7, 7, 9.5, 9.5, 1.5] },
    {
      d: 'M13 7V4.75a1.25 1.25 0 0 0-1.25-1.25h-7A1.25 1.25 0 0 0 3.5 4.75v7A1.25 1.25 0 0 0 4.75 13H7',
    },
  ],
  menu: [{ d: 'M3.5 5.5h13M3.5 10h13M3.5 14.5h13' }],
  chev: [{ d: 'm6 8 4 4 4-4' }],
  back: [{ d: 'M16 10H4.5M9 5.5 4.5 10 9 14.5' }],
  arrow: [{ d: 'M4 10h11.5M11 5.5l4.5 4.5-4.5 4.5' }],
  user: [{ circle: [10, 7, 3.25] }, { d: 'M3.75 16.5c.9-3 3.3-4.5 6.25-4.5s5.35 1.5 6.25 4.5' }],
  logout: [{ d: 'M8 16.5H4.5a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1H8M12.5 6.5 16 10l-3.5 3.5M16 10H8' }],
  search: [{ circle: [9, 9, 5.25] }, { d: 'm13 13 3.5 3.5' }],
  sliders: [
    { d: 'M3.5 6h3M10 6h6.5M3.5 14h7.5M14.5 14h2' },
    { circle: [8.25, 6, 1.75] },
    { circle: [12.75, 14, 1.75] },
  ],
  list: [{ d: 'M7.5 5.5h9M7.5 10h9M7.5 14.5h9M3.75 5.5v.01M3.75 10v.01M3.75 14.5v.01' }],
  close: [{ d: 'm5 5 10 10m0-10L5 15' }],
  check: [{ d: 'm4.5 10.5 3.5 3.5 7.5-8' }],
  star: [{ d: 'M10 3.25l2.1 4.3 4.65.7-3.4 3.3.8 4.7L10 13.8l-4.15 2.45.8-4.7-3.4-3.3 4.65-.7Z' }],
  down: [{ d: 'M10 4v12M5.5 11.5 10 16l4.5-4.5' }],
  box: [
    { d: 'M3.5 6.5 10 3.25l6.5 3.25v7L10 16.75 3.5 13.5Z' },
    { d: 'M3.5 6.5 10 9.75l6.5-3.25M10 9.75v7' },
  ],
} satisfies Readonly<Record<string, readonly IconShape[]>>;

export type IconName = keyof typeof ICONS;
