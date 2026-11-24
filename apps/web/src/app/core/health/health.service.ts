import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export const HEALTH_STATUSES = ['UP', 'DEGRADED', 'DOWN'] as const;

export type HealthStatus = (typeof HEALTH_STATUSES)[number];

export interface HealthResponse {
  status: HealthStatus;
}

export const HEALTH_URL = '/api/v1/health';

export class InvalidHealthResponseError extends Error {
  constructor() {
    super('Health response does not match the expected contract');
    this.name = 'InvalidHealthResponseError';
  }
}

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null || !('status' in value)) {
    return false;
  }
  const { status } = value;
  return HEALTH_STATUSES.some((known) => known === status);
}

/** Reads the public API health endpoint on the same origin. */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  getHealth(): Observable<HealthResponse> {
    return this.http.get<unknown>(HEALTH_URL).pipe(
      map((body) => {
        if (!isHealthResponse(body)) {
          throw new InvalidHealthResponseError();
        }
        return { status: body.status };
      }),
    );
  }
}
