import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ProductProvenance } from '../../core/api/api-types';
import { Formatter } from '../../core/format/formatter';
import { IconComponent } from '../icon/icon.component';

/** Which marker to render. */
export type ProvenanceKind = 'product' | 'demo-price' | 'demo-prices';

/**
 * Honest provenance (design hand-off §1.4, §3):
 * - `kind="product"`: "Synthetic demo product" (flask) for `synthetic = true`, or
 *   "US label snapshot · {date}" (tag) for label snapshots; with `detail` the
 *   "identification only, no affiliation" line is added;
 * - `kind="demo-price"`: "Demo price" (per offer);
 * - `kind="demo-prices"`: "All prices are demo values" (once per table or toolbar).
 *
 * ```html
 * <vd-provenance [provenance]="item.provenance" />
 * <vd-provenance kind="demo-prices" />
 * ```
 */
@Component({
  selector: 'vd-provenance',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @switch (kind()) {
      @case ('product') {
        @if (provenance(); as p) {
          @if (p.synthetic) {
            <span class="prov" data-kind="synthetic">
              <vd-icon name="flask" />{{ 'provenance.synthetic' | transloco }}
            </span>
          } @else {
            <span class="prov" data-kind="snapshot">
              <vd-icon name="tag" />
              @if (snapshotDate(); as date) {
                {{ 'provenance.label_snapshot' | transloco: { date } }}
              } @else {
                {{ 'provenance.label_snapshot_undated' | transloco }}
              }
            </span>
            @if (detail()) {
              <span class="prov note">{{ 'provenance.identification_only' | transloco }}</span>
            }
          }
        }
      }
      @case ('demo-price') {
        <span class="prov" data-kind="demo-price">
          <vd-icon name="info" />{{ 'provenance.demo_price' | transloco }}
        </span>
      }
      @case ('demo-prices') {
        <span class="prov" data-kind="demo-prices">
          <vd-icon name="info" />{{ 'provenance.demo_prices' | transloco }}
        </span>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      gap: var(--vd-space-1) var(--vd-space-3);
    }
    .prov {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: var(--vd-font-size-xs);
      color: var(--vd-color-text-muted);
    }
    .prov :where(.i) {
      width: 0.9rem;
      height: 0.9rem;
    }
    .note {
      white-space: normal;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProvenanceMarkerComponent {
  private readonly format = inject(Formatter);

  readonly kind = input<ProvenanceKind>('product');
  readonly provenance = input<ProductProvenance | null>(null);
  /** Adds the "identification only" line for label snapshots (product detail). */
  readonly detail = input(false);

  protected readonly snapshotDate = computed(() => {
    const date = this.provenance()?.sourceEntryDate;
    return date ? this.format.date(date) : null;
  });
}
