import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { Subscription, switchMap } from 'rxjs';

import { HealthService, HealthStatus } from '../core/health/health.service';

export type HealthState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly status: HealthStatus }
  | { readonly kind: 'unreachable' };

@Component({
  selector: 'vd-shell',
  imports: [TranslocoPipe],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  private readonly healthService = inject(HealthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  private readonly title = inject(Title);
  private readonly transloco = inject(TranslocoService);
  private healthRequest: Subscription | undefined;

  protected readonly health = signal<HealthState>({ kind: 'loading' });

  constructor() {
    this.transloco.langChanges$
      .pipe(
        switchMap((lang) => {
          this.document.documentElement.lang = lang;
          return this.transloco.selectTranslate<string>('shell.title', {}, lang);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((pageTitle) => this.title.setTitle(pageTitle));

    this.checkHealth();
  }

  protected checkHealth(): void {
    this.healthRequest?.unsubscribe();
    this.health.set({ kind: 'loading' });
    this.healthRequest = this.healthService
      .getHealth()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.health.set({ kind: 'ready', status: response.status }),
        error: () => this.health.set({ kind: 'unreachable' }),
      });
  }
}
