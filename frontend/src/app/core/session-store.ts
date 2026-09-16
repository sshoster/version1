import { SecureStoragePlugin } from 'capacitor-secure-storage-plugin';
import { isNativeApp } from './server-base';

/**
 * Where the session (tokens + user) is persisted.
 *
 * Web: localStorage, as before. Native app: an in-memory map mirrored into the OS's
 * encrypted storage (Android EncryptedSharedPreferences / iOS Keychain), so the 30-day
 * refresh token never sits in plain WebView storage. Reads stay synchronous for the
 * interceptor; `hydrate()` fills the memory map once at startup (app initializer),
 * migrating any tokens a previous app version left in localStorage.
 */
class SessionStore {
  private readonly cache = new Map<string, string>();
  private readonly native = isNativeApp();

  get(key: string): string | null {
    if (!this.native) return localStorage.getItem(key);
    return this.cache.get(key) ?? null;
  }

  set(key: string, value: string): void {
    if (!this.native) {
      localStorage.setItem(key, value);
      return;
    }
    this.cache.set(key, value);
    void SecureStoragePlugin.set({ key, value }).catch(() => undefined);
  }

  remove(key: string): void {
    if (!this.native) {
      localStorage.removeItem(key);
      return;
    }
    this.cache.delete(key);
    void SecureStoragePlugin.remove({ key }).catch(() => undefined);
  }

  /** Native startup: load persisted session into memory (and migrate old localStorage values). */
  async hydrate(keys: string[]): Promise<void> {
    if (!this.native) return;
    for (const key of keys) {
      try {
        const { value } = await SecureStoragePlugin.get({ key });
        if (value) this.cache.set(key, value);
      } catch {
        // Not in secure storage — migrate from localStorage if an older version stored it there.
        const legacy = localStorage.getItem(key);
        if (legacy !== null) {
          this.cache.set(key, legacy);
          void SecureStoragePlugin.set({ key, value: legacy }).catch(() => undefined);
          localStorage.removeItem(key);
        }
      }
    }
  }
}

export const sessionStore = new SessionStore();
