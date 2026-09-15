import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { serverBaseInterceptor } from './core/server-base';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    // serverBaseInterceptor first: it must rewrite the URL before anything else runs.
    provideHttpClient(withInterceptors([serverBaseInterceptor, authInterceptor])),
  ],
};
