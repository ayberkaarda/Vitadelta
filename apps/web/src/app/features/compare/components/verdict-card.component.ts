import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { CompareResponse, EngineParameters } from '../../../core/api/api-types';
import { Formatter } from '../../../core/format/formatter';
import { DisclaimerComponent } from '../../../shared/disclaimer/disclaimer.component';
import { EmphasizedTextComponent, emphasize } from '../../../shared/text/emphasized-text.component';
import { VerdictBadgeComponent } from '../../../shared/verdict/verdict-badge.component';
import { VERDICT_STYLE } from '../../../shared/verdict/verdict-style';
import { RatioScaleComponent } from './ratio-scale.component';

let nextId = 0;

interface Text {
  readonly key: string;
  readonly params: Readonly<Record<string, string>>;
}

interface Metric {
  /** Metric id; the label is `compare.verdict.<key>`. */
  readonly key: string;
  /** Formatted value, or `null` when `valueKey` renders it. */
  readonly value: string | null;
  readonly valueKey?: Text;
  readonly sub: Text | null;
}

/**
 * Verdict card (design hand-off §4.5, project specification §10.3): the server's verdict as a
 * label pill in its tone, the one-line explanation from `explanation.key` and its parameters
 * (money parameters formatted as amounts), the delta metrics, the ratio scale and the notes
 * (market-rate fallback, `verdict.indicative_note`, not medical advice).
 *
 * `marginalRate` / `ratio` = `null` drop their metric and the scale; `INSUFFICIENT_DATA` is a
 * neutral, dashed card without ratio or market rate. The client never derives a verdict.
 */
