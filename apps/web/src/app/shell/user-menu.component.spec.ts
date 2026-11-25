import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { fixtureUser } from '../testing/api-fixtures';
import { EN, translocoTesting } from '../testing/transloco-testing';
import { SessionStore } from '../core/auth/session.store';
import { UnreadCountStore } from '../core/state/unread-count.store';
import { UserMenuComponent } from './user-menu.component';

describe('UserMenuComponent', () => {
  let fixture: ComponentFixture<UserMenuComponent>;
  let admin: ReturnType<typeof signal<boolean>>;
  let signOut: ReturnType<typeof vi.fn>;

  const host = () => fixture.nativeElement as HTMLElement;
  const button = () => host().querySelector<HTMLButtonElement>('button.user');
  const items = () => Array.from(host().querySelectorAll<HTMLElement>('[role="menuitem"]'));
  const key = (target: Element | null, value: string) =>
    target?.dispatchEvent(
      new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true }),
    );
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };

  beforeEach(async () => {
    admin = signal(false);
    signOut = vi.fn(async () => undefined);
    TestBed.configureTestingModule({
      imports: [UserMenuComponent, translocoTesting()],
      providers: [
        provideRouter([]),
        {
          provide: SessionStore,
          useValue: {
            user: signal(fixtureUser({ email: 'pat@example.com' })),
            isAdmin: admin,
            plan: signal('PRO'),
            signOut,
          },
        },
        { provide: UnreadCountStore, useValue: { count: signal(4) } },
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(UserMenuComponent);
    document.body.appendChild(fixture.nativeElement);
    await fixture.whenStable();
  });

  afterEach(() => (fixture.nativeElement as HTMLElement).remove());

  it('shows the avatar initial and plan tag on a menu button', () => {
    expect(button()?.getAttribute('aria-haspopup')).toBe('menu');
    expect(button()?.getAttribute('aria-expanded')).toBe('false');
    expect(host().querySelector('.avatar')?.textContent?.trim()).toBe('P');
    expect(host().querySelector('.plan-tag')?.textContent?.trim()).toBe('PRO');
  });

  it('opens with ArrowDown, moves with arrows, Home and End, closes with Escape', async () => {
    button()?.focus();
    key(button(), 'ArrowDown');
    await settle();
    expect(button()?.getAttribute('aria-expanded')).toBe('true');
    expect(host().textContent).toContain('pat@example.com');
    expect(host().textContent).toContain('4 new');
    expect(items().map((item) => item.textContent?.trim())).not.toContain(EN.shell.menu.admin);
    expect(document.activeElement).toBe(items()[0]);

    const menu = host().querySelector('[role="menu"]');
    key(menu, 'ArrowDown');
    expect(document.activeElement).toBe(items()[1]);
    key(menu, 'ArrowUp');
    key(menu, 'ArrowUp');
    expect(document.activeElement).toBe(items()[items().length - 1]);
    key(menu, 'Home');
    expect(document.activeElement).toBe(items()[0]);
    key(menu, 'End');
    expect(document.activeElement?.textContent?.trim()).toBe(EN.shell.menu.logout);

    key(menu, 'Escape');
    await settle();
    expect(host().querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(button());
  });

  it('opens on ArrowUp with the last item focused and closes on Tab', async () => {
    button()?.focus();
    key(button(), 'ArrowUp');
    await settle();
    expect(document.activeElement?.textContent?.trim()).toBe(EN.shell.menu.logout);
    key(host().querySelector('[role="menu"]'), 'Tab');
    await settle();
    expect(host().querySelector('[role="menu"]')).toBeNull();
  });

  it('lists the admin entry for admins and toggles on click', async () => {
    admin.set(true);
    await fixture.whenStable();
    expect(host().querySelector('.plan-tag')?.textContent?.trim()).toBe('ADMIN');
    button()?.click();
    await settle();
    expect(items().some((item) => item.textContent?.includes(EN.shell.menu.admin))).toBe(true);
    button()?.click();
    await settle();
    expect(host().querySelector('[role="menu"]')).toBeNull();
  });

  it('closes when clicking outside', async () => {
    button()?.click();
    await settle();
    document.body.click();
    await settle();
    expect(host().querySelector('[role="menu"]')).toBeNull();
  });

  it('logs out and returns home', async () => {
    button()?.click();
    await settle();
    items()[items().length - 1].click();
    await settle();
    expect(signOut).toHaveBeenCalled();
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/');
  });
});
