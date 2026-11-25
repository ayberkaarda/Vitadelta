import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ProductNutrient } from '../../../core/api/api-types';
import { IconComponent } from '../../../shared/icon/icon.component';
import { Formatter } from '../../../core/format/formatter';
import { ReferenceDataStore } from '../../results/shared/reference-data.store';

interface FormulationRow {
  readonly code: string;
  readonly name: string | null;
  readonly form: string | null;
  readonly conversionAssumed: boolean;
  readonly perServing: string;
  readonly perDay: string;
  readonly exceeded: boolean;
  readonly highlighted: boolean;
}

/** DOM id of the formulation row of a nutrient (target of the `EXCEEDS_UL` flag link). */
export function nutrientRowId(code: string): string {
  return `nutrient-row-${code}`;
}

/**
 * Formulation table of the product detail page (design hand-off §4.6): nutrient code and
 * registry name, the form printed on the label, amounts per serving and per day in canonical
 * units. Rows named by an `EXCEEDS_UL` flag carry the warning marker; `highlight` marks the rows
 * the flag link jumped to.
 */
@Component({
  selector: 'vd-formulation-table',
  imports: [IconComponent, TranslocoPipe],
  template: `
    <div class="tbl-wrap">
      <table class="tbl">
        <caption class="sr-only">
          {{
            'product.formulation.caption' | transloco: { name: productName() }
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ 'product.formulation.nutrient' | transloco }}</th>
            <th scope="col">{{ 'product.formulation.form' | transloco }}</th>
            <th scope="col" class="r">{{ 'product.formulation.per_serving' | transloco }}</th>
            <th scope="col" class="r">{{ 'product.formulation.per_day' | transloco }}</th>
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.code) {
            <tr
              [id]="rowId(row.code)"
              tabindex="-1"
              [class.is-hl]="row.exceeded || row.highlighted"
              [attr.data-code]="row.code"
            >
              <td>
                <span class="nut">
                  @if (row.exceeded) {
                    <span class="warn">
                      <vd-icon name="warn" size="sm" />
                      <span class="sr-only">{{ 'product.formulation.exceeded' | transloco }}</span>
                    </span>
                  }
                  <span class="ncode">{{ row.code }}</span>
                  @if (row.name) {
                    <span class="text-xs muted">{{ row.name }}</span>
                  }
                </span>
              </td>
              <td>
                {{ row.form ?? ('common.none' | transloco) }}
                @if (row.conversionAssumed) {
                  <span class="badge t-info conv">
                    <vd-icon name="info" />
                    <span>{{ 'product.formulation.conversion_assumed' | transloco }}</span>
                  </span>
                }
              </td>
              <td class="r">{{ row.perServing }}</td>
              <td class="r" [class.strong]="row.exceeded">{{ row.perDay }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="4" class="muted">{{ 'product.formulation.empty' | transloco }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .nut {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--vd-space-1) var(--vd-space-2);
    }
    .warn {
      color: var(--vd-flag-warning);
      align-self: center;
    }
    .conv {
      margin-left: var(--vd-space-2);
    }
    tr:focus-visible {
      outline-offset: -3px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormulationTableComponent {
  private readonly reference = inject(ReferenceDataStore);
  private readonly format = inject(Formatter);

  readonly nutrients = input.required<readonly ProductNutrient[]>();
  readonly productName = input.required<string>();
  /** Nutrient codes of the server's `EXCEEDS_UL` flag. */
  readonly exceeded = input<readonly string[]>([]);
  /** Nutrient codes the flag link jumped to. */
  readonly highlight = input<readonly string[]>([]);

  protected readonly rowId = nutrientRowId;

  protected readonly rows = computed<readonly FormulationRow[]>(() =>
    this.nutrients().map((entry) => {
      const nutrient = this.reference.nutrient(entry.code);
      const registryForm = nutrient?.forms?.find((form) => form.code === entry.formCode)?.nameEn;
      return {
        code: entry.code,
        name: nutrient?.nameEn ?? null,
        form: entry.formLabel ?? registryForm ?? null,
        conversionAssumed: entry.conversionAssumed,
        perServing: this.format.quantity(entry.amountPerServing, entry.unit),
        perDay: this.format.quantity(entry.amountPerDay, entry.unit),
        exceeded: this.exceeded().includes(entry.code),
        highlighted: this.highlight().includes(entry.code),
      };
    }),
  );
}
