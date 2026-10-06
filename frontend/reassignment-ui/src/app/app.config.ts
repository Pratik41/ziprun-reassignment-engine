import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { unauthorizedInterceptor } from './services/auth.service';

// provideHttpClient sends the XSRF-TOKEN cookie back as X-XSRF-TOKEN on writes (Angular's default)
export const appConfig: ApplicationConfig = {
  providers: [provideRouter(routes), provideHttpClient(withInterceptors([unauthorizedInterceptor]))]
};
