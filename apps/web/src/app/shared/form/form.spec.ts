import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { EN, TR, translocoTesting } from '../../testing/transloco-testing';
import { ApiProblem } from '../../core/api/api-problem';
import { ErrorSummaryComponent } from './error-summary.component';
import {
  FIELD_ERROR_CODES,
  FieldErrorEntry,
  fieldErrorKey,
  formErrors,
  mapFieldErrors,
  problemFormErrors,
  serverMessageKey,
} from './field-errors';
import { ControlDirective, FieldComponent } from './field.component';

@Component({
  imports: [ControlDirective, ErrorSummaryComponent, FieldComponent],
  template: `
    <vd-error-summary [errors]="summary()" />
    <vd-field controlId="email" label="E-mail" [hint]="hint()" [error]="error()">
      <input vdControl class="inp" type="email" />
    </vd-field>
  `,
})
class FormHostComponent {
  readonly hint = signal<string | null>('We send a verification link.');
  readonly error = signal<string | null>(null);
  readonly summary = signal<readonly FieldErrorEntry[]>([]);
}

describe('FieldComponent and ControlDirective', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  it('wires label, hint, error and the live region to the control', async () => {
    const fixture = TestBed.createComponent(FormHostComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const input = host.querySelector('input');
    expect(input?.id).toBe('email');
    expect(host.querySelector('label')?.getAttribute('for')).toBe('email');
    expect(input?.getAttribute('aria-describedby')).toBe('email-hint email-error');
    expect(input?.hasAttribute('aria-invalid')).toBe(false);
    const live = host.querySelector('#email-error');
    expect(live?.getAttribute('aria-live')).toBe('polite');
    expect(live?.textContent?.trim()).toBe('');

    fixture.componentInstance.error.set('Use at least 10 characters.');
    fixture.componentInstance.hint.set(null);
    await fixture.whenStable();
    expect(input?.getAttribute('aria-invalid')).toBe('true');
    expect(input?.getAttribute('aria-describedby')).toBe('email-error');
    expect(host.querySelector('#email-error')?.textContent?.trim()).toBe(
      'Use at least 10 characters.',
    );
    expect(host.querySelector('#email-error svg')).not.toBeNull();
  });

  it('focuses the error summary when it appears and links to the controls', async () => {
    const fixture = TestBed.createComponent(FormHostComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[role="alert"]')).toBeNull();

    fixture.componentInstance.summary.set([
      { controlId: 'email', messageKey: 'validation.required' },
    ]);
    await fixture.whenStable();
    const summary = host.querySelector<HTMLElement>('[role="alert"]');
    expect(summary).not.toBeNull();
    expect(document.activeElement).toBe(summary);
    expect(summary?.textContent).toContain('Please correct 1 field');
    expect(summary?.textContent).toContain('This field is required.');

    summary?.querySelector<HTMLButtonElement>('button')?.click();
    expect(document.activeElement?.id).toBe('email');
  });
});

describe('field error mapping', () => {
  it('maps known codes to their own message and others to the generic one', () => {
    expect(fieldErrorKey('weights_all_zero')).toBe('validation.weights_all_zero');
    expect(fieldErrorKey('too_short')).toBe('validation.too_short');
    expect(fieldErrorKey('breached_password')).toBe('validation.breached_password');
    expect(fieldErrorKey('invalid_format')).toBe('validation.invalid_format');
    expect(fieldErrorKey('something_new')).toBe('validation.generic');
  });

  it.each([
    ['en', EN],
    ['tr', TR],
  ] as const)('has a root %s message for every known field error code', (_lang, root) => {
    const messages = root.validation as Readonly<Record<string, string>>;
    for (const code of FIELD_ERROR_CODES) {
      expect(messages[code], code).toBeTruthy();
    }
  });

  it('maps server JSON paths to control ids once per control', () => {
    const problem = new ApiProblem({
      kind: 'problem',
      status: 422,
      code: 'validation_failed',
      errors: [
        { field: 'requiredNutrients[0].unit', code: 'unit_requires_form', message: '' },
        { field: 'requiredNutrients[0].unit', code: 'required', message: '' },
        { field: 'weights', code: 'weights_all_zero', message: '' },
        { field: 'unknownField', code: 'out_of_range', message: '' },
      ],
    });
    const ids: Record<string, string> = { 'requiredNutrients[0].unit': 'req-0-unit' };
    expect(mapFieldErrors(problem, (field) => ids[field] ?? null, 'form')).toEqual([
      { controlId: 'req-0-unit', messageKey: 'validation.unit_requires_form' },
      { controlId: 'form', messageKey: 'validation.weights_all_zero' },
    ]);
  });

  it('builds form errors once per control, from pairs or from a problem', () => {
    expect(
      formErrors([
        ['a', 'validation.required'],
        ['a', 'validation.out_of_range'],
        ['b', 'validation.too_short'],
      ]),
    ).toEqual({
      fields: { a: 'validation.required', b: 'validation.too_short' },
      summary: [
        { controlId: 'a', messageKey: 'validation.required' },
        { controlId: 'b', messageKey: 'validation.too_short' },
      ],
    });
    const problem = new ApiProblem({
      kind: 'problem',
      status: 422,
      code: 'validation_failed',
      errors: [
        { field: 'password', code: 'breached_password', message: '' },
        { field: 'other', code: 'invalid_format', message: '' },
      ],
    });
    expect(problemFormErrors(problem, { password: 'pw' }, 'email').fields).toEqual({
      pw: 'validation.breached_password',
      email: 'validation.invalid_format',
    });
  });

  it('accepts only known server message keys', () => {
    expect(serverMessageKey('auth.verify.resent', 'auth.register.check_email')).toBe(
      'auth.verify.resent',
    );
    expect(serverMessageKey('shell.brand', 'auth.register.check_email')).toBe(
      'auth.register.check_email',
    );
    expect(serverMessageKey(null, 'auth.forgot.check_email')).toBe('auth.forgot.check_email');
  });
});