@Component({
  selector: 'vd-verdict-card',
  imports: [
    DisclaimerComponent,
    EmphasizedTextComponent,
    RatioScaleComponent,
    TranslocoPipe,
    VerdictBadgeComponent,
  ],
  template: `
    @let r = response();
    <section
      class="verdict"
      [class]="modifier()"
      [class.dashed]="dashed()"
      [attr.data-verdict]="r.verdict"
      [attr.aria-labelledby]="headingId"
    >
      <h2 class="sr-only" [id]="headingId">{{ 'compare.verdict.heading' | transloco }}</h2>
      <div class="vh">
        <vd-verdict-badge [verdict]="r.verdict" />
        <span class="text-xs muted">{{ 'verdict.caption' | transloco }}</span>
      </div>
      <p class="expl" data-role="explanation">
        <vd-emphasized-text [text]="r.explanation.key | transloco: explanationParams()" />
      </p>
      <dl class="metrics">
        @for (metric of metrics(); track metric.key) {
          <div [attr.data-metric]="metric.key">
            <dt class="k">{{ 'compare.verdict.' + metric.key | transloco }}</dt>
            <dd class="m">
              @if (metric.valueKey; as v) {
                {{ v.key | transloco: v.params }}
              } @else {
                {{ metric.value }}
              }
            </dd>
            @if (metric.sub; as sub) {
              <dd class="k">{{ sub.key | transloco: sub.params }}</dd>
            }
          </div>
        }
      </dl>
      @if (scale(); as s) {
        <vd-ratio-scale [ratio]="s.ratio" [worth]="s.worth" [marginal]="s.marginal" />
      }
      <div class="notes">
        @if (showFallback()) {
          <p data-note="market_fallback">
            {{ 'verdict.market_fallback' | transloco: { marketSize: r.meta.marketSize } }}
          </p>
        }
        <p data-note="indicative">{{ 'verdict.indicative_note' | transloco }}</p>
        <vd-disclaimer variant="inline" />
      </div>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .verdict {
      --t: var(--vd-verdict-neutral);
      display: flex;
      flex-direction: column;
      gap: var(--vd-space-4);
      padding: var(--vd-space-5);
      border-radius: var(--vd-radius-lg);
      background: var(--vd-color-surface);
      border: 1px solid var(--vd-color-border);
      border-top: 4px solid var(--t);
    }
    .verdict.dashed {
      border-top-style: dashed;
    }
    .v-worth {
      --t: var(--vd-verdict-worth);
    }
    .v-marginal {
      --t: var(--vd-verdict-marginal);
    }
    .v-notworth {
      --t: var(--vd-verdict-not-worth);
    }
    .v-dominates {
      --t: var(--vd-verdict-dominates);
    }
    .v-equivalent {
      --t: var(--vd-verdict-equivalent);
    }
    .vh {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--vd-space-3);
    }
    .expl {
      font-size: 1.0625rem;
      line-height: 1.45;
      max-width: 64ch;
    }
    .metrics {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      gap: 1px;
      margin: 0;
      background: var(--vd-color-border);
      border: 1px solid var(--vd-color-border);
      border-radius: var(--vd-radius-md);
      overflow: hidden;
    }
    .metrics > div {
      background: var(--vd-color-surface);
      padding: var(--vd-space-3) var(--vd-space-4);
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .metrics dd {
      margin: 0;
    }
    .m {
      font-size: var(--vd-font-size-lg);
      font-weight: var(--vd-font-weight-bold);
      font-variant-numeric: tabular-nums;
      line-height: 1.2;
    }
    .k {
      font-size: var(--vd-font-size-xs);
      color: var(--vd-color-text-muted);
      font-variant-numeric: tabular-nums;
    }
    .notes {
      display: flex;
      flex-direction: column;
      gap: var(--vd-space-1);
      font-size: var(--vd-font-size-xs);
      color: var(--vd-color-text-muted);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VerdictCardComponent {
  private readonly format = inject(Formatter);

  readonly response = input.required<CompareResponse>();
  /** Engine parameters from `/weights/defaults`; `null` hides the scale and threshold line. */
  readonly engine = input<EngineParameters | null>(null);

  protected readonly headingId = `vd-verdict-${nextId++}`;
  protected readonly modifier = computed(
    () => `verdict ${VERDICT_STYLE[this.response().verdict].modifier}`,
  );
  protected readonly dashed = computed(() => VERDICT_STYLE[this.response().verdict].dashed);

  private readonly insufficient = computed(() => this.response().verdict === 'INSUFFICIENT_DATA');

  /**
   * Explanation parameters, each formatted in the active locale before interpolation and marked
   * for emphasis (the numbers are bold, design hand-off §4.5).
   */
  protected readonly explanationParams = computed(() => {
    const params = this.response().explanation.params;
    const out: Record<string, string> = { currency: emphasize(params.currency) };
    if (params.deltaCostPerDay !== undefined) {
      out['deltaCostPerDay'] = emphasize(this.format.amount(params.deltaCostPerDay, 2));
    }
    if (params.marginalRate !== undefined) {
      out['marginalRate'] = emphasize(this.format.amount(params.marginalRate, 2));
    }
    if (params.mRef !== undefined) {
      out['mRef'] = emphasize(this.format.amount(params.mRef, 2));
    }
    if (params.deltaContent !== undefined) {
      out['deltaContent'] = emphasize(this.format.amount(params.deltaContent, 4));
    }
    if (params.ratio !== undefined) {
      out['ratio'] = emphasize(this.format.amount(params.ratio, 1));
    }
    return out;
  });

  protected readonly metrics = computed<readonly Metric[]>(() => {
    const r = this.response();
    const currency = r.meta.currency;
    const money = (value: number) => this.format.money(value, currency);
    const amount = (value: number, digits: number) => this.format.amount(value, digits);
    const metrics: Metric[] = [
      {
        key: 'extra_cost',
        value: r.deltaCost > 0 ? `+${money(r.deltaCost)}` : money(r.deltaCost),
        sub: {
          key: 'compare.verdict.difference',
          params: {
            b: amount(r.productB.cost.costPerDay, 2),
            a: amount(r.productA.cost.costPerDay, 2),
          },
        },
      },
      {
        key: 'extra_content',
        value: this.format.signed(r.deltaContent, 4),
        sub: {
          key: 'compare.verdict.difference',
          params: { b: amount(r.productB.contentScore, 4), a: amount(r.productA.contentScore, 4) },
        },
      },
    ];
    if (this.insufficient()) {
      return metrics;
    }
    if (r.marginalRate !== null) {
      metrics.push({
        key: 'marginal_rate',
        value: money(r.marginalRate),
        sub:
          r.mRef !== null
            ? { key: 'compare.verdict.market_rate', params: { amount: money(r.mRef) } }
            : null,
      });
    }
    if (r.ratio !== null) {
      const engine = this.engine();
      metrics.push({
        key: 'ratio',
        value: null,
        valueKey: { key: 'compare.verdict.ratio_value', params: { ratio: amount(r.ratio, 1) } },
        sub: engine
          ? {
              key: 'compare.verdict.thresholds',
              params: {
                worth: amount(engine.verdictWorthItMaxRatio, 1),
                marginal: amount(engine.verdictMarginalMaxRatio, 1),
              },
            }
          : null,
      });
    }
    return metrics;
  });

  protected readonly scale = computed(() => {
    const r = this.response();
    const engine = this.engine();
    if (r.ratio === null || engine === null || this.insufficient()) {
      return null;
    }
    return {
      ratio: r.ratio,
      worth: engine.verdictWorthItMaxRatio,
      marginal: engine.verdictMarginalMaxRatio,
    };
  });

  protected readonly showFallback = computed(() => {
    const r = this.response();
    return !this.insufficient() && r.mRef !== null && r.meta.marketSize < 3;
  });
}
