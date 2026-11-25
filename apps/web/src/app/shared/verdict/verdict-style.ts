import { Verdict } from '../../core/api/api-types';
import { IconName } from '../icon/icon-paths';

export interface VerdictStyle {
  /** Modifier class (`v-worth`, …) that maps the verdict tokens in `verdict-badge`. */
  readonly modifier: string;
  readonly icon: IconName;
  readonly labelKey: string;
  /** `true` for the dashed, neutral `INSUFFICIENT_DATA` state. */
  readonly dashed: boolean;
}

/** Verdict tone, icon and label (design hand-off §4.5); colours come from `--vd-verdict-*`. */
export const VERDICT_STYLE: Readonly<Record<Verdict, VerdictStyle>> = {
  WORTH_IT: { modifier: 'v-worth', icon: 'ok', labelKey: 'verdict.WORTH_IT', dashed: false },
  MARGINAL: { modifier: 'v-marginal', icon: 'approx', labelKey: 'verdict.MARGINAL', dashed: false },
  NOT_WORTH_IT: {
    modifier: 'v-notworth',
    icon: 'nok',
    labelKey: 'verdict.NOT_WORTH_IT',
    dashed: false,
  },
  DOMINATES: { modifier: 'v-dominates', icon: 'dbl', labelKey: 'verdict.DOMINATES', dashed: false },
  EQUIVALENT: {
    modifier: 'v-equivalent',
    icon: 'equal',
    labelKey: 'verdict.EQUIVALENT',
    dashed: false,
  },
  INSUFFICIENT_DATA: {
    modifier: 'v-insufficient',
    icon: 'help',
    labelKey: 'verdict.INSUFFICIENT_DATA',
    dashed: true,
  },
};
