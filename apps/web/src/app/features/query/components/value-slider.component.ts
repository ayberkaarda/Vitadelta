import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

/** Ratio of a value inside [min, max], clamped to 0..1. */
export function sliderFraction(value: number, min: number, max: number): number {
  if (!(max > min)) {
    return 0;
  }
  return Math.min(1, Math.max(0, (value - min) / (max - min)));
}

/** Snaps `value` to the step grid inside [min, max] without floating-point noise. */
export function snapToStep(value: number, min: number, max: number, step: number): number {
  const clamped = Math.min(max, Math.max(min, value));
  if (!(step > 0)) {
    return clamped;
  }
  const decimals = (String(step).split('.')[1] ?? '').length;
  const snapped = min + Math.round((clamped - min) / step) * step;
  return Number(Math.min(max, Math.max(min, snapped)).toFixed(decimals));
}

/**
 * Accessible custom slider (design hand-off §3 "Slider", §6): `role="slider"` with
 * `aria-valuemin/max/now/text`, labelled by an external element. Keys: Left/Down −step,
 * Right/Up +step, PageDown/PageUp ∓ 5 steps, Home min, End max. Pointer: click or drag on the
 * 44 px rail. Optional end labels and an `<output>` with the formatted value.
 *
 * ```html
 * <vd-value-slider controlId="q-lambda" labelledBy="q-lambda-label" [value]="0.5" [min]="0"
 *   [max]="1" [step]="0.05" [valueText]="'0.50'" (valueChange)="lambda.set($event)" />
 * ```
 */
@Component({
  selector: 'vd-value-slider',
  template: `
    @if (startLabel(); as start) {
      <span class="end" aria-hidden="true">{{ start }}</span>
    }
    <div
      #rail
      class="rail"
      (pointerdown)="onPointerDown($event)"
      (pointermove)="onPointerMove($event)"
      (pointerup)="dragging.set(false)"
      (pointercancel)="dragging.set(false)"
    >
      <span class="tr"></span>
      <span class="fl" [style.width.%]="percent()"></span>
      @if (ticks()) {
        @for (tick of tickList; track tick) {
          <span class="tick" [style.left.%]="tick"></span>
        }
      }
      <span
        #thumb
        class="th"
        role="slider"
        tabindex="0"
        [id]="controlId()"
        [style.left.%]="percent()"
        [attr.aria-valuemin]="min()"
        [attr.aria-valuemax]="max()"
        [attr.aria-valuenow]="value()"
        [attr.aria-valuetext]="valueText()"
        [attr.aria-labelledby]="labelledBy()"
        [attr.aria-describedby]="describedBy()"
        [attr.aria-invalid]="invalid() ? 'true' : null"
        (keydown)="onKeydown($event)"
      ></span>
    </div>
    @if (endLabel(); as end) {
      <span class="end" aria-hidden="true">{{ end }}</span>
    }
    @if (showOutput()) {
      <output class="out" [attr.for]="controlId()" aria-hidden="true">{{ valueText() }}</output>
    }
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: var(--vd-space-3);
      min-width: 0;
    }
    .rail {
      position: relative;
      flex: 1 1 auto;
      min-width: 6rem;
      height: 2.75rem;
      cursor: pointer;
      touch-action: none;
    }
    .tr,
    .fl {
      position: absolute;
      top: 50%;
      left: 0;
      height: 6px;
      margin-top: -3px;
      border-radius: 3px;
    }
    .tr {
      right: 0;
      background: var(--vd-bar-track);
    }
    .fl {
      background: var(--vd-color-accent);
    }
    .tick {
      position: absolute;
      top: calc(50% + 8px);
      width: 1px;
      height: 5px;
      background: var(--vd-color-border-strong);
    }
    .th {
      position: absolute;
      top: 50%;
      width: 1.25rem;
      height: 1.25rem;
      margin: -0.625rem 0 0 -0.625rem;
      border-radius: 50%;
      background: var(--vd-color-surface);
      border: 2px solid var(--vd-color-accent);
      box-shadow: var(--vd-shadow-raised);
    }
    .th[aria-invalid='true'] {
      border-color: var(--vd-color-danger);
    }
    .end {
      font-size: var(--vd-font-size-xs);
      color: var(--vd-color-text-muted);
      white-space: nowrap;
    }
    @media (max-width: 48rem) {
      .end {
        display: none;
      }
    }
    .out {
      min-width: 3.25rem;
      padding: 2px 6px;
      border: 1px solid var(--vd-color-border);
      border-radius: var(--vd-radius-sm);
      text-align: center;
      font-variant-numeric: tabular-nums;
      font-weight: var(--vd-font-weight-semibold);
      font-size: var(--vd-font-size-sm);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ValueSliderComponent {
  private readonly rail = viewChild.required<ElementRef<HTMLElement>>('rail');
  private readonly thumb = viewChild.required<ElementRef<HTMLElement>>('thumb');

  readonly controlId = input.required<string>();
  /** Id of the element naming the slider. */
  readonly labelledBy = input.required<string>();
  readonly describedBy = input<string | null>(null);
  readonly value = input.required<number>();
  readonly min = input.required<number>();
  readonly max = input.required<number>();
  readonly step = input.required<number>();
  /** Translated / formatted value for `aria-valuetext` and the output box. */
  readonly valueText = input.required<string>();
  readonly startLabel = input<string | null>(null);
  readonly endLabel = input<string | null>(null);
  readonly showOutput = input(false);
  readonly ticks = input(false);
  readonly invalid = input(false);
  readonly valueChange = output<number>();

  protected readonly tickList = [0, 25, 50, 75, 100];
  protected readonly dragging = signal(false);
  protected readonly percent = computed(
    () => sliderFraction(this.value(), this.min(), this.max()) * 100,
  );

  protected onKeydown(event: KeyboardEvent): void {
    const step = this.step();
    const deltas: Readonly<Record<string, number>> = {
      ArrowLeft: -step,
      ArrowDown: -step,
      ArrowRight: step,
      ArrowUp: step,
      PageDown: -5 * step,
      PageUp: 5 * step,
    };
    let next: number;
    if (event.key === 'Home') {
      next = this.min();
    } else if (event.key === 'End') {
      next = this.max();
    } else if (event.key in deltas) {
      next = this.value() + deltas[event.key];
    } else {
      return;
    }
    event.preventDefault();
    this.emit(next);
  }

  protected onPointerDown(event: PointerEvent): void {
    if (event.button !== 0) {
      return;
    }
    this.dragging.set(true);
    const rail = this.rail().nativeElement;
    rail.setPointerCapture?.(event.pointerId);
    this.thumb().nativeElement.focus();
    this.fromPointer(event);
  }

  protected onPointerMove(event: PointerEvent): void {
    if (this.dragging()) {
      this.fromPointer(event);
    }
  }

  private fromPointer(event: PointerEvent): void {
    const rect = this.rail().nativeElement.getBoundingClientRect();
    if (rect.width <= 0) {
      return;
    }
    const fraction = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    this.emit(this.min() + fraction * (this.max() - this.min()));
  }

  private emit(raw: number): void {
    const next = snapToStep(raw, this.min(), this.max(), this.step());
    if (next !== this.value()) {
      this.valueChange.emit(next);
    }
  }
}
