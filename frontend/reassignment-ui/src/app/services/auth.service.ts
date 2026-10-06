import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, firstValueFrom, map, of, tap, throwError } from 'rxjs';

interface Me {
  username: string;
  loginRequired: boolean;
}

/**
 * Who is signed in. The backend keeps a session cookie; this service only mirrors
 * it: `user` is undefined until the first check, then the username or null.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly user = signal<string | null | undefined>(undefined);
  /** False when the backend runs with security disabled (no sign-in needed, no sign-out shown). */
  readonly loginRequired = signal(true);

  /** True when the last check couldn't reach the backend at all (not the same as "signed out"). */
  readonly unreachable = signal(false);

  /**
   * Asks the backend once; later calls reuse the answer. Only a 401 means "signed out".
   * If the backend can't be reached (down, restarting, network), nothing is remembered,
   * so the next navigation asks again instead of treating the user as logged out.
   */
  check(): Promise<boolean> {
    if (this.user() !== undefined) {
      return Promise.resolve(this.user() !== null);
    }
    return firstValueFrom(this.http.get<Me>('/api/auth/me').pipe(
      tap(me => {
        this.unreachable.set(false);
        this.signedIn(me);
      }),
      map(() => true),
      catchError((err: unknown) => {
        const signedOut = err instanceof HttpErrorResponse && err.status === 401;
        this.unreachable.set(!signedOut);
        if (signedOut) {
          this.user.set(null);
        }
        return of(false);
      }),
    ));
  }

  login(username: string, password: string): Observable<void> {
    return this.http.post<Me>('/api/auth/login', { username, password }).pipe(
      tap(me => this.signedIn({ ...me, loginRequired: true })),
      map(() => undefined),
      catchError((err: HttpErrorResponse) => throwError(() => new Error(
        err.status === 0 ? 'Cannot reach the backend' : err.error?.message ?? 'Sign-in failed'))),
    );
  }

  logout(): void {
    this.http.post('/api/auth/logout', {}).subscribe({ complete: () => this.signedOut(), error: () => this.signedOut() });
  }

  /** The session ended elsewhere (expired, backend restarted): back to the sign-in page. */
  signedOut(): void {
    this.user.set(null);
    this.router.navigate(['/login'], { queryParams: { next: this.router.url.startsWith('/login') ? null : this.router.url } });
  }

  private signedIn(me: Me): void {
    this.loginRequired.set(me.loginRequired);
    this.user.set(me.username);
  }
}

/** Pages behind sign-in. */
export const authGuard: CanActivateFn = async (_route, state) => {
  // inject() only works before the first await
  const auth = inject(AuthService);
  const router = inject(Router);
  if (await auth.check()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { next: state.url } });
};

/** Any 401 from the API (other than the sign-in calls themselves) means the session is gone. */
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  return next(req).pipe(catchError((err: unknown) => {
    if (err instanceof HttpErrorResponse && err.status === 401 && !req.url.includes('/auth/') && auth.user()) {
      auth.signedOut();
    }
    return throwError(() => err);
  }));
};
