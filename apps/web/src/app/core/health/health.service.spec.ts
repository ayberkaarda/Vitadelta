import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import {
  HEALTH_URL,
  HealthResponse,
  HealthService,
  InvalidHealthResponseError,
} from './health.service';

describe('HealthService', () => {
  let service: HealthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(HealthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests the same-origin health endpoint with GET', async () => {
    const result = firstValueFrom(service.getHealth());

    const request = http.expectOne(HEALTH_URL);
    expect(request.request.method).toBe('GET');
    expect(request.request.url).toBe('/api/v1/health');
    request.flush({ status: 'UP' });

    await expect(result).resolves.toEqual<HealthResponse>({ status: 'UP' });
  });

  it.each(['UP', 'DEGRADED', 'DOWN'] as const)('returns the %s status', async (status) => {
    const result = firstValueFrom(service.getHealth());

    http.expectOne(HEALTH_URL).flush({ status });

    await expect(result).resolves.toEqual({ status });
  });

  it('propagates HTTP errors', async () => {
    const result = firstValueFrom(service.getHealth());

    http
      .expectOne(HEALTH_URL)
      .flush({ status: 'DOWN' }, { status: 503, statusText: 'Service Unavailable' });

    await expect(result).rejects.toBeInstanceOf(HttpErrorResponse);
  });

  it('propagates network failures', async () => {
    const result = firstValueFrom(service.getHealth());

    http.expectOne(HEALTH_URL).error(new ProgressEvent('error'));

    await expect(result).rejects.toBeInstanceOf(HttpErrorResponse);
  });

  it('rejects a body that does not match the health contract', async () => {
    const result = firstValueFrom(service.getHealth());

    http.expectOne(HEALTH_URL).flush({ status: 'UNKNOWN' });

    await expect(result).rejects.toBeInstanceOf(InvalidHealthResponseError);
  });
});
