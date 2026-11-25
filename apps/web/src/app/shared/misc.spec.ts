import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { EN, translocoTesting } from '../testing/transloco-testing';
import { DisclaimerComponent } from './disclaimer/disclaimer.component';
import { ICONS, IconName } from './icon/icon-paths';
import { IconComponent } from './icon/icon.component';
import { PaginationComponent } from './pagination/pagination.component';
import { EmptyStateComponent } from './state/empty-state.component';
import { SkeletonComponent } from './state/skeleton.component';

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

async function render<T>(type: Type<T>, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(type);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  await fixture.whenStable();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('shared presentational components', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  it.each(Object.keys(ICONS) as IconName[])('icon %s renders a decorative SVG', async (name) => {
    const { host } = await render(IconComponent, { name });
    const svg = host.querySelector('svg');
    expect(svg?.namespaceURI).toBe('http://www.w3.org/2000/svg');
    expect(svg?.getAttribute('aria-hidden')).toBe('true');
    expect(svg?.getAttribute('focusable')).toBe('false');
    expect(svg?.getAttribute('viewBox')).toBe('0 0 20 20');
    expect(svg?.children.length).toBeGreaterThan(0);
    for (const child of Array.from(svg?.children ?? [])) {
      expect(child.namespaceURI).toBe('http://www.w3.org/2000/svg');
    }
  });

  it('icon supports the small size, spinning and dashed circles', async () => {
    const { host } = await render(IconComponent, { name: 'help', size: 'sm', spin: true });
    const svg = host.querySelector('svg');
    expect(svg?.classList.contains('i-sm')).toBe(true);
    expect(svg?.classList.contains('spin')).toBe(true);
    expect(host.querySelector('circle')?.getAttribute('stroke-dasharray')).toBe('2.4 2.4');
  });

  it('disclaimer renders disclaimer.not_medical_advice in both variants', async () => {
    const box = await render(DisclaimerComponent);
    expect(text(box.host)).toBe(EN.disclaimer.not_medical_advice);
    expect(box.host.querySelector('.disclaimer svg')).not.toBeNull();
    const inline = await render(DisclaimerComponent, { variant: 'inline' });
    expect(text(inline.host)).toBe(EN.disclaimer.not_medical_advice);
    expect(inline.host.querySelector('.disclaimer')).toBeNull();
  });

  it('pagination shows the range and emits page changes', async () => {
    const { fixture, host } = await render(PaginationComponent, {
      page: 1,
      pageSize: 5,
      total: 12,
    });
    expect(text(host.querySelector('p'))).toBe('Showing 1–5 of 12');
    const [previous, next] = Array.from(host.querySelectorAll('button'));
    expect(previous.disabled).toBe(true);
    expect(next.disabled).toBe(false);
    const pages: number[] = [];
    fixture.componentInstance.pageChange.subscribe((page) => pages.push(page));
    next.click();
    expect(pages).toEqual([2]);

    fixture.componentRef.setInput('page', 3);
    await fixture.whenStable();
    expect(text(host.querySelector('p'))).toBe('Showing 11–12 of 12');
    expect(host.querySelectorAll('button')[1].disabled).toBe(true);
    host.querySelectorAll('button')[0].click();
    expect(pages).toEqual([2, 2]);
  });

  it('pagination renders nothing for an empty list', async () => {
    const { host } = await render(PaginationComponent, { page: 1, pageSize: 5, total: 0 });
    expect(host.querySelector('nav')).toBeNull();
  });

  it('empty state renders icon well, heading level and tone', async () => {
    const { host } = await render(EmptyStateComponent, {
      heading: 'Nothing here',
      icon: 'search',
      tone: 'warn',
      headingLevel: '3',
    });
    expect(host.querySelector('.state')?.classList.contains('t-warn')).toBe(true);
    expect(text(host.querySelector('h3'))).toBe('Nothing here');
    expect(host.querySelector('.ic svg')).not.toBeNull();
  });

  it('skeleton is busy, announces its status and hides the bars', async () => {
    const { host } = await render(SkeletonComponent, { rows: 3, status: 'Ranking products…' });
    expect(host.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(text(host.querySelector('[role="status"]'))).toBe('Ranking products…');
    expect(host.querySelectorAll('.line[aria-hidden="true"]')).toHaveLength(3);
  });
});
