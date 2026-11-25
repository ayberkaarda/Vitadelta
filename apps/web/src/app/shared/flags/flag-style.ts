import { DeltaRowFlag, ProductFlagCode } from '../../core/api/api-types';
import { IconName } from '../icon/icon-paths';

/** Tone class suffix of `.badge` / `.notice` (`t-warn`, `t-info`, …); `neutral` uses the base. */
export type Tone = 'neutral' | 'info' | 'warn' | 'ok' | 'bad';

export interface FlagStyle {
  readonly tone: Tone;
  readonly icon: IconName;
  /** i18n key of the label. */
  readonly labelKey: string;
}

/** Product flags (design hand-off §3 "Flag badge"). */
export const PRODUCT_FLAG_STYLE: Readonly<Record<ProductFlagCode, FlagStyle>> = {
  EXCEEDS_UL: { tone: 'warn', icon: 'warn', labelKey: 'flag.EXCEEDS_UL' },
  UL_FORM_DEPENDENT: { tone: 'warn', icon: 'shield', labelKey: 'flag.UL_FORM_DEPENDENT' },
  PRICE_STALE: { tone: 'neutral', icon: 'clock', labelKey: 'flag.PRICE_STALE' },
  OUT_OF_STOCK: { tone: 'neutral', icon: 'nostock', labelKey: 'flag.OUT_OF_STOCK' },
  FX_STALE: { tone: 'neutral', icon: 'clock', labelKey: 'flag.FX_STALE' },
  FX_UNAVAILABLE: { tone: 'neutral', icon: 'fx', labelKey: 'flag.FX_UNAVAILABLE' },
  UNAVAILABLE_IN_COUNTRY: {
    tone: 'neutral',
    icon: 'nostock',
    labelKey: 'flag.UNAVAILABLE_IN_COUNTRY',
  },
  MARKET_MEDIAN_REFERENCE_USED: {
    tone: 'info',
    icon: 'info',
    labelKey: 'flag.MARKET_MEDIAN_REFERENCE_USED',
  },
  PRESENCE_ONLY_NUTRIENTS: { tone: 'info', icon: 'info', labelKey: 'flag.PRESENCE_ONLY_NUTRIENTS' },
  BELOW_PRESENCE_FLOOR: { tone: 'info', icon: 'info', labelKey: 'flag.BELOW_PRESENCE_FLOOR' },
  CONVERSION_ASSUMED: { tone: 'info', icon: 'info', labelKey: 'flag.CONVERSION_ASSUMED' },
};

/** Delta-table row flags. */
export const DELTA_FLAG_STYLE: Readonly<Record<DeltaRowFlag, FlagStyle>> = {
  NEW_IN_B: { tone: 'info', icon: 'plus', labelKey: 'delta.flag.NEW_IN_B' },
  REMOVED_IN_B: { tone: 'neutral', icon: 'minus', labelKey: 'delta.flag.REMOVED_IN_B' },
  DIMINISHING: { tone: 'info', icon: 'dimin', labelKey: 'delta.flag.DIMINISHING' },
  ABOVE_CEILING_NO_CREDIT: {
    tone: 'warn',
    icon: 'ceil',
    labelKey: 'delta.flag.ABOVE_CEILING_NO_CREDIT',
  },
  ABOVE_UL_NO_CREDIT: { tone: 'warn', icon: 'warn', labelKey: 'delta.flag.ABOVE_UL_NO_CREDIT' },
};

/** Info-tone method flags that a dense list collapses into one "N method notes" badge. */
export const METHOD_NOTE_FLAGS: ReadonlySet<ProductFlagCode> = new Set<ProductFlagCode>([
  'MARKET_MEDIAN_REFERENCE_USED',
  'PRESENCE_ONLY_NUTRIENTS',
  'BELOW_PRESENCE_FLOOR',
  'CONVERSION_ASSUMED',
]);

/** CSS class for a tone on `.badge`, `.notice` or `.state`. */
export function toneClass(tone: Tone): string {
  return tone === 'neutral' ? 't-neutral' : `t-${tone}`;
}
