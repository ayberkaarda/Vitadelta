import { parseThreshold } from './alert-form.component';

describe('parseThreshold', () => {
  it.each([
    ['5', 5],
    ['0.01', 0.01],
    ['99.99', 99.99],
    ['7,5', 7.5],
    [' 12 ', 12],
  ])('accepts %s', (raw, value) => {
    expect(parseThreshold(raw)).toBe(value);
  });

  it.each(['', '0', '0.00', '100', '5.125', '-1', 'abc', '1e2'])('rejects "%s"', (raw) => {
    expect(parseThreshold(raw)).toBeNull();
  });
});
