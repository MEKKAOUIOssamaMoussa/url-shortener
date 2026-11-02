export function isValidUrl(input: string): boolean {
  if (!input || typeof input !== 'string') {
    return false;
  }
  try {
    const parsed = new URL(input.trim());
    const isHttp = parsed.protocol === 'http:' || parsed.protocol === 'https:';
    const hasHost = Boolean(parsed.hostname && parsed.hostname.length > 0);
    return isHttp && hasHost;
  } catch {
    return false;
  }
}
