import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiProblem } from '../../core/api/api-problem';
import { Tone } from '../flags/flag-style';
import { IconComponent } from '../icon/icon.component';
import { IconName } from '../icon/icon-paths';
import { LimitNoticeComponent } from './limit-notice.component';
import { NoticeComponent } from './notice.component';

const WARN_CODES = new Set(['email_not_verified', 'conflict_retry', 'token_expired']);

/**
 * Renders any {@link ApiProblem} as an inline notice:
 * - limit errors with an `upgradeHint` → {@link LimitNoticeComponent};
 * - `rate_limited` → warning with a retry button that counts down `Retry-After`;
 * - everything else → `problem.<code>.title/.body`, the request id when known and, with
 *   `retryable`, a "Try again" button that emits `retry`.
 *
 * Field errors of `validation_failed` belong next to their fields (see `FieldComponent`).
 */
@Component({
  selector: 'vd-problem-notice',
  imports: [IconComponent, LimitNoticeComponent, NoticeComponent, TranslocoPipe],
  template: `
    @let p = problem();
    @if (p.upgradeHint) {
      <vd-limit-notice [problem]="p" />
    } @else {
      <vd-notice
        [tone]="tone()"
        [icon]="icon()"
        live="alert"
        [heading]="p.messageKey + '.title' | transloco"
        [attr.data-code]="p.code ?? p.kind"
      >
        <p>{{ p.messageKey + '.body' | transloco }}</p>
        @if (p.requestId && p.status >= 500) {
          <p class="text-xs muted">
            {{ 'problem.request_id' | transloco: { requestId: p.requestId } }}
          </p>
        }
        @if (retryable() || p.code === 'rate_limited') {
          <div vdNoticeActions>
            <button
              type="button"
              class="btn btn-sm"
              [disabled]="secondsLeft() > 0"
              (click)="retry.emit()"
            >
              <vd-icon name="refresh" />
              @if (secondsLeft() > 0) {
                {{ 'problem.retry_in' | transloco: { seconds: secondsLeft() } }}
              } @else {
                {{ 'problem.retry' | transloco }}
              }
            </button>
          </div>
        }
      </vd-notice>
    }
  `,
  styles: `
    [vdNoticeActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProblemNoticeComponent {
  readonly problem = input.required<ApiProblem>();
  /** Shows a "Try again" button for problems the page can retry. */
  readonly retryable = input(false);
  readonly retry = output();

  protected readonly secondsLeft = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  protected readonly tone = computed<Tone>(() => {
    const p = this.problem();
    if (p.code === 'rate_limited' || (p.code !== null && WARN_CODES.has(p.code))) {
      return 'warn';
    }
    return 'bad';
  });

  protected readonly icon = computed<IconName>(() => {
    const code = this.problem().code;
    if (code === 'rate_limited' || code === 'token_expired') {
      return 'clock';
    }
    if (code === 'account_disabled') {
      return 'lock';
    }
    return 'warn';
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopTimer());
    effect(() => {
      const problem = this.problem();
      this.stopTimer();
      const seconds = problem.code === 'rate_limited' ? (problem.retryAfterSeconds ?? 0) : 0;
      this.secondsLeft.set(seconds);
      if (seconds > 0) {
        this.timer = setInterval(() => {
          const next = Math.max(0, this.secondsLeft() - 1);
          this.secondsLeft.set(next);
          if (next === 0) {
            this.stopTimer();
          }
        }, 1000);
      }
    });
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
