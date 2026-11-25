import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  inject,
  input,
  output,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { INPUT_UNITS, InputUnit, Nutrient } from '../../../core/api/api-types';
import { IconComponent } from '../../../shared/icon/icon.component';

/** One editable row; `min` keeps the raw text so invalid input can be shown and fixed. */
export interface RequiredRow {
  readonly key: number;
  readonly code: string;
  readonly min: string;
  readonly unit: InputUnit | '';
}

/** The only nutrient for which the contract accepts `IU` as input unit. */
export const IU_NUTRIENT = 'VIT_D';

/** Units offered for a nutrient (`IU` only for vitamin D, as the contract allows). */
export function unitsFor(code: string): readonly InputUnit[] {
  return INPUT_UNITS.filter((unit) => unit !== 'IU' || code === IU_NUTRIENT);
}

/**
 * Required nutrients editor (design hand-off §3): one row per nutrient with the registry select,
 * an optional minimum per day and its unit (disabled while the minimum is empty), a remove button,
 * and "Add required nutrient". Field errors are passed in by control id
 * (`q-req-<index>-code|min|unit`) and announced through `aria-describedby`.
 */
@Component({
  selector: 'vd-required-nutrients-editor',
  imports: [IconComponent, TranslocoPipe],
  templateUrl: './required-nutrients-editor.component.html',
  styleUrl: './required-nutrients-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RequiredNutrientsEditorComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly rows = input.required<readonly RequiredRow[]>();
  readonly nutrients = input.required<readonly Nutrient[]>();
  /** i18n key of the error per control id. */
  readonly errors = input<ReadonlyMap<string, string>>(new Map());
  readonly rowsChange = output<RequiredRow[]>();

  private nextKey = 1_000;
  private focusTarget: string | null = null;

  constructor() {
    afterRenderEffect(() => {
      this.rows();
      if (this.focusTarget) {
        this.host.nativeElement.querySelector<HTMLElement>(this.focusTarget)?.focus();
        this.focusTarget = null;
      }
    });
  }

  protected unitsFor = unitsFor;

  protected nameOf(code: string): string {
    return this.nutrients().find((n) => n.code === code)?.nameEn ?? code;
  }

  /** Registry nutrients not used by another row. */
  protected choices(row: RequiredRow): readonly Nutrient[] {
    const used = new Set(
      this.rows()
        .filter((r) => r.key !== row.key)
        .map((r) => r.code),
    );
    return this.nutrients().filter((n) => !used.has(n.code));
  }

  protected describedBy(id: string): string | null {
    return this.errors().has(id) ? `${id}-error` : null;
  }

  protected update(index: number, change: Partial<RequiredRow>): void {
    const rows = this.rows().map((row, i) => {
      if (i !== index) {
        return row;
      }
      const next = { ...row, ...change };
      if (next.unit === 'IU' && next.code !== IU_NUTRIENT) {
        return { ...next, unit: '' as const };
      }
      return next;
    });
    this.rowsChange.emit(rows);
  }

  protected remove(index: number): void {
    const rows = this.rows().filter((_, i) => i !== index);
    this.focusTarget = rows.length > 0 ? `#q-req-${Math.max(0, index - 1)}-code` : '#q-req-add';
    this.rowsChange.emit(rows);
  }

  protected add(): void {
    const used = new Set(this.rows().map((row) => row.code));
    const first = this.nutrients().find((n) => !used.has(n.code));
    if (!first) {
      return;
    }
    const rows = [
      ...this.rows(),
      { key: this.nextKey++, code: first.code, min: '', unit: '' as const },
    ];
    this.focusTarget = `#q-req-${rows.length - 1}-code`;
    this.rowsChange.emit(rows);
  }
}
