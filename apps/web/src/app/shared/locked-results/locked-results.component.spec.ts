import { reflectComponentType } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EN, translocoTesting } from '../../testing/transloco-testing';
import { Visibility } from '../../core/api/api-types';
import { LockedResultsComponent } from './locked-results.component';

describe('LockedResultsComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideRouter([])],
    }),
  );

  async function render(visibility: Visibility, inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(LockedResultsComponent);
    fixture.componentRef.setInput('visibility', visibility);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const rows = (host: HTMLElement) => Array.from(host.querySelectorAll('li.locked-row:not(.tail)'));

  it('renders exclusively from meta.visibility (no product input exists)', () => {
    const inputs = reflectComponentType(LockedResultsComponent)?.inputs.map(
      (input) => input.propName,
    );
    expect(inputs?.sort()).toEqual(['maxRows', 'returnUrl', 'visibility']);
  });

  it('renders one locked row per locked position after the visible ones', async () => {
    const host = await render({ shown: 3, total: 8, locked: 5, reason: 'SIGN_UP_REQUIRED' });
    const locked = rows(host);
    expect(locked).toHaveLength(5);
    expect(locked.map((row) => row.getAttribute('aria-label'))).toEqual([
      'Result 4, locked',
      'Result 5, locked',
      'Result 6, locked',
      'Result 7, locked',
      'Result 8, locked',
    ]);
    for (const row of locked) {
      expect(row.querySelector('svg')).not.toBeNull();
      expect(row.textContent?.replace(/\s+/g, ' ').trim()).toMatch(/^\d+ Locked result$/);
    }
  });

  it('shows the sign-up call to action with the locked count', async () => {
    const host = await render(
      { shown: 3, total: 8, locked: 5, reason: 'SIGN_UP_REQUIRED' },
      {
        returnUrl: '/results?country=TR',
      },
    );
    expect(host.querySelector('h2')?.textContent?.trim()).toBe('5 more results are locked');
    expect(host.textContent).toContain(EN.results.locked_text);
    const links = Array.from(host.querySelectorAll<HTMLAnchorElement>('.cta a'));
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/auth/register',
      '/auth/login?returnUrl=%2Fresults%3Fcountry%3DTR',
    ]);
    expect(links[0].textContent?.trim()).toBe(EN.results.locked_register);
  });

  it('uses the singular text for one locked result', async () => {
    const host = await render({ shown: 3, total: 4, locked: 1, reason: 'SIGN_UP_REQUIRED' });
    expect(host.querySelector('h2')?.textContent?.trim()).toBe(EN.results.locked_cta_one);
  });

  it('collapses the tail beyond maxRows', async () => {
    const host = await render(
      { shown: 3, total: 13, locked: 10, reason: 'SIGN_UP_REQUIRED' },
      { maxRows: 2 },
    );
    expect(rows(host)).toHaveLength(2);
    expect(host.querySelector('li.tail')?.textContent?.trim()).toBe('6–13 · 8 more locked');
    expect(host.querySelectorAll('li.tail')).toHaveLength(1);
  });

  it('marks a shorter phone variant: two rows, then the tail', async () => {
    const host = await render({ shown: 3, total: 8, locked: 5, reason: 'SIGN_UP_REQUIRED' });
    expect(rows(host).map((row) => row.classList.contains('wide-only'))).toEqual([
      false,
      false,
      true,
      true,
      true,
    ]);
    expect(host.querySelector('li.tail.wide-only')).toBeNull();
    expect(host.querySelector('li.tail.narrow-only')?.textContent?.trim()).toBe(
      '6–8 · 3 more locked',
    );
  });

  it('renders nothing when nothing is locked', async () => {
    const host = await render({ shown: 20, total: 40, locked: 0, reason: null });
    expect(host.children).toHaveLength(0);
  });

  it('follows page 2 of an anonymous ranking without items', async () => {
    const host = await render({ shown: 3, total: 13, locked: 10, reason: 'SIGN_UP_REQUIRED' });
    expect(rows(host)[0].getAttribute('aria-label')).toBe('Result 4, locked');
    expect(host.querySelector('h2')?.textContent?.trim()).toBe('10 more results are locked');
  });
});
