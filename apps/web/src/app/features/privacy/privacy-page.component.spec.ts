import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import privacyEn from '../../../../public/i18n/privacy/en.json';
import privacyTr from '../../../../public/i18n/privacy/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { PRIVACY_SECTIONS, PrivacyPageComponent } from './privacy-page.component';

describe('PrivacyPageComponent', () => {
  async function setup(getLegal: ReturnType<typeof vi.fn>, lang: 'en' | 'tr' = 'en') {
    TestBed.configureTestingModule({
      imports: [
        PrivacyPageComponent,
        translocoTesting({ scopes: { privacy: { en: privacyEn, tr: privacyTr } }, lang }),
      ],
      providers: [provideRouter([]), { provide: ApiClient, useValue: { getLegal } }],
    });
    const fixture = TestBed.createComponent(PrivacyPageComponent);
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  it('renders the controller contact from GET /legal as selectable text with a copy button', async () => {
    const getLegal = vi.fn(() => of({ privacyContact: 'privacy@example.com' }));
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const { fixture, host } = await setup(getLegal);
    expect(getLegal).toHaveBeenCalled();
    expect(host.querySelector('[data-contact]')?.textContent).toBe('privacy@example.com');
    expect(host.querySelector('a[href^="mailto:"]')).toBeNull();
    host.querySelector<HTMLButtonElement>('#who button')?.click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    expect(writeText).toHaveBeenCalledWith('privacy@example.com');
    expect(host.querySelector('#who [role="status"]')?.textContent).toContain(privacyEn.who.copied);
  });

  it('contains every required section with a table of contents', async () => {
    const { host } = await setup(vi.fn(() => of({ privacyContact: 'privacy@example.com' })));
    for (const section of PRIVACY_SECTIONS) {
      expect(host.querySelector(`section#${section} h2`)).not.toBeNull();
      expect(host.querySelector(`.toc a[href="/#${section}"]`)).not.toBeNull();
    }
    const text = host.textContent ?? '';
    expect(text).toContain('KVKK');
    expect(text).toContain('GDPR');
    expect(text).toContain('vd_anon');
    expect(text).toContain('vd_refresh');
    expect(text).toContain(privacyEn.retention.items.unverified);
    expect(text).toContain(privacyEn.retention.items.backups);
    expect(text).toContain(privacyEn.recipients.fx);
    expect(host.querySelectorAll('#purposes tbody tr')).toHaveLength(6);
    expect(host.querySelector('#rights a[href="/account"]')).not.toBeNull();
  });

  it('renders the Turkish notice', async () => {
    const { host } = await setup(
      vi.fn(() => of({ privacyContact: 'privacy@example.com' })),
      'tr',
    );
    expect(host.querySelector('h1')?.textContent).toContain(privacyTr.title);
    expect(host.textContent).toContain(privacyTr.cookies.no_banner);
  });

  it('offers a retry when the contact cannot be loaded', async () => {
    const getLegal = vi
      .fn()
      .mockReturnValueOnce(
        throwError(() => new ApiProblem({ kind: 'network', status: 0, code: null })),
      )
      .mockReturnValue(of({ privacyContact: 'privacy@example.com' }));
    const { fixture, host } = await setup(getLegal);
    expect(host.textContent).toContain(EN.problem.network.title);
    host.querySelector<HTMLButtonElement>('#who vd-problem-notice button')?.click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    expect(host.querySelector('[data-contact]')?.textContent).toBe('privacy@example.com');
  });
});
