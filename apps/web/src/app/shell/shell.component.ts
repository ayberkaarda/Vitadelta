import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { filter } from 'rxjs';

import { safeReturnUrl } from '../core/auth/return-url';
import { SessionStore } from '../core/auth/session.store';
import { CurrencyStore } from '../core/state/currency.store';
import { RegionStore } from '../core/state/region.store';
import { UNREAD_POLL_INTERVAL_MS, UnreadCountStore } from '../core/state/unread-count.store';
import { DrawerComponent } from '../shared/dialog/drawer.component';
import { IconComponent } from '../shared/icon/icon.component';
import { QuotaChipComponent } from '../shared/quota-chip/quota-chip.component';
import { NotificationBellComponent } from './notification-bell.component';
import { RegionControlsComponent } from './region-controls.component';
import { UserMenuComponent } from './user-menu.component';

/**
 * Application frame (design hand-off §4.1): skip link, header with brand, primary navigation,
 * country / display currency / language controls, quota chip or notifications bell, and the
 * login links or the account menu; a menu sheet below 48rem; the routed page; the footer with
 * the privacy link and the not-medical-advice line.
 */
/**
 * Where a signed-in visitor continues from the current page: the checked `returnUrl` of the
 * login page, or `null` to stay on any other page.
 */
function signedInTarget(router: Router): string | null {
  const tree = router.parseUrl(router.url);
  const path = tree.root.children['primary']?.segments.map((segment) => segment.path).join('/');
  return path === 'auth/login' ? safeReturnUrl(tree.queryParams['returnUrl']) : null;
}

@Component({
  selector: 'vd-shell',
  imports: [
    DrawerComponent,
    IconComponent,
    NotificationBellComponent,
    QuotaChipComponent,
    RegionControlsComponent,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    TranslocoPipe,
    UserMenuComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  protected readonly session = inject(SessionStore);
  private readonly region = inject(RegionStore);
  private readonly currency = inject(CurrencyStore);
  private readonly unread = inject(UnreadCountStore);
  private readonly document = inject(DOCUMENT);
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  protected readonly sheetOpen = signal(false);

  constructor() {
    void this.session.bootstrap();
    void this.region.loadCountries();
    void this.currency.loadCurrencies();

    const destroyRef = inject(DestroyRef);
    let timer: ReturnType<typeof setInterval> | null = null;
    const stop = (): void => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    effect(() => {
      // A new session generation (another account, a sign-out) restarts the polling.
      this.session.generation();
      stop();
      if (this.session.isAuthenticated()) {
        void this.unread.refresh();
        timer = setInterval(() => {
          if (!this.document.hidden) {
            void this.unread.refresh();
          }
        }, UNREAD_POLL_INTERVAL_MS);
      } else {
        this.unread.set(null);
      }
    });
    destroyRef.onDestroy(stop);

    // When the session changes under a mounted page (also from another tab), the guards of the
    // current route run again: protected pages send the visitor to login or home. A login page
    // that sees a sign-in (here or in another tab) continues to its return URL instead.
    const router = inject(Router);
    let seenGeneration: number | null = null;
    effect(() => {
      const generation = this.session.generation();
      untracked(() => {
        const changed = seenGeneration !== null && seenGeneration !== generation;
        seenGeneration = generation;
        if (!changed || !router.navigated) {
          return;
        }
        const target = this.session.isAuthenticated() ? signedInTarget(router) : null;
        void router.navigateByUrl(
          target ?? router.url,
          target === null
            ? { onSameUrlNavigation: 'reload', replaceUrl: true }
            : { replaceUrl: true },
        );
      });
    });

    const navigation = router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => this.sheetOpen.set(false));
    destroyRef.onDestroy(() => navigation.unsubscribe());
  }

  /** Moves focus to the main region without changing the URL (base href would reload). */
  protected skipToMain(event: Event): void {
    event.preventDefault();
    this.main().nativeElement.focus();
  }
}
