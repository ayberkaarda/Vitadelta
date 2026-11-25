import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { EN, translocoTesting } from '../../testing/transloco-testing';
import { ConfirmDialogComponent } from './confirm-dialog.component';

@Component({
  imports: [ConfirmDialogComponent],
  template: `
    <button id="trigger" type="button" (click)="open.set(true)">open</button>
    <p id="outside">outside</p>
    <vd-confirm-dialog
      [open]="open()"
      heading="Delete the code?"
      confirmLabel="Delete"
      [tone]="tone()"
      [busy]="busy()"
      (confirmed)="confirmed = confirmed + 1"
      (cancelled)="open.set(false)"
    >
      @if (withField()) {
        <input id="secret" type="password" data-initial-focus />
      }
      <p>The code stops working at once.</p>
    </vd-confirm-dialog>
  `,
})
class ConfirmHostComponent {
  readonly open = signal(false);
  readonly busy = signal(false);
  readonly tone = signal<'danger' | 'default'>('danger');
  readonly withField = signal(false);
  confirmed = 0;
}

describe('ConfirmDialogComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  async function setup(withField = false) {
    const fixture = TestBed.createComponent(ConfirmHostComponent);
    fixture.componentInstance.withField.set(withField);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const trigger = host.querySelector<HTMLButtonElement>('#trigger');
    trigger?.focus();
    trigger?.click();
    await fixture.whenStable();
    const dialog = host.querySelector<HTMLElement>('[role="dialog"]');
    return { fixture, host, trigger, dialog };
  }

  it('renders a centred modal dialog named by its heading', async () => {
    const { host, dialog } = await setup();
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.classList).toContain('center');
    const titleId = dialog?.getAttribute('aria-labelledby') ?? '';
    expect(host.querySelector(`#${titleId}`)?.textContent?.trim()).toBe('Delete the code?');
    expect(dialog?.textContent).toContain('The code stops working at once.');
    expect(dialog?.querySelector('[data-cancel]')?.textContent?.trim()).toBe(EN.common.cancel);
    expect(dialog?.querySelector('[data-confirm]')?.classList).toContain('btn-danger');
    expect((host.querySelector('#outside') as HTMLElement).inert).toBe(true);
  });

  it('focuses Cancel by default and a marked field when there is one', async () => {
    await setup();
    expect(document.activeElement?.hasAttribute('data-cancel')).toBe(true);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [translocoTesting()] });
    await setup(true);
    expect(document.activeElement?.id).toBe('secret');
  });

  it('emits confirmed on submit and ignores it while busy', async () => {
    const { fixture, dialog } = await setup();
    const form = dialog?.querySelector('form');
    form?.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(fixture.componentInstance.confirmed).toBe(1);

    fixture.componentInstance.busy.set(true);
    await fixture.whenStable();
    expect(dialog?.querySelector('[data-confirm]')?.getAttribute('aria-busy')).toBe('true');
    form?.dispatchEvent(new Event('submit', { cancelable: true }));
    dialog?.querySelector<HTMLButtonElement>('[data-cancel]')?.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.confirmed).toBe(1);
    expect(fixture.componentInstance.open()).toBe(true);
  });

  it('cancels with the Cancel button and Escape, restoring focus', async () => {
    const { fixture, host, trigger, dialog } = await setup();
    dialog?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);

    trigger?.click();
    await fixture.whenStable();
    host.querySelector<HTMLButtonElement>('[data-cancel]')?.click();
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(fixture.componentInstance.confirmed).toBe(0);
  });

  it('uses the primary style for a non-destructive confirmation', async () => {
    const { fixture, dialog } = await setup();
    fixture.componentInstance.tone.set('default');
    await fixture.whenStable();
    expect(dialog?.querySelector('[data-confirm]')?.classList).toContain('btn-primary');
  });
});
