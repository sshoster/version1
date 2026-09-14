import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthResponse, UserResponse } from './models';

const ACCESS_TOKEN_KEY = 'cg.accessToken';
const REFRESH_TOKEN_KEY = 'cg.refreshToken';
const USER_KEY = 'cg.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly userSignal = signal<UserResponse | null>(readStoredUser());
  readonly user = computed(() => this.userSignal());
  readonly isAuthenticated = computed(() => this.userSignal() !== null);

  register(email: string, displayName: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/v1/auth/register', { email, displayName, password })
      .pipe(tap((response) => this.store(response)));
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/v1/auth/login', { email, password })
      .pipe(tap((response) => this.store(response)));
  }

  /** Exchanges a Google Identity Services credential for our own session tokens. */
  loginWithGoogle(idToken: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/v1/auth/google', { idToken })
      .pipe(tap((response) => this.store(response)));
  }

  /** Public auth config (e.g. the Google client ID); blank client ID = button hidden. */
  authConfig(): Observable<{ googleClientId: string }> {
    return this.http.get<{ googleClientId: string }>('/api/v1/auth/config');
  }

  refresh(): Observable<AuthResponse> {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY) ?? '';
    return this.http
      .post<AuthResponse>('/api/v1/auth/refresh', { refreshToken })
      .pipe(tap((response) => this.store(response)));
  }

  /** Edits the account profile and refreshes the locally stored user. */
  updateProfile(displayName: string): Observable<UserResponse> {
    return this.http.patch<UserResponse>('/api/v1/users/me', { displayName }).pipe(
      tap((user) => {
        localStorage.setItem(USER_KEY, JSON.stringify(user));
        this.userSignal.set(user);
      }),
    );
  }

  logout(): void {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.userSignal.set(null);
  }

  accessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_KEY);
  }

  hasRefreshToken(): boolean {
    return localStorage.getItem(REFRESH_TOKEN_KEY) !== null;
  }

  private store(response: AuthResponse): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(response.user));
    this.userSignal.set(response.user);
  }
}

function readStoredUser(): UserResponse | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as UserResponse) : null;
  } catch {
    return null;
  }
}
