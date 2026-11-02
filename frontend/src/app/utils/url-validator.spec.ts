import { describe, expect, it } from 'vitest';
import { isValidUrl } from './url-validator';

describe('isValidUrl', () => {
  it('should accept valid HTTP and HTTPS URLs with host', () => {
    expect(isValidUrl('https://example.com/a')).toBe(true);
    expect(isValidUrl('http://example.com')).toBe(true);
    expect(isValidUrl('https://sub.domain.co.uk/path?q=1#hash')).toBe(true);
  });

  it('should reject invalid or non-HTTP/HTTPS URLs', () => {
    expect(isValidUrl('http://')).toBe(false);
    expect(isValidUrl('ftp://x')).toBe(false);
    expect(isValidUrl('not a url')).toBe(false);
    expect(isValidUrl('')).toBe(false);
    expect(isValidUrl('   ')).toBe(false);
    expect(isValidUrl('javascript:alert(1)')).toBe(false);
  });
});
