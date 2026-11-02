import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { environment } from '../../environments/environment';
import { ShortUrlResponse, ShortUrlStatsResponse } from '../models/short-url.model';
import { UrlApiService } from './url-api.service';

describe('UrlApiService', () => {
  let service: UrlApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        UrlApiService,
      ],
    });

    service = TestBed.inject(UrlApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should post to /api/urls with payload on create()', () => {
    const mockResponse: ShortUrlResponse = {
      code: 'abc123',
      shortUrl: 'http://localhost:8080/abc123',
      targetUrl: 'https://example.com/target',
      createdAt: '2026-11-02T20:00:00Z',
    };

    let actualResponse: ShortUrlResponse | undefined;
    service.create('https://example.com/target').subscribe((res) => {
      actualResponse = res;
    });

    const req = httpTesting.expectOne(`${environment.apiBaseUrl}/api/urls`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ url: 'https://example.com/target' });

    req.flush(mockResponse);
    expect(actualResponse).toEqual(mockResponse);
  });

  it('should get from /api/urls/{code}/stats on stats()', () => {
    const mockStats: ShortUrlStatsResponse = {
      code: 'abc123',
      totalClicks: 42,
      daily: [{ day: '2026-11-02', count: 42 }],
    };

    let actualStats: ShortUrlStatsResponse | undefined;
    service.stats('abc123').subscribe((res) => {
      actualStats = res;
    });

    const req = httpTesting.expectOne(`${environment.apiBaseUrl}/api/urls/abc123/stats`);
    expect(req.request.method).toBe('GET');

    req.flush(mockStats);
    expect(actualStats).toEqual(mockStats);
  });
});
