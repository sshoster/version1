import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone, inject } from '@angular/core';
import { Router } from '@angular/router';
import { PushNotifications } from '@capacitor/push-notifications';
import { isNativeApp } from './server-base';

/**
 * Mobile push (native app only): after sign-in, registers this device's FCM token with the
 * backend so users hear about activity while the app is closed. Tapping a notification opens
 * the room it points at. The OS re-issues tokens over time — the 'registration' listener
 * re-uploads whenever that happens.
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly zone = inject(NgZone);

  private enabled = false;

  async enable(): Promise<void> {
    if (!isNativeApp() || this.enabled) return;
    this.enabled = true;

    const permission = await PushNotifications.requestPermissions();
    if (permission.receive !== 'granted') return;

    await PushNotifications.addListener('registration', ({ value }) => {
      this.zone.run(() => {
        this.http.put('/api/v1/notifications/devices', { token: value, platform: 'ANDROID' }).subscribe({
          error: () => undefined,
        });
      });
    });

    await PushNotifications.addListener('pushNotificationActionPerformed', (action) => {
      const roomId = (action.notification.data as { roomId?: string } | undefined)?.roomId;
      if (roomId) this.zone.run(() => void this.router.navigateByUrl(`/rooms/${roomId}`));
    });

    await PushNotifications.register();
  }
}
