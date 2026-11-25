import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  computed,
  inject,
  input,
} from '@angular/core';

import { IconComponent } from '../icon/icon.component';

/**
 * Form field (design hand-off §3 "Fields"): label above, the projected control, hint below and
 * an error line in a polite live region, so errors are announced when they appear
 * (project specification §10.5). Put `vdControl` on the native control to wire `id`,
 * `aria-describedby` and `aria-invalid`.
 *
 * ```html
 * <vd-field controlId="email" [label]="'auth.email' | transloco" [error]="emailError()">
 *   <input vdControl class="inp" type="email" autocomplete="email" [formControl]="email" />
 * </vd-field>
 * ```
 */
@Component({
  selector: 'vd-field',
  imports: [IconComponent],
  template: `
    <div class="fld">
      <label class="lbl" [attr.for]="controlId()">{{ label() }}</label>
      <ng-content />
      @if (hint()) {
        <span class="hint" [id]="hintId()">{{ hint() }}</span>
      }
      <span class="err-slot" [id]="errorId()" aria-live="polite">
        @if (error()) {
          <span class="err"><vd-icon name="warn" />{{ error() }}</span>
        }
      </span>
    </div>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .err-slot:empty {
      display: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FieldComponent {
  /** Id of the projected control; server `FieldError.field` paths map to these ids. */
  readonly controlId = input.required<string>();
  /** Translated label text. */
  readonly label = input.required<string>();
  /** Translated hint text. */
  readonly hint = input<string | null>(null);
  /** Translated error text; `null` when valid. */
  readonly error = input<string | null>(null);

  readonly hintId = computed(() => `${this.controlId()}-hint`);
  readonly errorId = computed(() => `${this.controlId()}-error`);
  readonly describedBy = computed(() => {
    const ids = [this.errorId()];
    if (this.hint()) {
      ids.unshift(this.hintId());
    }
    return ids.join(' ');
  });
  readonly invalid = computed(() => !!this.error());
}

/** Connects a native control to its enclosing {@link FieldComponent}. */
@Directive({
  selector: '[vdControl]',
  host: {
    '[id]': 'field.controlId()',
    '[attr.aria-describedby]': 'field.describedBy()',
    '[attr.aria-invalid]': 'field.invalid() ? "true" : null',
  },
})
export class ControlDirective {
  protected readonly field = inject(FieldComponent);
}
