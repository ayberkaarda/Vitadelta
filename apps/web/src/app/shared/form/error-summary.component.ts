import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  afterRenderEffect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { IconComponent } from '../icon/icon.component';
import { FieldErrorEntry } from './field-errors';

/**
 * Error summary shown after a failed submit (design hand-off §3 "Fields"): `role="alert"`,
 * focusable and focused when it appears, with one link per invalid field that moves focus to
 * the control.
 *
 * ```html
 * <vd-error-summary [errors]="fieldErrors()" />
 * ```
 */
@Component({
  selector: 'vd-error-summary',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @if (errors().length > 0) {
      <div #box class="notice t-bad" role="alert" tabindex="-1">
        <vd-icon name="warn" />
        <div class="body">
          <p class="nt">
            {{
              (errors().length === 1 ? 'validation.summary_title_one' : 'validation.summary_title')
                | transloco: { count: errors().length }
            }}
          </p>
          <ul class="list">
            @for (entry of errors(); track entry.controlId) {
              <li>
                <button type="button" class="link" (click)="focusControl(entry.controlId)">
                  {{ entry.messageKey | transloco }}
                </button>
              </li>
            }
          </ul>
        </div>
      </div>
    }
  `,
  styles: `
    .list {
      margin: 0;
      padding-left: var(--vd-space-4);
    }
    .link {
      border: 0;
      padding: 0;
      background: none;
      color: var(--vd-color-accent);
      text-decoration: underline;
      text-underline-offset: 2px;
      cursor: pointer;
      text-align: left;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ErrorSummaryComponent {
  private readonly document = inject(DOCUMENT);
  private readonly box = viewChild<ElementRef<HTMLElement>>('box');

  readonly errors = input.required<readonly FieldErrorEntry[]>();

  constructor() {
    afterRenderEffect(() => {
      const box = this.box();
      if (box && this.errors().length > 0) {
        box.nativeElement.focus();
      }
    });
  }

  protected focusControl(controlId: string): void {
    this.document.getElementById(controlId)?.focus();
  }
}
