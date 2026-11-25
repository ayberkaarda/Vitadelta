import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DrawerComponent } from './drawer.component';

@Component({
  imports: [DrawerComponent],
  template: `
    <button id="trigger" type="button" (click)="open.set(true)">open</button>
    <p id="outside">outside</p>
    <vd-drawer [open]="open()" labelledBy="dlg-title" (closed)="close()">
      <h2 id="dlg-title">Title</h2>
      <button id="first" type="button">first</button>
      <button id="initial" type="button" data-initial-focus>initial</button>
      <input id="consumer" (keydown.escape)="$event.preventDefault()" />
      <button id="last" type="button">last</button>
    </vd-drawer>
  `,
})
class DrawerHostComponent {
  readonly open = signal(false);
  closedCount = 0;

  close(): void {
    this.closedCount += 1;
    this.open.set(false);
  }
}

@Component({
  imports: [DrawerComponent],
  template: `
    <button id="outer-trigger" type="button" (click)="outer.set(true)">outer</button>
    <vd-drawer [open]="outer()" labelledBy="outer-title" (closed)="outer.set(false)">
      <h2 id="outer-title">Outer</h2>
      <button id="inner-trigger" type="button" (click)="inner.set(true)">inner</button>
      <vd-drawer [open]="inner()" labelledBy="inner-title" (closed)="inner.set(false)">
        <h2 id="inner-title">Inner</h2>
        <button id="inner-close" type="button">close</button>
      </vd-drawer>
    </vd-drawer>
  `,
})
class NestedDrawerHostComponent {
  readonly outer = signal(false);
  readonly inner = signal(false);
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

  const key = (target: EventTarget | null, init: KeyboardEventInit) =>
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

  it('closes on Escape when focus has left the panel, exactly once', async () => {
    const { fixture, host, trigger } = await setup();
    (document.activeElement as HTMLElement | null)?.blur();
    expect(document.activeElement).toBe(document.body);
    key(document.body, { key: 'Escape' });
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(fixture.componentInstance.closedCount).toBe(1);
    expect(document.activeElement).toBe(trigger);
    expect((host.querySelector('#outside') as HTMLElement).inert).toBe(false);
  });

  it('handles Escape inside the panel once and stops listening after closing', async () => {
    const { fixture, host } = await setup();
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    host.querySelector('#initial')?.dispatchEvent(escape);
    await fixture.whenStable();
    expect(fixture.componentInstance.closedCount).toBe(1);
    expect(escape.defaultPrevented).toBe(true);
    key(document.body, { key: 'Escape' });
    key(document, { key: 'Escape' });
    await fixture.whenStable();
    expect(fixture.componentInstance.closedCount).toBe(1);
  });

  it('leaves Escape to a control inside that consumes it', async () => {
    const { fixture, host } = await setup();
    host.querySelector<HTMLElement>('#consumer')?.focus();
    key(host.querySelector('#consumer'), { key: 'Escape' });
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
    expect(fixture.componentInstance.closedCount).toBe(0);
  });

  it('ignores other keys on the document', async () => {
    const { fixture, host } = await setup();
    key(document.body, { key: 'Enter' });
    key(document.body, { key: 'Esc' });
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
  });

  it('closes only the innermost of nested drawers on Escape', async () => {
    const fixture = TestBed.createComponent(NestedDrawerHostComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLButtonElement>('#outer-trigger')?.click();
    await fixture.whenStable();
    host.querySelector<HTMLButtonElement>('#inner-trigger')?.click();
    await fixture.whenStable();
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(2);
    (document.activeElement as HTMLElement | null)?.blur();
    key(document.body, { key: 'Escape' });
    await fixture.whenStable();
    expect(fixture.componentInstance.inner()).toBe(false);
    expect(fixture.componentInstance.outer()).toBe(true);
    key(document.body, { key: 'Escape' });
    await fixture.whenStable();
    expect(fixture.componentInstance.outer()).toBe(false);
    expect(host.querySelector('[role="dialog"]')).toBeNull();
  });

  it('stops listening when destroyed while open', async () => {
    const { fixture } = await setup();
    fixture.destroy();
    key(document.body, { key: 'Escape' });
    expect(fixture.componentInstance.closedCount).toBe(0);
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
