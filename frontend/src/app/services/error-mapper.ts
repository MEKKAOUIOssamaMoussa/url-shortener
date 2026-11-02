import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail, UiError } from '../models/short-url.model';

export function mapHttpError(error: HttpErrorResponse): UiError {
  if (error.status === 0) {
    return {
      message: 'Cannot reach the server (network or CORS)',
    };
  }

  const problem: ProblemDetail | null =
    error.error && typeof error.error === 'object' ? (error.error as ProblemDetail) : null;

  if (error.status === 429) {
    const retryAfterHeader = error.headers?.get('Retry-After');
    let retryAfterSeconds = 60;
    if (retryAfterHeader) {
      const parsed = parseInt(retryAfterHeader, 10);
      if (!Number.isNaN(parsed) && parsed > 0) {
        retryAfterSeconds = parsed;
      }
    }

    return {
      message: problem?.detail || problem?.title || 'Too many requests. Please slow down.',
      retryAfterSeconds,
    };
  }

  if (error.status === 400) {
    const details = problem?.errors?.map((e) => (e.field ? `${e.field}: ${e.message}` : e.message)) ?? [];
    return {
      message: problem?.detail || problem?.title || 'Invalid request',
      details: details.length > 0 ? details : undefined,
    };
  }

  if (error.status === 404) {
    return {
      message: problem?.detail || problem?.title || 'Resource not found',
    };
  }

  return {
    message: problem?.detail || problem?.title || error.message || 'An unexpected error occurred',
  };
}
