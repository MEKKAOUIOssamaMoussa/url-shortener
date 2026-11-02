import { Component, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ShortUrlStatsResponse, UiError } from '../../models/short-url.model';
import { UrlApiService } from '../../services/url-api.service';
import { mapHttpError } from '../../services/error-mapper';

const CODE_REGEX = /^[0-9A-Za-z]{1,16}$/;

@Component({
  selector: 'app-stats-card',
  standalone: true,
  templateUrl: './stats-card.component.html',
  styleUrl: './stats-card.component.css',
})
export class StatsCardComponent {
  private readonly urlApiService = inject(UrlApiService);

  readonly prefilledCode = input<string | null>(null);

  readonly requestStarted = output<void>();
  readonly requestFinished = output<void>();

  readonly code = signal('');
  readonly validationError = signal<string | null>(null);
  readonly loading = signal(false);
  readonly stats = signal<ShortUrlStatsResponse | null>(null);
  readonly error = signal<UiError | null>(null);

  constructor() {
    effect(() => {
      const prefilled = this.prefilledCode();
      if (prefilled) {
        this.code.set(prefilled);
        this.fetchStats(prefilled);
      }
    });
  }

  onCodeInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.code.set(target.value);
    if (this.validationError()) {
      this.validationError.set(null);
    }
  }

  onSubmit(event: Event): void {
    event.preventDefault();
    const trimmed = this.code().trim();
    if (!CODE_REGEX.test(trimmed)) {
      this.validationError.set('Short code must be 1 to 16 alphanumeric characters ([0-9A-Za-z])');
      return;
    }

    this.fetchStats(trimmed);
  }

  fetchStats(codeToFetch: string): void {
    this.validationError.set(null);
    this.error.set(null);
    this.stats.set(null);
    this.loading.set(true);
    this.requestStarted.emit();

    this.urlApiService.stats(codeToFetch).subscribe({
      next: (response) => {
        this.loading.set(false);
        this.stats.set(response);
        this.requestFinished.emit();
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(mapHttpError(err));
        this.requestFinished.emit();
      },
    });
  }
}
