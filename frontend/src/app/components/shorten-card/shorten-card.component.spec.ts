import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ShortUrlResponse } from '../../models/short-url.model';
import { UrlApiService } from '../../services/url-api.service';
import { ShortenCardComponent } from './shorten-card.component';

describe('ShortenCardComponent', () => {
  let component: ShortenCardComponent;
  let fixture: ComponentFixture<ShortenCardComponent>;
  let mockUrlApiService: {
    create: ReturnType<typeof vi.fn>;
    stats: ReturnType<typeof vi.fn>;
  };

  const sampleResponse: ShortUrlResponse = {
    code: 'abc12345',
    shortUrl: 'http://localhost:8080/abc12345',
    targetUrl: 'https://example.com/a',
    createdAt: '2026-11-02T20:00:00Z',
  };

  beforeEach(async () => {
    mockUrlApiService = {
      create: vi.fn(),
      stats: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [ShortenCardComponent],
      providers: [
        { provide: UrlApiService, useValue: mockUrlApiService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShortenCardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('should render form with url input and shorten button', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input#url-input')).toBeTruthy();
    expect(compiled.querySelector('button[type="submit"]')).toBeTruthy();
  });

  it('should block submission for invalid URLs and display validation error', () => {
    const testCases = ['http://', 'ftp://x', 'not a url'];

    for (const invalidUrl of testCases) {
      component.url.set(invalidUrl);
      component.onSubmit(new Event('submit'));
      fixture.detectChanges();

      expect(mockUrlApiService.create).not.toHaveBeenCalled();
      expect(component.validationError()).toBe('Please enter a valid absolute HTTP or HTTPS URL with a host');

      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.error-text')?.textContent).toContain(
        'Please enter a valid absolute HTTP or HTTPS URL with a host'
      );
    }
  });

  it('should submit valid URL, render result link with security attributes, and emit urlCreated', () => {
    mockUrlApiService.create.mockReturnValue(of(sampleResponse));
    let emittedUrl: ShortUrlResponse | undefined;
    component.urlCreated.subscribe((res) => {
      emittedUrl = res;
    });

    component.url.set('https://example.com/a');
    component.onSubmit(new Event('submit'));
    fixture.detectChanges();

    expect(mockUrlApiService.create).toHaveBeenCalledWith('https://example.com/a');
    expect(emittedUrl).toEqual(sampleResponse);
    expect(component.createdUrl()).toEqual(sampleResponse);

    const compiled = fixture.nativeElement as HTMLElement;
    const link = compiled.querySelector('a.short-link') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toBe('http://localhost:8080/abc12345');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.textContent).toContain('http://localhost:8080/abc12345');
  });

  it('should provide feedback when copyToClipboard succeeds', async () => {
    const writeTextSpy = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, {
      clipboard: { writeText: writeTextSpy },
    });

    component.copyToClipboard('http://localhost:8080/abc12345');
    expect(writeTextSpy).toHaveBeenCalledWith('http://localhost:8080/abc12345');

    await Promise.resolve();
    fixture.detectChanges();
    expect(component.copyFeedback()).toBe('Copied!');
  });

  it('should display fallback message when Clipboard API is unavailable', () => {
    const originalClipboard = navigator.clipboard;
    // Simulate missing clipboard API
    Object.defineProperty(navigator, 'clipboard', {
      value: undefined,
      configurable: true,
      writable: true,
    });

    component.copyToClipboard('http://localhost:8080/abc12345');
    fixture.detectChanges();
    expect(component.copyFeedback()).toBe('Clipboard API unavailable. Please copy manually.');

    // Restore
    Object.defineProperty(navigator, 'clipboard', {
      value: originalClipboard,
      configurable: true,
      writable: true,
    });
  });

  it('should handle 400 ProblemDetail errors and render message and error list', () => {
    const errorResponse = new HttpErrorResponse({
      status: 400,
      error: {
        title: 'Bad Request',
        status: 400,
        detail: 'Invalid URL input',
        errors: [{ field: 'url', message: 'must not be blank' }],
      },
    });
    mockUrlApiService.create.mockReturnValue(throwError(() => errorResponse));

    component.url.set('https://example.com/a');
    component.onSubmit(new Event('submit'));
    fixture.detectChanges();

    expect(component.error()?.message).toBe('Invalid URL input');
    expect(component.error()?.details).toEqual(['url: must not be blank']);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.error-banner p')?.textContent).toContain('Invalid URL input');
    expect(compiled.querySelector('.error-banner li')?.textContent).toContain('url: must not be blank');
  });

  it('should handle 429 response by starting countdown, disabling button, and re-enabling at 0', () => {
    vi.useFakeTimers();

    const headers = new HttpHeaders().set('Retry-After', '3');
    const errorResponse = new HttpErrorResponse({
      status: 429,
      headers,
      error: { title: 'Too Many Requests', status: 429, detail: 'Rate limit exceeded' },
    });
    mockUrlApiService.create.mockReturnValue(throwError(() => errorResponse));

    component.url.set('https://example.com/a');
    component.onSubmit(new Event('submit'));
    fixture.detectChanges();

    expect(component.retryAfterSeconds()).toBe(3);
    const compiled = fixture.nativeElement as HTMLElement;
    const submitBtn = compiled.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(true);

    // Advance 1s -> 2s remaining
    vi.advanceTimersByTime(1000);
    expect(component.retryAfterSeconds()).toBe(2);

    // Advance 1s -> 1s remaining
    vi.advanceTimersByTime(1000);
    expect(component.retryAfterSeconds()).toBe(1);

    // Advance 1s -> 0 remaining (countdown finished)
    vi.advanceTimersByTime(1000);
    expect(component.retryAfterSeconds()).toBe(0);
    fixture.detectChanges();

    expect(submitBtn.disabled).toBe(false);
  });
});
