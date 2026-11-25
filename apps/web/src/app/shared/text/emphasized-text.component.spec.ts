import { TestBed } from '@angular/core/testing';

import { EmphasizedTextComponent, emphasisSegments, emphasize } from './emphasized-text.component';

describe('emphasized text', () => {
  it('splits marked parameters into strong segments', () => {
    expect(emphasisSegments(`Costs ${emphasize('1.67')} ${emphasize('TRY')} more.`)).toEqual([
      { text: 'Costs ', strong: false },
      { text: '1.67', strong: true },
      { text: ' ', strong: false },
      { text: 'TRY', strong: true },
      { text: ' more.', strong: false },
    ]);
    expect(emphasisSegments('No markers')).toEqual([{ text: 'No markers', strong: false }]);
    expect(emphasisSegments('')).toEqual([]);
  });

  it('drops stray or unbalanced markers instead of rendering them', () => {
    expect(emphasisSegments('ab')).toEqual([{ text: 'ab', strong: false }]);
    expect(emphasisSegments('ab')).toEqual([
      { text: 'a', strong: false },
      { text: 'b', strong: true },
    ]);
  });

  it('renders markup in a parameter as text, never as HTML', async () => {
    const fixture = TestBed.createComponent(EmphasizedTextComponent);
    fixture.componentRef.setInput('text', `Value ${emphasize('<img src=x onerror=alert(1)>')}!`);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('strong')?.textContent).toBe('<img src=x onerror=alert(1)>');
    expect(host.textContent).toBe('Value <img src=x onerror=alert(1)>!');
  });
});
