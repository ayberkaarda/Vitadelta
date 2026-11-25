import { startApplication } from './app.start';
import { takeEmailToken } from './core/auth/email-token';

const TOKEN = 'c3RhcnQtdXAtc3ludGhldGljLXRlc3QtdG9rZW4tMDE';

describe('startApplication', () => {
  afterEach(() => window.history.replaceState(null, '', '/'));

  it('takes the e-mail token out of the address bar before the application starts', async () => {
    window.history.replaceState(null, '', `/auth/verify?lang=en#token=${TOKEN}`);
    const seenAtStart: string[] = [];
    const result = await startApplication(async () => {
      // The first moment application code (and so any request) can run.
      seenAtStart.push(window.location.href);
      return 'started';
    });
    expect(result).toBe('started');
    expect(seenAtStart).toHaveLength(1);
    expect(seenAtStart[0]).not.toContain(TOKEN);
    expect(seenAtStart[0]).toMatch(/\/auth\/verify\?lang=en$/);
    expect(takeEmailToken('/auth/verify')).toBe(TOKEN);
    expect(takeEmailToken('/auth/verify')).toBeNull();
  });

  it('starts other pages with their address unchanged', async () => {
    window.history.replaceState(null, '', '/privacy#rights');
    const seenAtStart: string[] = [];
    await startApplication(async () => seenAtStart.push(window.location.href));
    expect(seenAtStart[0]).toMatch(/\/privacy#rights$/);
  });

  it('passes a start-up failure to the caller', async () => {
    await expect(startApplication(() => Promise.reject(new Error('boom')))).rejects.toThrow('boom');
  });
});
