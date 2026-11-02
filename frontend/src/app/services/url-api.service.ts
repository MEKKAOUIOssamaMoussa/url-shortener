import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ShortUrlRequest, ShortUrlResponse, ShortUrlStatsResponse } from '../models/short-url.model';

@Injectable({
  providedIn: 'root',
})
export class UrlApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  create(url: string): Observable<ShortUrlResponse> {
    const body: ShortUrlRequest = { url };
    return this.http.post<ShortUrlResponse>(`${this.baseUrl}/api/urls`, body);
  }

  stats(code: string): Observable<ShortUrlStatsResponse> {
    return this.http.get<ShortUrlStatsResponse>(`${this.baseUrl}/api/urls/${encodeURIComponent(code)}/stats`);
  }
}
