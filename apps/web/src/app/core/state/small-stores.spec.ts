import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { SessionStore } from '../auth/session.store';
import { PreferenceStorage } from './preference-storage';
import { UnreadCountStore } from './unread-count.store';

describe('UnreadCountStore', () => {
  const authenticated = signal(true);
  const generation = signal(1);
  const getUnreadNotificationCount = vi.fn();

  beforeEach(() => {
    authenticated.set(true);
    generation.set(1);
    getUnreadNotificationCount.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: { getUnreadNotificationCount } },
        { provide: SessionStore, useValue: { isAuthenticated: authenticated, generation } },
      ],
    });
  });

  it('never shows a count of a previous session', async () => {
    const late = new Subject<{ unreadCount: number }>();
    getUnreadNotificationCount.mockReturnValue(of({ unreadCount: 2 }));
    const store = TestBed.inject(UnreadCountStore);
    await store.refresh();
    expect(store.count()).toBe(2);

    getUnreadNotificationCount.mockReturnValue(late.asObservable());
    const refreshing = store.refresh();
    // Another account signs in: the old count disappears at once.
    generation.set(2);
    expect(store.count()).toBeNull();
    late.next({ unreadCount: 7 });
    late.complete();
    await refreshing;
    expect(store.count()).toBeNull();
  });

  it('loads the count for signed-in users', async () => {
    getUnreadNotificationCount.mockReturnValue(of({ unreadCount: 7 }));
    const store = TestBed.inject(UnreadCountStore);
    await store.refresh();
    expect(store.count()).toBe(7);
    store.set(3);
    expect(store.count()).toBe(3);
  });

  it('is unknown when signed out or when the request fails', async () => {
    const store = TestBed.inject(UnreadCountStore);
    getUnreadNotificationCount.mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'http', status: 500, code: null })),
    );
    await store.refresh();
    expect(store.count()).toBeNull();
    authenticated.set(false);
    await store.refresh();
    expect(getUnreadNotificationCount).toHaveBeenCalledTimes(1);
    expect(store.count()).toBeNull();
  });
});

describe('PreferenceStorage', () => {
  afterEach(() => vi.restoreAllMocks());

  it('reads, writes and removes prefixed values', () => {
    const storage = TestBed.inject(PreferenceStorage);
    storage.write('lang', 'tr');
    expect(localStorage.getItem('vd.lang')).toBe('tr');
    expect(storage.read('lang')).toBe('tr');
    storage.write('lang', null);
    expect(storage.read('lang')).toBeNull();
  });

  it('survives unavailable storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const storage = TestBed.inject(PreferenceStorage);
    expect(storage.read('lang')).toBeNull();
    expect(() => storage.write('lang', 'en')).not.toThrow();
  });
});
