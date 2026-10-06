import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService, unauthorizedInterceptor } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([unauthorizedInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  afterEach(() => http.verify());

  it('asks the backend once who is signed in', async () => {
    const first = auth.check();
    http.expectOne('/api/auth/me').flush({ username: 'ops', loginRequired: true });
    expect(await first).toBeTrue();
    expect(auth.user()).toBe('ops');

    expect(await auth.check()).toBeTrue(); // cached: no second request
  });

  it('treats a 401 from /auth/me as signed out', async () => {
    const result = auth.check();
    http.expectOne('/api/auth/me').flush({ message: 'Not signed in' }, { status: 401, statusText: 'Unauthorized' });
    expect(await result).toBeFalse();
    expect(auth.user()).toBeNull();
  });

  it('knows when the backend needs no sign-in', async () => {
    const result = auth.check();
    http.expectOne('/api/auth/me').flush({ username: 'ops', loginRequired: false });
    await result;
    expect(auth.loginRequired()).toBeFalse();
  });

  it('turns a failed sign-in into the server\'s message', done => {
    auth.login('ops', 'nope').subscribe({
      error: (e: Error) => {
        expect(e.message).toBe('Wrong username or password');
        expect(auth.user()).toBeUndefined();
        done();
      },
    });
    const req = http.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({ username: 'ops', password: 'nope' });
    req.flush({ message: 'Wrong username or password' }, { status: 401, statusText: 'Unauthorized' });
  });

  it('sends the user to sign-in when an API call comes back 401 (session expired)', () => {
    auth.user.set('ops');
    TestBed.inject(HttpClient).get('/api/agents').subscribe({ error: () => undefined });
    http.expectOne('/api/agents').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.user()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], jasmine.anything());
  });
});
