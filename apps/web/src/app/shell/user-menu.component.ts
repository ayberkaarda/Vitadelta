import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { SessionStore } from '../core/auth/session.store';
import { UnreadCountStore } from '../core/state/unread-count.store';
import { IconComponent } from '../shared/icon/icon.component';

let nextId = 0;

/**
 * Account menu (design hand-off §4.1): pill button with avatar initial and plan tag; menu with
 * account, saved products, alerts, notifications, admin (role ADMIN only) and log out.
 * Keyboard: Down/Enter/Space open and focus the first item, Up/Down move, Home/End jump,
 * Esc closes and returns focus to the button, Tab closes.
 */
@Component({
  selector: 'vd-user-menu',
  imports: [IconComponent, RouterLink, TranslocoPipe],
  templateUrl: './user-menu.component.html',
  styleUrl: './user-menu.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:click)': 'onDocumentClick($event)',
  },
})
export class UserMenuComponent {
  protected readonly session = inject(SessionStore);
  protected readonly unread = inject(UnreadCountStore);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly button = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private readonly menu = viewChild<ElementRef<HTMLElement>>('menu');

  protected readonly menuId = `vd-user-menu-${nextId++}`;
  protected readonly open = signal(false);

  protected readonly initial = computed(
    () => this.session.user()?.email.trim().charAt(0).toUpperCase() ?? '',
  );
  protected readonly tag = computed(() => {
    if (this.session.isAdmin()) {
      return 'ADMIN';
    }
    return this.session.plan() ?? 'FREE';
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.open.set(false));
  }

  protected onButtonKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openMenu(0);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.openMenu(-1);
    }
  }

  protected toggle(): void {
    if (this.open()) {
      this.close(false);
    } else {
      this.openMenu(0);
    }
  }

  protected onMenuKeydown(event: KeyboardEvent): void {
    const items = this.items();
    const index = items.indexOf(this.document.activeElement as HTMLElement);
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        items[(index + 1) % items.length]?.focus();
        break;
      case 'ArrowUp':
        event.preventDefault();
        items[(index - 1 + items.length) % items.length]?.focus();
        break;
      case 'Home':
        event.preventDefault();
        items[0]?.focus();
        break;
      case 'End':
        event.preventDefault();
        items[items.length - 1]?.focus();
        break;
      case 'Escape':
        event.preventDefault();
        this.close(true);
        break;
      case 'Tab':
        this.close(false);
        break;
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close(false);
    }
  }

  protected select(): void {
    this.close(false);
  }

  protected async logout(): Promise<void> {
    this.close(false);
    await this.session.signOut();
    await this.router.navigateByUrl('/');
  }

  private openMenu(focusIndex: number): void {
    this.open.set(true);
    afterNextRender(
      () => {
        const items = this.items();
        const target = focusIndex < 0 ? items[items.length - 1] : items[focusIndex];
        target?.focus();
      },
      { injector: this.injector },
    );
  }

  private close(refocus: boolean): void {
    this.open.set(false);
    if (refocus) {
      this.button().nativeElement.focus();
    }
  }

  private items(): HTMLElement[] {
    const menu = this.menu()?.nativeElement;
    return menu ? Array.from(menu.querySelectorAll<HTMLElement>('[role="menuitem"]')) : [];
  }
}
