import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ProductNutrient, UlBasis } from '../../../core/api/api-types';
import { IconComponent } from '../../../shared/icon/icon.component';
import { Formatter } from '../../../core/format/formatter';
import { ReferenceDataStore, setLabel } from '../../results/shared/reference-data.store';

/** "Of reference" cell: the server's percentage and the reference it was computed against. */
interface ReferenceCell {
  readonly percent: string;
  readonly amount: string;
  readonly set: string;
}

/** "Upper limit" cell: the matched limit of the country's UL set, as the server sent it. */
interface LimitCell {
  readonly amount: string;
  readonly basis: UlBasis;
  readonly set: string;
  readonly note: string | null;
  readonly noteId: string;
  /** UL-relevant amount per day, only when it differs from the scored amount per day. */
  readonly ulAmount: string | null;
  readonly exceeded: boolean;
}

interface FormulationRow {
  readonly code: string;
  readonly name: string | null;
  readonly form: string | null;
  readonly conversionAssumed: boolean;
  readonly perServing: string;
  readonly perDay: string;
  readonly reference: ReferenceCell | null;
  readonly limit: LimitCell | null;
  readonly exceeded: boolean;
  readonly highlighted: boolean;
}

let nextId = 0;

/** DOM id of the formulation row of a nutrient (target of the `EXCEEDS_UL` flag link). */
export function nutrientRowId(code: string): string {
  return `nutrient-row-${code}`;
}

/**
 * Formulation table of the product detail page (design hand-off §4.6): nutrient code and
 * registry name, the form printed on the label, amounts per serving and per day in canonical
 * units, the share of the country's reference and the applicable upper limit.
 *
 * Reference and upper-limit cells render the `reference` and `upperLimit` facts of each
 * `ProductNutrient` as sent by the API: the percentage, the limit and the `exceeded` decision
 * are never computed here. A missing fact (`null` or absent) shows the muted empty marker.
 * The citation note of a limit opens as an inline disclosure, so it stays reachable by
 * keyboard and inside the horizontally scrolling table on narrow screens.
 */
