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
import { Params, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiProblem } from '../../../core/api/api-problem';
import { Tone } from '../../../shared/flags/flag-style';
import { IconComponent } from '../../../shared/icon/icon.component';
import { IconName } from '../../../shared/icon/icon-paths';
import { EmptyStateComponent } from '../../../shared/state/empty-state.component';

/** Problems the user fixes by changing the query rather than retrying. */
const QUERY_CODES = new Set(['country_not_found', 'fx_unsupported_currency', 'validation_failed']);

/**
 * Error state that replaces the ranking (design hand-off §4.4): `503 scoring_unavailable` with
 * retry and request id, `429 rate_limited` with a retry button that counts down `Retry-After`,
 * query problems with "Edit query", and generic / network errors with retry.
 */
@Component({
  selector: 'vd-results-error',
  imports: [EmptyStateComponent, IconComponent, RouterLink, TranslocoPipe],
  template: `
    @let p = problem();
    <div class="card" [attr.data-code]="p.code ?? p.kind">
      <vd-empty-state
        [icon]="icon()"
        [tone]="tone()"
        [heading]="p.messageKey + '.title' | transloco"
      >
        <p>{{ p.messageKey + '.body' | transloco }}</p>
        <div vdStateActions>
          @if (editable()) {
            <a class="btn btn-primary" routerLink="/" [queryParams]="editParams()">{{
              'results.error.edit' | transloco
            }}</a>
          } @else {
            <button
              type="button"
              class="btn btn-primary"
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
          }
        </div>
        @if (p.requestId && p.status >= 500) {
          <p class="text-xs">{{ 'problem.request_id' | transloco: { requestId: p.requestId } }}</p>
        }
      </vd-empty-state>
    </div>
  `,
  styles: `
    .card {
      padding: 0;
    }
    [vdStateActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultsErrorComponent {
  readonly problem = input.required<ApiProblem>();
  readonly editParams = input<Params>({});
  readonly retry = output();

  protected readonly secondsLeft = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  protected readonly editable = computed(() => {
    const code = this.problem().code;
    return code !== null && QUERY_CODES.has(code);
  });

  protected readonly tone = computed<Tone>(() => {
    const p = this.problem();
    if (p.code === 'rate_limited' || this.editable()) {
      return 'warn';
    }
    return p.kind === 'network' ? 'neutral' : 'bad';
  });

  protected readonly icon = computed<IconName>(() => {
    const p = this.problem();
    if (p.code === 'rate_limited') {
      return 'clock';
    }
    return p.kind === 'network' ? 'refresh' : 'warn';
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
    effect(() => {
      const problem = this.problem();
      this.stop();
      const seconds = problem.code === 'rate_limited' ? (problem.retryAfterSeconds ?? 0) : 0;
      this.secondsLeft.set(seconds);
      if (seconds > 0) {
        this.timer = setInterval(() => {
          const next = Math.max(0, this.secondsLeft() - 1);
          this.secondsLeft.set(next);
          if (next === 0) {
            this.stop();
          }
        }, 1000);
      }
    });
  }

  private stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
