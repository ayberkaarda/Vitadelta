import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DrawerComponent } from './drawer.component';

@Component({
  imports: [DrawerComponent],
  template: `
    <button id="trigger" type="button" (click)="open.set(true)">open</button>
    <p id="outside">outside</p>
    <vd-drawer [open]="open()" labelledBy="dlg-title" (closed)="open.set(false)">
      <h2 id="dlg-title">Title</h2>
      <button id="first" type="button">first</button>
      <button id="initial" type="button" data-initial-focus>initial</button>
      <button id="last" type="button">last</button>
    </vd-drawer>
  `,
})
class DrawerHostComponent {
  readonly open = signal(false);
}

describe('DrawerComponent', () => {
  async function setup() {
    const fixture = TestBed.createComponent(DrawerHostComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const trigger = host.querySelector<HTMLButtonElement>('#trigger');
    trigger?.focus();
    trigger?.click();
    await fixture.whenStable();
    return { fixture, host, trigger };
  }

  const key = (target: Element | null, init: KeyboardEventInit) =>
    target?.dispatchEvent(
      new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }),
    );

  it('opens a labelled modal dialog and focuses the initial element', async () => {
    const { host } = await setup();
    const dialog = host.querySelector('[role="dialog"]');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.getAttribute('aria-labelledby')).toBe('dlg-title');
    expect(document.activeElement?.id).toBe('initial');
    expect((host.querySelector('#outside') as HTMLElement).inert).toBe(true);
  });

  it('traps Tab inside the panel', async () => {
    const { host } = await setup();
    const dialog = host.querySelector('[role="dialog"]');
    host.querySelector<HTMLElement>('#last')?.focus();
    key(dialog, { key: 'Tab' });
    expect(document.activeElement?.id).toBe('first');
    key(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement?.id).toBe('last');
    host.querySelector<HTMLElement>('#initial')?.focus();
    key(dialog, { key: 'Tab' });
    expect(document.activeElement?.id).toBe('initial');
  });

  it('closes on Escape, restores focus and the background', async () => {
    const { fixture, host, trigger } = await setup();
    key(host.querySelector('[role="dialog"]'), { key: 'Escape' });
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect((host.querySelector('#outside') as HTMLElement).inert).toBe(false);
  });

  it('closes on a scrim click', async () => {
    const { fixture, host } = await setup();
    host.querySelector<HTMLElement>('.scrim')?.click();
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
  });

  it('restores the background when destroyed while open', async () => {
    const { fixture, host } = await setup();
    const outside = host.querySelector('#outside') as HTMLElement;
    fixture.destroy();
    expect(outside.inert).toBe(false);
  });
});
