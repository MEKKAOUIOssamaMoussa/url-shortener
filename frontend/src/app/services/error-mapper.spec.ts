import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { mapHttpError } from './error-mapper';

describe('error-mapper', () => {
  it('should map status 0 to network/CORS error message', () => {
    const error = new HttpErrorResponse({ status: 0, statusText: 'Unknown Error' });
    const result = mapHttpError(error);

    expect(result.message).toBe('Cannot reach the server (network or CORS)');
    expect(result.details).toBeUndefined();
    expect(result.retryAfterSeconds).toBeUndefined();
  });

  it('should map 400 ProblemDetail with validation errors', () => {
    const problem = {
      title: 'Bad Request',
      status: 400,
      detail: 'Validation failed for request',
      instance: '/api/urls',
      errors: [
        { field: 'url', message: 'must be a valid absolute HTTP or HTTPS URL with a host' },
      ],
    };
    const error = new HttpErrorResponse({
      status: 400,
      error: problem,
    });

    const result = mapHttpError(error);
    expect(result.message).toBe('Validation failed for request');
    expect(result.details).toEqual(['url: must be a valid absolute HTTP or HTTPS URL with a host']);
  });

  it('should map 404 ProblemDetail to detail message', () => {
    const problem = {
      title: 'Not Found',
      status: 404,
      detail: 'Short URL not found: xyz',
      instance: '/api/urls/xyz/stats',
    };
    const error = new HttpErrorResponse({
      status: 404,
      error: problem,
    });

    const result = mapHttpError(error);
    expect(result.message).toBe('Short URL not found: xyz');
  });

  it('should map 429 ProblemDetail and extract Retry-After header', () => {
    const headers = new HttpHeaders().set('Retry-After', '45');
    const problem = {
      title: 'Too Many Requests',
      status: 429,
      detail: 'Rate limit exceeded. Please retry after 45 seconds.',
      instance: '/api/urls',
    };
    const error = new HttpErrorResponse({
      status: 429,
      headers,
      error: problem,
    });

    const result = mapHttpError(error);
    expect(result.message).toBe('Rate limit exceeded. Please retry after 45 seconds.');
    expect(result.retryAfterSeconds).toBe(45);
  });

  it('should fallback to 60s for 429 when Retry-After is absent or invalid', () => {
    const errorNoHeader = new HttpErrorResponse({
      status: 429,
      error: { title: 'Too Many Requests', status: 429 },
    });
    const resultNoHeader = mapHttpError(errorNoHeader);
    expect(resultNoHeader.retryAfterSeconds).toBe(60);

    const invalidHeaders = new HttpHeaders().set('Retry-After', 'not-a-number');
    const errorInvalid = new HttpErrorResponse({
      status: 429,
      headers: invalidHeaders,
    });
    const resultInvalid = mapHttpError(errorInvalid);
    expect(resultInvalid.retryAfterSeconds).toBe(60);
  });
});
