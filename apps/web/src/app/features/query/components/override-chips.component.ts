import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Nutrient, NutrientOverrides } from '../../../core/api/api-types';
import { Formatter } from '../../../core/format/formatter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { snapToStep } from './value-slider.component';

const STEP = 0.1;

/**
 * Per-nutrient weight overrides (design hand-off §3 "Override chips"): a list of chips with the
 * mono code and the multiplier (`×0` struck through with "not scored"), an edit mode with an
 * inline number input (Enter / blur commits, Esc cancels) and a remove button per chip, and an
 * "Add nutrient" combobox over the registry (ARIA combobox + listbox pattern).
 */
@Component({
  selector: 'vd-override-chips',
  imports: [IconComponent, TranslocoPipe],
  templateUrl: './override-chips.component.html',
  styleUrl: './override-chips.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverrideChipsComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly format = inject(Formatter);

  readonly overrides = input.required<NutrientOverrides>();
  readonly nutrients = input.required<readonly Nutrient[]>();
  readonly min = input(0);
  readonly max = input(3);
  /** Value a new override starts with (`overrideDefault`). */
  readonly defaultValue = input(1);
  readonly describedBy = input<string | null>(null);
  readonly changed = output<NutrientOverrides>();

  protected readonly editing = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly search = signal('');
  protected readonly active = signal(0);
  private focusTarget: string | null = null;

  protected readonly chips = computed(() =>
    Object.keys(this.overrides())
      .sort()
      .map((code) => ({
        code,
        value: this.overrides()[code],
        name: this.nutrients().find((n) => n.code === code)?.nameEn ?? code,
      })),
  );

  protected readonly options = computed(() => {
    const used = new Set(Object.keys(this.overrides()));
    const term = this.search().trim().toLowerCase();
    return this.nutrients().filter(
      (n) =>
        !used.has(n.code) &&
        (term === '' ||
          n.code.toLowerCase().includes(term) ||
          n.nameEn.toLowerCase().includes(term)),
    );
  });

  constructor() {
    afterRenderEffect(() => {
      this.editing();
      this.adding();
      if (this.focusTarget) {
        const element = this.host.nativeElement.querySelector<HTMLElement>(this.focusTarget);
        this.focusTarget = null;
        element?.focus();
        if (element instanceof HTMLInputElement) {
          element.select();
        }
      }
    });
  }

  protected multiplier(value: number): string {
    return `×${this.format.amount(value, 1)}`;
  }

  protected startEdit(code: string): void {
    this.focusTarget = `#q-o-${code}`;
    this.editing.set(code);
  }

  protected commit(code: string, raw: string): void {
    if (this.editing() !== code) {
      return;
    }
    const parsed = Number(raw.replace(',', '.'));
    this.editing.set(null);
    this.focusTarget = `#q-o-btn-${code}`;
    if (raw.trim() === '' || !Number.isFinite(parsed)) {
      return;
    }
    const value = snapToStep(parsed, this.min(), this.max(), STEP);
    this.changed.emit({ ...this.overrides(), [code]: value });
  }

  protected cancelEdit(code: string): void {
    this.editing.set(null);
    this.focusTarget = `#q-o-btn-${code}`;
  }

  protected remove(code: string): void {
    const next = Object.fromEntries(
      Object.entries(this.overrides()).filter(([key]) => key !== code),
    );
    this.focusTarget = '#q-overrides-add';
    this.changed.emit(next);
  }

  protected openAdd(): void {
    this.search.set('');
    this.active.set(0);
    this.focusTarget = '#q-overrides-search';
    this.adding.set(true);
  }

  protected closeAdd(): void {
    this.adding.set(false);
    this.focusTarget = '#q-overrides-add';
  }

  protected onSearch(value: string): void {
    this.search.set(value);
    this.active.set(0);
  }

  protected onComboKey(event: KeyboardEvent): void {
    const count = this.options().length;
    if (event.key === 'ArrowDown' && count > 0) {
      event.preventDefault();
      this.active.set((this.active() + 1) % count);
    } else if (event.key === 'ArrowUp' && count > 0) {
      event.preventDefault();
      this.active.set((this.active() - 1 + count) % count);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const option = this.options()[this.active()];
      if (option) {
        this.pick(option.code);
      }
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.closeAdd();
    }
  }

  protected pick(code: string): void {
    this.adding.set(false);
    this.changed.emit({ ...this.overrides(), [code]: this.defaultValue() });
    this.startEdit(code);
  }
}
