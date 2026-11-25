import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { AlertTrigger } from '../../../core/api/api-types';
import { SessionStore } from '../../../core/auth/session.store';
import { ControlDirective, FieldComponent } from '../../../shared/form/field.component';
import {
  ProductActionState,
  ProductActionsService,
} from '../../results/shared/product-actions.service';

let nextId = 0;

const TRIGGERS: readonly AlertTrigger[] = ['PRICE_DROP', 'BACK_IN_STOCK', 'PRICE_CHANGE'];
/** `ThresholdPercent` of the contract: 0.01 to 99.99 with at most two decimals. */
const THRESHOLD_PATTERN = /^\d{1,2}(?:[.,]\d{1,2})?$/;
const DEFAULT_THRESHOLD = '5';

/** Parses a threshold typed by the user; `null` when it is outside the contract range. */
export function parseThreshold(raw: string): number | null {
  const trimmed = raw.trim();
  if (!THRESHOLD_PATTERN.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed.replace(',', '.'));
  return value >= 0.01 && value <= 99.99 ? value : null;
}

/**
 * "Alert me when" form of the product page (design hand-off §4.6): one radio card per trigger,
 * the price-drop threshold (shape checked locally, range and decimals as in the contract; the
 * server stays the authority), the server's alert usage and Create / Cancel. Emits `done` with
 * `true` after the alert was created or reactivated, `false` on cancel.
 */
@Component({
  selector: 'vd-alert-form',
  imports: [ControlDirective, FieldComponent, TranslocoPipe],
  template: `
    <form class="card stack-3" novalidate (submit)="submit($event)">
      <fieldset class="fieldset">
        <legend class="h4">{{ 'product.alert.title' | transloco }}</legend>
        @for (trigger of triggers; track trigger) {
          <div class="opt" [class.is-checked]="selected() === trigger">
            <label class="opt-head">
              <input
                type="radio"
                [name]="groupName"
                [value]="trigger"
                [checked]="selected() === trigger"
                [attr.data-trigger]="trigger"
                (change)="selected.set(trigger)"
              />
              <span class="strong">{{ 'product.alert.' + trigger | transloco }}</span>
            </label>
            @if (trigger === 'PRICE_DROP' && selected() === 'PRICE_DROP') {
              <div class="threshold">
                <vd-field
                  [controlId]="thresholdId"
                  [label]="'product.alert.threshold_label' | transloco"
                  [error]="thresholdError() ? ('product.alert.threshold_error' | transloco) : null"
                >
                  <span class="row-2">
                    <input
                      vdControl
                      class="inp num"
                      type="text"
                      inputmode="decimal"
                      autocomplete="off"
                      [value]="threshold()"
                      (input)="onThreshold($event)"
                    />
                    <span aria-hidden="true">%</span>
                  </span>
                </vd-field>
                @if (price(); as p) {
                  <p class="text-xs muted">
                    {{ 'product.alert.from_price' | transloco: { price: p } }}
                  </p>
                }
              </div>
            }
          </div>
        }
      </fieldset>

      @if (session.usage(); as usage) {
        @let meter = usage.alertSubscriptions;
        @let planName = planKey() | transloco;
        <p class="text-xs muted" data-usage>
          @if (meter.limit === null) {
            {{ 'product.alert.usage_unlimited' | transloco: { used: meter.used, plan: planName } }}
          } @else {
            {{
              'product.alert.usage'
                | transloco: { used: meter.used, limit: meter.limit, plan: planName }
            }}
          }
        </p>
      }

      <div class="row-2">
        <button
          type="submit"
          class="btn btn-primary"
          [attr.aria-busy]="state().busy() === 'alert' ? 'true' : null"
        >
          {{ 'product.alert.submit' | transloco }}
        </button>
        <button type="button" class="btn btn-ghost" (click)="done.emit(false)">
          {{ 'common.cancel' | transloco }}
        </button>
      </div>
    </form>
  `,
  styles: `
    :host {
      display: block;
    }
    .opt {
      border: 1px solid var(--vd-color-border-strong);
      border-radius: var(--vd-radius-md);
      padding: var(--vd-space-3) var(--vd-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--vd-space-2);
    }
    .opt.is-checked {
      border-color: var(--vd-color-accent);
      background: var(--vd-color-accent-bg);
      box-shadow: inset 0 0 0 1px var(--vd-color-accent);
    }
    .opt-head {
      display: flex;
      align-items: center;
      gap: var(--vd-space-3);
      cursor: pointer;
      font-size: var(--vd-font-size-sm);
    }
    .threshold {
      padding-left: calc(1.125rem + var(--vd-space-3));
      display: flex;
      flex-direction: column;
      gap: var(--vd-space-1);
    }
    .threshold .inp {
      width: 5rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertFormComponent {
  protected readonly session = inject(SessionStore);
  private readonly actions = inject(ProductActionsService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly state = input.required<ProductActionState>();
  /** Today's effective price, formatted, for the "from today's …" line. */
  readonly price = input<string | null>(null);
  readonly done = output<boolean>();

  protected readonly triggers = TRIGGERS;
  protected readonly groupName = `vd-alert-trigger-${nextId}`;
  protected readonly thresholdId = `vd-alert-threshold-${nextId++}`;
  protected readonly selected = signal<AlertTrigger>('PRICE_DROP');
  protected readonly threshold = signal(DEFAULT_THRESHOLD);
  private readonly submitted = signal(false);
  protected readonly planKey = computed(() => `plan.${this.session.plan() ?? 'FREE'}`);

  /** Local shape check after a submit attempt, or the server's field error for the threshold. */
  protected readonly thresholdError = computed(() => {
    const problem = this.state().problem();
    const serverError =
      problem?.code === 'validation_failed' &&
      problem.errors.some((error) => error.field === 'thresholdPercent');
    return serverError || (this.submitted() && parseThreshold(this.threshold()) === null);
  });

  constructor() {
    afterNextRender(() => {
      this.host.nativeElement
        .querySelector<HTMLInputElement>('input[type="radio"]:checked')
        ?.focus();
    });
  }

  protected onThreshold(event: Event): void {
    this.threshold.set((event.target as HTMLInputElement).value);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.submitted.set(true);
    const trigger = this.selected();
    let threshold: number | null = null;
    if (trigger === 'PRICE_DROP') {
      threshold = parseThreshold(this.threshold());
      if (threshold === null) {
        this.host.nativeElement.querySelector<HTMLInputElement>(`#${this.thresholdId}`)?.focus();
        return;
      }
    }
    const created = await this.actions.createAlert(this.state(), trigger, threshold);
    if (created) {
      this.done.emit(true);
    }
  }
}
