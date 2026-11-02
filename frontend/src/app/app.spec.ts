import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { ShortUrlResponse } from './models/short-url.model';

describe('App', () => {
  let component: App;
  let fixture: ComponentFixture<App>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(App);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should create the app and render header title', () => {
    expect(component).toBeTruthy();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-title')?.textContent).toContain('URL Shortener');
    expect(compiled.querySelector('app-shorten-card')).toBeTruthy();
    expect(compiled.querySelector('app-stats-card')).toBeTruthy();
  });

  it('should show cold-start banner after 2.5s of pending request and hide upon completion', () => {
    vi.useFakeTimers();

    expect(component.isColdStart()).toBe(false);

    // Request starts
    component.onRequestStarted();
    expect(component.activeRequests()).toBe(1);
    expect(component.isColdStart()).toBe(false);

    // Advance 2.4s -> still not cold-start
    vi.advanceTimersByTime(2400);
    expect(component.isColdStart()).toBe(false);

    // Advance another 100ms -> 2.5s reached -> cold-start banner visible
    vi.advanceTimersByTime(100);
    expect(component.isColdStart()).toBe(true);
    fixture.detectChanges();

    const banner = (fixture.nativeElement as HTMLElement).querySelector('.cold-start-banner');
    expect(banner).toBeTruthy();

    // Request completes -> banner immediately hides
    component.onRequestFinished();
    expect(component.activeRequests()).toBe(0);
    expect(component.isColdStart()).toBe(false);
    fixture.detectChanges();

    const bannerAfter = (fixture.nativeElement as HTMLElement).querySelector('.cold-start-banner');
    expect(bannerAfter).toBeNull();
  });

  it('should not show cold-start banner if request completes before 2.5s', () => {
    vi.useFakeTimers();

    component.onRequestStarted();
    vi.advanceTimersByTime(1000); // 1.0s

    component.onRequestFinished();
    expect(component.activeRequests()).toBe(0);

    vi.advanceTimersByTime(2000); // 3.0s total elapsed
    expect(component.isColdStart()).toBe(false);
  });

  it('should propagate created short URL code to latestCreatedCode signal', () => {
    const mockResponse: ShortUrlResponse = {
      code: 'xyz987',
      shortUrl: 'http://localhost:8080/xyz987',
      targetUrl: 'https://example.com/xyz',
      createdAt: '2026-11-02T20:00:00Z',
    };

    component.onUrlCreated(mockResponse);
    expect(component.latestCreatedCode()).toBe('xyz987');
  });
});
