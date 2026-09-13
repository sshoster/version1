import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Attaches the Bearer token; on a 401 tries a single refresh-and-retry before signing out. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isAuthEndpoint = request.url.includes('/api/v1/auth/');
  const authorized = withToken(request, auth.accessToken());

  return next(authorized).pipe(
    catchError((error: unknown) => {
      const canRetry =
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !isAuthEndpoint &&
        auth.hasRefreshToken();
      if (!canRetry) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        switchMap((tokens) => next(withToken(request, tokens.accessToken))),
        catchError((refreshError: unknown) => {
          auth.logout();
          void router.navigate(['/welcome']);
          return throwError(() => refreshError);
        }),
      );
    }),
  );
};

function withToken<T>(request: HttpRequest<T>, token: string | null): HttpRequest<T> {
  if (!token) return request;
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}
