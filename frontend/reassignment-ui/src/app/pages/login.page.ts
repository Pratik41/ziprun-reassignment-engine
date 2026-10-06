import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { ThemeService } from '../services/theme.service';
import { IconComponent } from '../ui/icon.component';

/** Sign-in for the ops console. */
@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [FormsModule, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="wrap">
      <form class="card panel fade-in" (ngSubmit)="submit()">
        <div class="brand">
          <span class="brand-mark"><app-icon name="truck" [size]="20" /></span>
          <div>
            <div class="brand-name">ZipRun</div>
            <div class="subtle">Dispatch ops</div>
          </div>
        </div>

        <h1>Sign in</h1>

        <div class="field">
          <label for="user">Username</label>
          <input id="user" name="user" class="input" autocomplete="username" required autofocus
                 [ngModel]="username()" (ngModelChange)="username.set($event)" />
        </div>
        <div class="field">
          <label for="pass">Password</label>
          <input id="pass" name="pass" class="input" type="password" autocomplete="current-password" required
                 [ngModel]="password()" (ngModelChange)="password.set($event)" />
        </div>

        @if (error()) {
          <div class="callout tone-danger" role="alert"><app-icon name="alert" [size]="14" /><span>{{ error() }}</span></div>
        }

        <button type="submit" class="btn btn-primary submit" [disabled]="busy() || !username() || !password()">
          @if (busy()) { <app-icon name="loader" class="spin" /> } @else { <app-icon name="lock" /> }
          Sign in
        </button>
        <p class="subtle hint">Credentials are set on the backend (OPS_USERNAME / OPS_PASSWORD).</p>
      </form>
      <button class="btn btn-ghost btn-sm theme" (click)="theme.toggle()">
        <app-icon [name]="theme.isDark() ? 'sun' : 'moon'" [size]="14" />{{ theme.isDark() ? 'Light' : 'Dark' }}
      </button>
    </main>
  `,
  styles: [`
    .wrap { min-height: 100vh; display: grid; place-items: center; padding: 24px 16px; position: relative;
      background: radial-gradient(1200px 600px at 20% -10%, color-mix(in srgb, var(--primary) 14%, transparent), transparent 60%), var(--bg); }
    .panel { width: min(380px, 100%); padding: 28px; display: flex; flex-direction: column; gap: 16px; box-shadow: var(--shadow-lg); }
    .brand { display: flex; align-items: center; gap: 12px; }
    .brand-mark { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 10px;
      background: linear-gradient(135deg, #6366f1, #8b5cf6); color: #fff; box-shadow: 0 4px 14px rgba(99, 102, 241, 0.35); }
    .brand-name { font-weight: 650; font-size: 16px; }
    h1 { margin: 4px 0 0; font-size: 20px; letter-spacing: -0.01em; }
    .submit { justify-content: center; height: 40px; }
    .hint { font-size: 12px; margin: 0; text-align: center; }
    .theme { position: absolute; top: 16px; right: 16px; }
  `],
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly theme = inject(ThemeService);

  readonly username = signal('');
  readonly password = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(this.username().trim(), this.password()).subscribe({
      next: () => {
        this.busy.set(false);
        const next = this.route.snapshot.queryParamMap.get('next');
        this.router.navigateByUrl(next && next.startsWith('/') && !next.startsWith('/login') ? next : '/queue');
      },
      error: (e: Error) => {
        this.busy.set(false);
        this.password.set('');
        this.error.set(e.message);
      },
    });
  }
}
