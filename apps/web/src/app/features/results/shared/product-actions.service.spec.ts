import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { ApiProblem } from '../../../core/api/api-problem';
import { SessionStore } from '../../../core/auth/session.store';
import { GOLD_A_ID, goldAlert } from '../testing/golden.fixtures';
import { ProductActionsService } from './product-actions.service';

describe('ProductActionsService', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let signedIn: ReturnType<typeof signal<boolean>>;

  beforeEach(() => {
    api = {
      updateAlert: vi.fn(),
      createAlert: vi.fn(),
      saveProduct: vi.fn(),
      resendVerification: vi.fn(),
    };
    signedIn = signal(true);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        {
          provide: SessionStore,
          useValue: {
            isAuthenticated: signedIn,
            reloadMe: vi.fn(() => Promise.resolve()),
            user: signal(null),
          },
        },
      ],
    });
  });

  it('reactivates an inactive subscription and changes its threshold with PATCH', async () => {
    const service = TestBed.inject(ProductActionsService);
    const inactive = goldAlert({ active: false, thresholdPercent: 5 });
    api['updateAlert'].mockReturnValue(of({ ...inactive, active: true, thresholdPercent: 7.5 }));
    const state = service.create(GOLD_A_ID, 'TR', true, [inactive]);

    expect(await service.createAlert(state, 'PRICE_DROP', 7.5)).toBe(true);
    expect(api['updateAlert']).toHaveBeenCalledWith(inactive.id, {
      active: true,
      thresholdPercent: 7.5,
    });
    expect(api['createAlert']).not.toHaveBeenCalled();
    expect(state.priceAlert()?.thresholdPercent).toBe(7.5);
    expect(await service.createAlert(state, 'PRICE_DROP', 7.5)).toBe(true);
    expect(api['updateAlert']).toHaveBeenCalledTimes(1);
  });

  it('rolls a failed alert creation back and keeps the saved flag', async () => {
    const service = TestBed.inject(ProductActionsService);
    api['createAlert'].mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'problem', status: 500, code: 'internal_error' })),
    );
    const state = service.create(GOLD_A_ID, 'TR', false, []);
    expect(await service.createAlert(state, 'BACK_IN_STOCK', null)).toBe(false);
    expect(api['createAlert']).toHaveBeenCalledWith({
      productId: GOLD_A_ID,
      countryCode: 'TR',
      trigger: 'BACK_IN_STOCK',
    });
    expect(state.alerts()).toEqual([]);
    expect(state.saved()).toBe(false);
    expect(state.problem()?.code).toBe('internal_error');
    expect(state.problemAction()).toBe('alert');
  });

  it('sends anonymous visitors to the login page instead of calling the API', async () => {
    signedIn.set(false);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const service = TestBed.inject(ProductActionsService);
    const state = service.create(GOLD_A_ID, 'TR', false, []);
    await service.toggleSave(state);
    await service.toggleAlert(state);
    expect(api['saveProduct']).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/auth/login'], { queryParams: {} });
    await service.resendVerification(state);
    expect(api['resendVerification']).not.toHaveBeenCalled();
  });
});
