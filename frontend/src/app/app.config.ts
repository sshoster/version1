import { ApplicationConfig, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { serverBaseInterceptor } from './core/server-base';
import { sessionStore } from './core/session-store';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Native app: load the session from encrypted OS storage before anything reads it.
    provideAppInitializer(() => sessionStore.hydrate(['cg.accessToken', 'cg.refreshToken', 'cg.user'])),
    provideRouter(routes, withComponentInputBinding()),
    // serverBaseInterceptor first: it must rewrite the URL before anything else runs.
    provideHttpClient(withInterceptors([serverBaseInterceptor, authInterceptor])),
  ],
};
