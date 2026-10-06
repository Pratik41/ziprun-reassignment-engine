import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApiService, errorMessage } from './services/api.service';
import { AuthService } from './services/auth.service';
import { StoreService } from './services/store.service';
import { ThemeService } from './services/theme.service';
import { ToastService } from './services/toast.service';
import { CreateOrderDialogComponent } from './components/create-order-dialog.component';
import { IconComponent } from './ui/icon.component';
import { ToastsComponent } from './ui/toasts.component';

const STRATEGY_INFO: Record<string, { label: string; icon: string; hint: string }> = {
  ai: { label: 'AI', icon: 'sparkles', hint: 'Gemini (Groq as backup) recommends an agent; answers are checked against the roster' },
  'rule-based': { label: 'Rule-based', icon: 'scale', hint: 'Lowest effective load wins: instant and deterministic' },
};

/** App shell: sidebar navigation, top bar (routing strategy, new order), and global overlays. */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent, ToastsComponent, CreateOrderDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  readonly store = inject(StoreService);
  readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly newOrderOpen = signal(false);
  readonly switching = signal(false);

  readonly strategies = computed(() => this.store.strategy()?.available ?? ['ai', 'rule-based']);

  /** Data flows only while someone is signed in. */
  private readonly session = effect(() => {
    const signedIn = !!this.auth.user();
    untracked(() => (signedIn ? this.store.start() : this.store.stop()));
  });

  info(name: string) {
    return STRATEGY_INFO[name] ?? { label: name, icon: 'activity', hint: name };
  }

  setStrategy(name: string): void {
    if (name === this.store.strategy()?.active) {
      return;
    }
    this.switching.set(true);
    this.api.setRoutingStrategy(name).subscribe({
      next: s => {
        this.switching.set(false);
        this.store.strategy.set(s);
        this.toast.success(`Routing switched to ${this.info(s.active).label}`, 'Applies to the next suggestion; existing ones keep their source.');
      },
      error: err => {
        this.switching.set(false);
        this.toast.error('Couldn\'t switch routing strategy', errorMessage(err));
      },
    });
  }
}
