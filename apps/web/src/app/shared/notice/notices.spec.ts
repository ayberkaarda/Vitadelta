import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EN, translocoTesting } from '../../testing/transloco-testing';
import { ApiProblem } from '../../core/api/api-problem';
import { LimitNoticeComponent } from './limit-notice.component';
import { NoticeComponent } from './notice.component';
import { ProblemNoticeComponent } from './problem-notice.component';

@Component({
  imports: [NoticeComponent],
  template: `
    <vd-notice tone="ok" live="status" heading="Saved">
      <p class="body-text">Body</p>
      <button vdNoticeActions type="button">Act</button>
    </vd-notice>
  `,
})
class NoticeHostComponent {}

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('NoticeComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  it('renders tone, live role, heading, body and actions', async () => {
    const fixture = TestBed.createComponent(NoticeHostComponent);
    await fixture.whenStable();
    const notice = (fixture.nativeElement as HTMLElement).querySelector('.notice');
    expect(notice?.classList.contains('t-ok')).toBe(true);
    expect(notice?.getAttribute('role')).toBe('status');
    expect(text(notice?.querySelector('.nt'))).toBe('Saved');
    expect(notice?.querySelector('.body-text')).not.toBeNull();
    expect(notice?.querySelector('.acts button')).not.toBeNull();
    expect(notice?.querySelector('svg')).not.toBeNull();
  });
});

describe('LimitNoticeComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideRouter([])],
    }),
  );

  async function render(problem: ApiProblem) {
    const fixture = TestBed.createComponent(LimitNoticeComponent);
    fixture.componentRef.setInput('problem', problem);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the upgradeHint message with the server counters and CTA path', async () => {
    const host = await render(
      new ApiProblem({
        kind: 'problem',
        status: 403,
        code: 'entitlement_exceeded',
        used: 5,
        limit: 5,
        plan: 'FREE',
        upgradeHint: { messageKey: 'limits.saved_products_reached', ctaPath: '/upgrade' },
      }),
    );
    expect(host.querySelector('.notice')?.classList.contains('t-warn')).toBe(true);
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(text(host)).toContain(
      'You have saved 5 of 5 products. Remove one from your list or upgrade to PRO.',
    );
    const link = host.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/upgrade');
    expect(text(link)).toBe(EN.limits.see_pro);
  });

  it('links the quota CTA to registration and ignores unsafe CTA paths', async () => {
    const quota = await render(
      new ApiProblem({
        kind: 'problem',
        status: 403,
        code: 'quota_exceeded',
        upgradeHint: { messageKey: 'quota.exhausted', ctaPath: '/auth/register' },
      }),
    );
    expect(quota.querySelector('a')?.getAttribute('href')).toBe('/auth/register');
    expect(text(quota.querySelector('a'))).toBe(EN.results.locked_register);

    const unsafe = await render(
      new ApiProblem({
        kind: 'problem',
        status: 403,
        code: 'entitlement_exceeded',
        upgradeHint: { messageKey: 'limits.saved_products_reached', ctaPath: '//evil.example' },
      }),
    );
    expect(unsafe.querySelector('a')?.getAttribute('href')).toBe('/');
  });
});

describe('ProblemNoticeComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideRouter([])],
    }),
  );
  afterEach(() => vi.useRealTimers());

  async function render(problem: ApiProblem, retryable = false) {
    const fixture = TestBed.createComponent(ProblemNoticeComponent);
    fixture.componentRef.setInput('problem', problem);
    fixture.componentRef.setInput('retryable', retryable);
    await fixture.whenStable();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  it('renders the problem title and body with the request id for server errors', async () => {
    const { fixture, host } = await render(
      new ApiProblem({
        kind: 'problem',
        status: 503,
        code: 'scoring_unavailable',
        requestId: 'req-42',
      }),
      true,
    );
    expect(text(host.querySelector('.nt'))).toBe(EN.problem.scoring_unavailable.title);
    expect(text(host)).toContain(EN.problem.scoring_unavailable.body);
    expect(text(host)).toContain('Request ID req-42');
    expect(host.querySelector('.notice')?.classList.contains('t-bad')).toBe(true);

    let retried = 0;
    fixture.componentInstance.retry.subscribe(() => (retried += 1));
    host.querySelector<HTMLButtonElement>('button')?.click();
    expect(retried).toBe(1);
  });

  it('uses the generic and network texts for problems without a code', async () => {
    const generic = await render(new ApiProblem({ kind: 'http', status: 500, code: null }));
    expect(text(generic.host.querySelector('.nt'))).toBe(EN.problem.generic.title);
    expect(generic.host.querySelector('button')).toBeNull();
    const network = await render(new ApiProblem({ kind: 'network', status: 0, code: null }));
    expect(text(network.host.querySelector('.nt'))).toBe(EN.problem.network.title);
  });

  it('counts down Retry-After before enabling the retry button', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const { fixture, host } = await render(
      new ApiProblem({ kind: 'problem', status: 429, code: 'rate_limited', retryAfterSeconds: 2 }),
    );
    const button = () => host.querySelector<HTMLButtonElement>('button');
    expect(host.querySelector('.notice')?.classList.contains('t-warn')).toBe(true);
    expect(button()?.disabled).toBe(true);
    expect(text(button())).toBe('Try again in 2 s');
    vi.advanceTimersByTime(1000);
    await fixture.whenStable();
    expect(text(button())).toBe('Try again in 1 s');
    vi.advanceTimersByTime(1000);
    await fixture.whenStable();
    expect(button()?.disabled).toBe(false);
    expect(text(button())).toBe(EN.problem.retry);
  });

  it('delegates limit problems to the limit notice', async () => {
    const { host } = await render(
      new ApiProblem({
        kind: 'problem',
        status: 403,
        code: 'entitlement_exceeded',
        used: 3,
        limit: 3,
        upgradeHint: { messageKey: 'limits.alert_subscriptions_reached', ctaPath: '/upgrade' },
      }),
    );
    expect(host.querySelector('vd-limit-notice')).not.toBeNull();
    expect(text(host)).toContain('3 of 3 alerts are already active.');
  });
});