@Component({
  selector: 'vd-formulation-table',
  imports: [IconComponent, TranslocoPipe],
  template: `
    <div
      class="tbl-wrap"
      role="region"
      tabindex="0"
      [attr.aria-label]="'product.formulation.caption' | transloco: { name: productName() }"
    >
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
            <th scope="col" class="r">{{ 'product.formulation.of_reference' | transloco }}</th>
            <th scope="col" class="r">{{ 'product.formulation.upper_limit' | transloco }}</th>
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
                  <span class="nut-head">
                    @if (row.exceeded) {
                      <span class="warn">
                        <vd-icon name="warn" size="sm" />
                        <span class="sr-only">{{
                          'product.formulation.exceeded' | transloco
                        }}</span>
                      </span>
                    }
                    <span class="ncode">{{ row.code }}</span>
                  </span>
                  @if (row.name) {
                    <span class="nut-name text-xs muted">{{ row.name }}</span>
                  }
                </span>
              </td>
              <td class="form">
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
              <td class="r" data-col="reference">
                @if (row.reference; as ref) {
                  <span class="cell">
                    <span class="main" data-part="percent">{{ ref.percent }}</span>&ngsp;
                    <span class="sub" data-part="reference">{{
                      'product.formulation.reference_of'
                        | transloco: { amount: ref.amount, set: ref.set }
                    }}</span>
                  </span>
                } @else {
                  <span class="muted" data-empty="reference">{{
                    'product.formulation.reference_none' | transloco
                  }}</span>
                }
              </td>
              <td class="r" data-col="upper-limit">
                @if (row.limit; as limit) {
                  <span class="cell">
                    <span class="main" [class.over]="limit.exceeded">
                      <span data-part="limit">{{ limit.amount }}</span>
                      @if (limit.exceeded) {
                        <span class="over-mark" data-marker="exceeded">
                          <span aria-hidden="true">·</span>
                          <vd-icon name="warn" size="sm" />
                          {{ 'product.formulation.ul_exceeded' | transloco }}
                        </span>
                      }</span
                    >&ngsp;
                    <span class="meta">
                      <span class="sub" data-part="basis">
                        {{ 'product.formulation.ul_basis.' + limit.basis | transloco }} ·
                        {{ limit.set }}
                      </span>
                      @if (limit.note) {
                        <button
                          type="button"
                          class="src-btn"
                          [attr.aria-expanded]="isOpen(row.code)"
                          [attr.aria-controls]="limit.noteId"
                          [attr.aria-label]="
                            'product.formulation.ul_source_label' | transloco: { code: row.code }
                          "
                          (click)="toggle(row.code)"
                        >
                          <vd-icon name="info" size="sm" />
                          <span>{{ 'product.formulation.ul_source' | transloco }}</span>
                        </button>
                      }
                    </span>
                    @if (limit.ulAmount; as ulAmount) {
                      &ngsp;<span class="sub" data-part="ul-amount">{{
                        'product.formulation.ul_amount' | transloco: { amount: ulAmount }
                      }}</span>
                    }
                    @if (limit.note; as note) {
                      <span class="note" [id]="limit.noteId" [hidden]="!isOpen(row.code)">{{
                        note
                      }}</span>
                    }
                  </span>
                } @else {
                  <span class="muted" data-empty="upper-limit">{{
                    'product.formulation.ul_none' | transloco
                  }}</span>
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="muted">{{ 'product.formulation.empty' | transloco }}</td>
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
    .tbl-wrap:focus-visible {
      outline-offset: 2px;
    }
    .nut {
      display: inline-flex;
      flex-direction: column;
      gap: 1px;
      min-width: 7.5rem;
      white-space: nowrap;
    }
    /* Registry and label text may hold a long token: it breaks inside the column (design
       hand-off: the page never scrolls sideways) while ordinary names stay on one line. */
    .nut-name {
      white-space: normal;
      overflow-wrap: anywhere;
    }
    td.form {
      min-width: 8rem;
      overflow-wrap: anywhere;
    }
    .nut-head {
      display: inline-flex;
      align-items: center;
      gap: var(--vd-space-1);
    }
    .warn {
      display: inline-flex;
      color: var(--vd-flag-warning);
    }
    .conv {
      margin-left: var(--vd-space-2);
      /* The note may wrap inside its column instead of widening the table. */
      white-space: normal;
    }
    tr:focus-visible {
      outline-offset: -3px;
    }
    .cell {
      display: inline-flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 2px;
    }
    .sub {
      font-size: var(--vd-font-size-xs);
      color: var(--vd-color-text-muted);
    }
    .over {
      color: var(--vd-flag-warning);
      font-weight: var(--vd-font-weight-semibold);
    }
    .over-mark {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      margin-left: 3px;
    }
    .over-mark .i {
      width: 0.875rem;
      height: 0.875rem;
      stroke-width: 2;
    }
    td[data-col='upper-limit'] {
      white-space: normal;
    }
    .meta {
      display: inline-flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      align-items: center;
      gap: 0 var(--vd-space-2);
    }
    .meta .sub,
    .main {
      white-space: nowrap;
    }
    .src-btn {
      position: relative;
      display: inline-flex;
      align-items: center;
      gap: 3px;
      min-height: 1.25rem;
      padding: 0 2px;
      border: 0;
      border-radius: var(--vd-radius-sm);
      background: none;
      color: var(--vd-color-accent);
      font: inherit;
      font-size: var(--vd-font-size-xs);
      font-weight: var(--vd-font-weight-semibold);
      cursor: pointer;
    }
    .src-btn:hover {
      text-decoration: underline;
    }
    .src-btn .i {
      width: 0.875rem;
      height: 0.875rem;
    }
    .note {
      max-width: 18rem;
      white-space: normal;
      text-align: left;
      font-size: var(--vd-font-size-xs);
      line-height: 1.45;
      color: var(--vd-color-text);
      margin-top: var(--vd-space-1);
      padding: var(--vd-space-2) var(--vd-space-3);
      border: 1px solid var(--vd-color-border);
      border-radius: var(--vd-radius-md);
      background: var(--vd-color-surface);
    }
    .note[hidden] {
      display: none;
    }
    @media (max-width: 48rem) {
      /* 44 px touch target without making the row taller. */
      .src-btn::after {
        content: '';
        position: absolute;
        inset: -0.75rem -0.5rem;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormulationTableComponent {
  private readonly reference = inject(ReferenceDataStore);
  private readonly format = inject(Formatter);
  private readonly instance = nextId++;

  readonly nutrients = input.required<readonly ProductNutrient[]>();
  readonly productName = input.required<string>();
  /** Nutrient codes the flag link jumped to. */
  readonly highlight = input<readonly string[]>([]);

  protected readonly rowId = nutrientRowId;
  private readonly openNotes = signal<ReadonlySet<string>>(new Set());

  protected readonly rows = computed<readonly FormulationRow[]>(() =>
    this.nutrients().map((entry) => {
      const nutrient = this.reference.nutrient(entry.code);
      const registryForm = nutrient?.forms?.find((form) => form.code === entry.formCode)?.nameEn;
      const reference = entry.reference ?? null;
      const limit = entry.upperLimit ?? null;
      return {
        code: entry.code,
        name: nutrient?.nameEn ?? null,
        form: entry.formLabel ?? registryForm ?? null,
        conversionAssumed: entry.conversionAssumed,
        perServing: this.format.quantity(entry.amountPerServing, entry.unit),
        perDay: this.format.quantity(entry.amountPerDay, entry.unit),
        reference: reference
          ? {
              percent: this.format.percentValue(reference.percentOfReference),
              amount: this.format.quantity(reference.amount, reference.unit),
              set: setLabel(reference.setCode),
            }
          : null,
        limit: limit
          ? {
              amount: this.format.quantity(limit.amount, limit.unit),
              basis: limit.basis,
              set: setLabel(limit.setCode),
              note: limit.note?.trim() ? limit.note : null,
              noteId: `vd-ul-note-${this.instance}-${entry.code}`,
              ulAmount:
                limit.ulAmountPerDay !== entry.amountPerDay
                  ? this.format.quantity(limit.ulAmountPerDay, limit.unit)
                  : null,
              exceeded: limit.exceeded,
            }
          : null,
        exceeded: limit?.exceeded === true,
        highlighted: this.highlight().includes(entry.code),
      };
    }),
  );

  protected isOpen(code: string): boolean {
    return this.openNotes().has(code);
  }

  protected toggle(code: string): void {
    this.openNotes.update((open) => {
      const next = new Set(open);
      if (!next.delete(code)) {
        next.add(code);
      }
      return next;
    });
  }
}
