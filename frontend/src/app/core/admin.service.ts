import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AdminRoomView, AdminUserView } from './models';

/** Super-admin dashboard API — the server 404s everything for non-allowlisted users. */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  users(query?: string): Observable<AdminUserView[]> {
    return this.http.get<AdminUserView[]>('/api/v1/admin/users', {
      params: query?.trim() ? { query: query.trim() } : {},
    });
  }

  createUser(email: string, displayName: string, password: string): Observable<AdminUserView> {
    return this.http.post<AdminUserView>('/api/v1/admin/users', { email, displayName, password });
  }

  updateUser(userId: string, displayName: string | null, newPassword: string | null): Observable<AdminUserView> {
    return this.http.patch<AdminUserView>(`/api/v1/admin/users/${userId}`, {
      displayName: displayName || null,
      newPassword: newPassword || null,
    });
  }

  suspendUser(userId: string): Observable<AdminUserView> {
    return this.http.post<AdminUserView>(`/api/v1/admin/users/${userId}/suspend`, null);
  }

  reactivateUser(userId: string): Observable<AdminUserView> {
    return this.http.post<AdminUserView>(`/api/v1/admin/users/${userId}/reactivate`, null);
  }

  deleteUser(userId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/admin/users/${userId}`);
  }

  rooms(): Observable<AdminRoomView[]> {
    return this.http.get<AdminRoomView[]>('/api/v1/admin/rooms');
  }

  deleteRoom(roomId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/admin/rooms/${roomId}`);
  }
}
