import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Where the backend lives.
 *
 * On the web the app is served by the backend itself, so relative URLs ('/api/...',
 * '/ws') work as-is and the base is empty. Inside the Capacitor native shell the web
 * assets are served from the device (https://localhost), so every call must be
 * absolute against the real server.
 */
const NATIVE_SERVER = 'https://bridge-ai-fuev.onrender.com';

declare global {
  interface Window {
    Capacitor?: { isNativePlatform?: () => boolean };
  }
}

export function isNativeApp(): boolean {
  return window.Capacitor?.isNativePlatform?.() === true;
}

/** '' on the web; the absolute backend origin inside the native app. */
export function serverBase(): string {
  return isNativeApp() ? NATIVE_SERVER : '';
}

/** WebSocket endpoint, correct for both web (same origin) and the native app. */
export function wsUrl(): string {
  const base = serverBase();
  if (base) return base.replace(/^http/, 'ws') + '/ws';
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${location.host}/ws`;
}

/** Prefixes relative API calls with the server base when running natively. */
export const serverBaseInterceptor: HttpInterceptorFn = (request, next) => {
  const base = serverBase();
  if (base && request.url.startsWith('/')) {
    return next(request.clone({ url: base + request.url }));
  }
  return next(request);
};
