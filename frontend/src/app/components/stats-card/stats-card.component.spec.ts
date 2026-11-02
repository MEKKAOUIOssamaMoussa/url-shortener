import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ShortUrlStatsResponse } from '../../models/short-url.model';
import { UrlApiService } from '../../services/url-api.service';
import { StatsCardComponent } from './stats-card.component';

describe('StatsCardComponent', () => {
  let component: StatsCardComponent;
  let fixture: ComponentFixture<StatsCardComponent>;
  let mockUrlApiService: {
    create: ReturnType<typeof vi.fn>;
    stats: ReturnType<typeof vi.fn>;
  };

  const sampleStats: ShortUrlStatsResponse = {
    code: 'abc12345',
    totalClicks: 25,
    daily: [
      { day: '2026-11-02', count: 15 },
      { day: '2026-11-01', count: 10 },
    ],
  };

  beforeEach(async () => {
    mockUrlApiService = {
      create: vi.fn(),
      stats: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [StatsCardComponent],
      providers: [
        { provide: UrlApiService, useValue: mockUrlApiService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StatsCardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should render form with code input and view stats button', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input#stats-code-input')).toBeTruthy();
    expect(compiled.querySelector('button[type="submit"]')).toBeTruthy();
  });

  it('should validate short code pattern and block submission for invalid code', () => {
    const invalidCodes = ['', 'code_with_underscore', 'special$char', 'moreThan16CharactersLongCode'];

    for (const invalidCode of invalidCodes) {
      component.code.set(invalidCode);
      component.onSubmit(new Event('submit'));
      fixture.detectChanges();

      expect(mockUrlApiService.stats).not.toHaveBeenCalled();
      expect(component.validationError()).toBe('Short code must be 1 to 16 alphanumeric characters ([0-9A-Za-z])');

      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.querySelector('.error-text')?.textContent).toContain(
        'Short code must be 1 to 16 alphanumeric characters'
      );
    }
  });

  it('should fetch stats and render totalClicks and daily table on valid submission', () => {
    mockUrlApiService.stats.mockReturnValue(of(sampleStats));

    component.code.set('abc12345');
    component.onSubmit(new Event('submit'));
    fixture.detectChanges();

    expect(mockUrlApiService.stats).toHaveBeenCalledWith('abc12345');
    expect(component.stats()).toEqual(sampleStats);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.click-number')?.textContent).toBe('25');

    const rows = compiled.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('2026-11-02');
    expect(rows[0].textContent).toContain('15');
    expect(rows[1].textContent).toContain('2026-11-01');
    expect(rows[1].textContent).toContain('10');
  });

  it('should render empty state message when totalClicks is 0', () => {
    const emptyStats: ShortUrlStatsResponse = {
      code: 'abc12345',
      totalClicks: 0,
      daily: [],
    };
    mockUrlApiService.stats.mockReturnValue(of(emptyStats));

    component.code.set('abc12345');
    component.onSubmit(new Event('submit'));
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.no-clicks-message')?.textContent).toContain('No clicks recorded yet.');
    expect(compiled.querySelector('table')).toBeNull();
  });

  it('should render 404 ProblemDetail error when code is not found', () => {
    const notFoundError = new HttpErrorResponse({
      status: 404,
      error: {
        title: 'Not Found',
        status: 404,
        detail: 'Short URL not found: unknownXyz',
        instance: '/api/urls/unknownXyz/stats',
      },
    });
    mockUrlApiService.stats.mockReturnValue(throwError(() => notFoundError));

    component.code.set('unknownXyz');
    component.onSubmit(new Event('submit'));
    fixture.detectChanges();

    expect(component.error()?.message).toBe('Short URL not found: unknownXyz');

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.error-banner p')?.textContent).toContain(
      'Short URL not found: unknownXyz'
    );
  });

  it('should automatically fetch stats when prefilledCode is provided', () => {
    mockUrlApiService.stats.mockReturnValue(of(sampleStats));

    fixture.componentRef.setInput('prefilledCode', 'prefilledCode1');
    fixture.detectChanges();

    expect(component.code()).toBe('prefilledCode1');
    expect(mockUrlApiService.stats).toHaveBeenCalledWith('prefilledCode1');
  });
});
