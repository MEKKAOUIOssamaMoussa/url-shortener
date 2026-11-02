export interface ShortUrlRequest {
  url: string;
}

export interface ShortUrlResponse {
  code: string;
  shortUrl: string;
  targetUrl: string;
  createdAt: string;
}

export interface DailyClicks {
  day: string;
  count: number;
}

export interface ShortUrlStatsResponse {
  code: string;
  totalClicks: number;
  daily: DailyClicks[];
}

export interface FieldError {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: FieldError[];
}

export interface UiError {
  message: string;
  details?: string[];
  retryAfterSeconds?: number;
}
