import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EN, translocoTesting } from '../../testing/transloco-testing';
import { QuotaChipComponent } from './quota-chip.component';

describe('QuotaChipComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideRouter([])],
    }),
  );

  async function render(used: number, limit: number, compact = false) {
    const fixture = TestBed.createComponent(QuotaChipComponent);
    fixture.componentRef.setInput('used', used);
    fixture.componentRef.setInput('limit', limit);
    fixture.componentRef.setInput('compact', compact);
    await fixture.whenStable();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  const text = (host: HTMLElement) => host.textContent?.replace(/\s+/g, ' ').trim();
  const pips = (host: HTMLElement) =>
    Array.from(host.querySelectorAll('.pip')).map((pip) => pip.classList.contains('on'));

  it.each([
    [0, '3/3 free queries left', [true, true, true]],
    [1, '2/3 free queries left', [true, true, false]],
  ])('after %i queries shows "%s"', async (used, label, expectedPips) => {
    const { fixture, host } = await render(used, 3);
    expect(fixture.componentInstance.state()).toBe('available');
    expect(host.querySelector('[role="status"]')).not.toBeNull();
    expect(text(host)).toBe(label);
    expect(pips(host)).toEqual(expectedPips);
    expect(host.querySelector('.quota')?.classList.contains('is-last')).toBe(false);
  });

  it('switches to the warning tone for the last query', async () => {
    const { fixture, host } = await render(2, 3);
    expect(fixture.componentInstance.state()).toBe('last');
    expect(text(host)).toBe('1/3 free query left');
    expect(host.querySelector('.quota')?.classList.contains('is-last')).toBe(true);
  });

  it('turns into the sign-up link once exhausted', async () => {
    const { fixture, host } = await render(3, 3);
    expect(fixture.componentInstance.state()).toBe('exhausted');
    const link = host.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/auth/register');
    expect(text(host)).toBe(EN.quota.exhausted);
    expect(host.querySelector('[role="status"]')).not.toBeNull();
  });

  it('treats counters above the limit as exhausted', async () => {
    const { fixture } = await render(5, 3);
    expect(fixture.componentInstance.state()).toBe('exhausted');
  });

  it('uses short labels in the compact variant', async () => {
    expect(text((await render(1, 3, true)).host)).toBe('2/3 left');
    expect(text((await render(3, 3, true)).host)).toBe(EN.quota.exhausted_short);
  });
});
