import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './services/auth.service';
import { StoreService } from './services/store.service';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    // keep the store from opening a live stream or polling in tests
    const store = TestBed.inject(StoreService);
    spyOn(store, 'start');
    spyOn(store, 'stop');
  });

  it('shows only the routed page (sign-in) while nobody is signed in', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('nav')).toBeNull();
  });

  it('renders the navigation and sign-out once signed in, and starts loading data', () => {
    const auth = TestBed.inject(AuthService);
    auth.user.set('ops');
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const nav = el.querySelector('nav');
    expect(nav?.textContent).toContain('Queue');
    expect(nav?.textContent).toContain('Fleet');
    expect(nav?.textContent).toContain('Insights');
    expect(el.textContent).toContain('Sign out');
    expect(TestBed.inject(StoreService).start).toHaveBeenCalled();
  });
});
