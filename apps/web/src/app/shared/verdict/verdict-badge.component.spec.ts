import { TestBed } from '@angular/core/testing';

import { EN, translocoTesting } from '../../testing/transloco-testing';
import { VERDICTS, Verdict } from '../../core/api/api-types';
import { VerdictBadgeComponent } from './verdict-badge.component';
import { VERDICT_STYLE } from './verdict-style';

describe('VerdictBadgeComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  async function render(verdict: Verdict, size: 'md' | 'sm' = 'md') {
    const fixture = TestBed.createComponent(VerdictBadgeComponent);
    fixture.componentRef.setInput('verdict', verdict);
    fixture.componentRef.setInput('size', size);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it.each(VERDICTS)('renders %s with its label, icon and tone modifier', async (verdict) => {
    const host = await render(verdict);
    const label = host.querySelector('.vlabel');
    expect(label?.getAttribute('data-verdict')).toBe(verdict);
    expect(label?.classList.contains(VERDICT_STYLE[verdict].modifier)).toBe(true);
    expect(label?.querySelector('svg')).not.toBeNull();
    expect(label?.textContent?.trim()).toBe(EN.verdict[verdict]);
  });

  it('marks INSUFFICIENT_DATA as the dashed neutral state', () => {
    expect(VERDICT_STYLE.INSUFFICIENT_DATA.dashed).toBe(true);
    expect(VERDICT_STYLE.INSUFFICIENT_DATA.icon).toBe('help');
    expect(VERDICTS.filter((verdict) => VERDICT_STYLE[verdict].dashed)).toEqual([
      'INSUFFICIENT_DATA',
    ]);
  });

  it('supports the small size', async () => {
    const host = await render('NOT_WORTH_IT', 'sm');
    expect(host.querySelector('.vlabel')?.classList.contains('sm')).toBe(true);
  });
});
