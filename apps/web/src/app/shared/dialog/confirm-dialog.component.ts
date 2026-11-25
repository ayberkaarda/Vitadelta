import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { IconComponent } from '../icon/icon.component';
import { IconName } from '../icon/icon-paths';
import { DrawerComponent } from './drawer.component';

export type ConfirmTone = 'danger' | 'default';

let nextId = 0;

/**
 * Centred confirmation dialog for irreversible actions (design hand-off §3, §6), built on the
 * `vd-drawer` primitive with `placement="center"`: `role="dialog"`, `aria-modal`, labelled by its
 * heading, focus trap, Esc and scrim close, inert background and focus restore. The body is
 * projected inside the dialog's form, so a field in it (a password) submits with Enter; mark it
 * `data-initial-focus` to focus it on open, otherwise the Cancel button is focused. No browser
 * dialogs are used anywhere.
 *
 * ```html
 * <vd-confirm-dialog [open]="open()" [heading]="'x.title' | transloco"
 *   [confirmLabel]="'x.confirm' | transloco" [busy]="busy()"
 *   (confirmed)="delete()" (cancelled)="open.set(false)">
 *   <p>…</p>
 * </vd-confirm-dialog>
 * ```
 */
@Component({
  selector: 'vd-confirm-dialog',
  imports: [DrawerComponent, IconComponent, TranslocoPipe],
  template: `
    <vd-drawer [open]="open()" [labelledBy]="titleId" placement="center" (closed)="cancel()">
      <form class="confirm" novalidate (submit)="confirm($event)">
        <div class="head">
          <span class="well" [class.danger]="tone() === 'danger'" aria-hidden="true">
            <vd-icon [name]="icon()" />
          </span>
          <h2 class="h3" [id]="titleId">{{ heading() }}</h2>
        </div>
        <div class="body stack">
          <ng-content />
        </div>
        <div class="actions">
          <button type="button" class="btn" data-cancel data-initial-focus (click)="cancel()">
            {{ cancelLabel() ?? ('common.cancel' | transloco) }}
          </button>
          <button
            type="submit"
            class="btn"
            data-confirm
            [class.btn-danger]="tone() === 'danger'"
            [class.btn-primary]="tone() === 'default'"
            [attr.aria-busy]="busy()"
          >
            @if (busy()) {
              <vd-icon name="refresh" [spin]="true" />
            } @else if (confirmIcon(); as name) {
              <vd-icon [name]="name" />
            }
            {{ confirmLabel() }}
          </button>
        </div>
      </form>
    </vd-drawer>
  `,
  styles: `
    :host {
      display: contents;
    }
    .confirm {
      display: flex;
      flex-direction: column;
      gap: var(--vd-space-4);
      padding: var(--vd-space-5);
    }
    .head {
      display: flex;
      align-items: center;
      gap: var(--vd-space-3);
    }
    .well {
      width: 2.5rem;
      height: 2.5rem;
      flex: none;
      display: grid;
      place-items: center;
      border-radius: 50%;
      background: var(--vd-color-accent-bg);
      color: var(--vd-color-accent);
      &.danger {
        background: var(--vd-color-danger-bg);
        color: var(--vd-color-danger);
      }
    }
    .body {
      font-size: var(--vd-font-size-sm);
    }
    .actions {
      display: flex;
      flex-wrap: wrap-reverse;
      justify-content: flex-end;
      gap: var(--vd-space-3);
      padding-top: var(--vd-space-2);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialogComponent {
  readonly open = input.required<boolean>();
  /** Translated heading; names the dialog. */
  readonly heading = input.required<string>();
  /** Translated label of the confirming button. */
  readonly confirmLabel = input.required<string>();
  /** Translated label of the cancel button; defaults to `common.cancel`. */
  readonly cancelLabel = input<string | null>(null);
  readonly tone = input<ConfirmTone>('danger');
  readonly icon = input<IconName>('warn');
  readonly confirmIcon = input<IconName | null>(null);
  /** While busy the dialog cannot be dismissed and the confirm button shows a spinner. */
  readonly busy = input(false);

  readonly confirmed = output();
  readonly cancelled = output();

  protected readonly titleId = `confirm-title-${nextId++}`;

  protected confirm(event: Event): void {
    event.preventDefault();
    if (!this.busy()) {
      this.confirmed.emit();
    }
  }

  protected cancel(): void {
    if (!this.busy()) {
      this.cancelled.emit();
    }
  }
}
