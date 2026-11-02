import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ShortUrlResponse } from './models/short-url.model';
import { ColdStartBannerComponent } from './components/cold-start-banner/cold-start-banner.component';
import { ShortenCardComponent } from './components/shorten-card/shorten-card.component';
import { StatsCardComponent } from './components/stats-card/stats-card.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    ColdStartBannerComponent,
    ShortenCardComponent,
    StatsCardComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly destroyRef = inject(DestroyRef);
  private coldStartTimer: ReturnType<typeof setTimeout> | null = null;

  readonly activeRequests = signal(0);
  readonly isColdStart = signal(false);
  readonly latestCreatedCode = signal<string | null>(null);

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.clearColdStartTimer();
    });
  }

  onRequestStarted(): void {
    const current = this.activeRequests();
    this.activeRequests.set(current + 1);

    if (current === 0) {
      this.clearColdStartTimer();
      this.coldStartTimer = setTimeout(() => {
        if (this.activeRequests() > 0) {
          this.isColdStart.set(true);
        }
      }, 2500);
    }
  }

  onRequestFinished(): void {
    const current = this.activeRequests();
    const updated = Math.max(0, current - 1);
    this.activeRequests.set(updated);

    if (updated === 0) {
      this.clearColdStartTimer();
      this.isColdStart.set(false);
    }
  }

  onUrlCreated(response: ShortUrlResponse): void {
    this.latestCreatedCode.set(response.code);
  }

  private clearColdStartTimer(): void {
    if (this.coldStartTimer !== null) {
      clearTimeout(this.coldStartTimer);
      this.coldStartTimer = null;
    }
  }
}
