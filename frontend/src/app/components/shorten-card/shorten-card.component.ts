import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ShortUrlResponse, UiError } from '../../models/short-url.model';
import { UrlApiService } from '../../services/url-api.service';
import { mapHttpError } from '../../services/error-mapper';
import { isValidUrl } from '../../utils/url-validator';

@Component({
  selector: 'app-shorten-card',
  standalone: true,
  templateUrl: './shorten-card.component.html',
  styleUrl: './shorten-card.component.css',
})
export class ShortenCardComponent {
  private readonly urlApiService = inject(UrlApiService);
  private readonly destroyRef = inject(DestroyRef);
  private countdownTimer: ReturnType<typeof setInterval> | null = null;

  readonly requestStarted = output<void>();
  readonly requestFinished = output<void>();
  readonly urlCreated = output<ShortUrlResponse>();

  readonly url = signal('');
  readonly validationError = signal<string | null>(null);
  readonly loading = signal(false);
  readonly createdUrl = signal<ShortUrlResponse | null>(null);
  readonly copyFeedback = signal<string | null>(null);
  readonly error = signal<UiError | null>(null);
  readonly retryAfterSeconds = signal<number>(0);

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.stopCountdown();
    });
  }

  onUrlInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.url.set(target.value);
    if (this.validationError()) {
      this.validationError.set(null);
    }
  }

  onSubmit(event: Event): void {
    event.preventDefault();
    this.validationError.set(null);
    this.error.set(null);

    const trimmed = this.url().trim();
    if (!isValidUrl(trimmed)) {
      this.validationError.set('Please enter a valid absolute HTTP or HTTPS URL with a host');
      return;
    }

    this.loading.set(true);
    this.requestStarted.emit();

    this.urlApiService.create(trimmed).subscribe({
      next: (response) => {
        this.loading.set(false);
        this.createdUrl.set(response);
        this.urlCreated.emit(response);
        this.requestFinished.emit();
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        const uiErr = mapHttpError(err);
        this.error.set(uiErr);
        if (uiErr.retryAfterSeconds && uiErr.retryAfterSeconds > 0) {
          this.startCountdown(uiErr.retryAfterSeconds);
        }
        this.requestFinished.emit();
      },
    });
  }

  copyToClipboard(text: string): void {
    if (
      typeof navigator !== 'undefined' &&
      navigator.clipboard &&
      typeof navigator.clipboard.writeText === 'function'
    ) {
      navigator.clipboard.writeText(text).then(
        () => {
          this.copyFeedback.set('Copied!');
          setTimeout(() => {
            if (this.copyFeedback() === 'Copied!') {
              this.copyFeedback.set(null);
            }
          }, 2000);
        },
        () => {
          this.copyFeedback.set('Clipboard copy failed. Please copy manually.');
        }
      );
    } else {
      this.copyFeedback.set('Clipboard API unavailable. Please copy manually.');
    }
  }

  startCountdown(seconds: number): void {
    this.stopCountdown();
    this.retryAfterSeconds.set(seconds);
    this.countdownTimer = setInterval(() => {
      const current = this.retryAfterSeconds();
      if (current <= 1) {
        this.stopCountdown();
        this.retryAfterSeconds.set(0);
      } else {
        this.retryAfterSeconds.set(current - 1);
      }
    }, 1000);
  }

  private stopCountdown(): void {
    if (this.countdownTimer !== null) {
      clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    }
  }
}
