import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from '../services/toast.service';
import { IconComponent } from './icon.component';

@Component({
  selector: 'app-toasts',
  standalone: true,
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" aria-live="polite">
      @for (t of toast.toasts(); track t.id) {
        <div class="toast fade-in" [class]="'toast fade-in ' + t.kind" role="status">
          <span class="toast-icon">
            <app-icon [name]="t.kind === 'success' ? 'check-circle' : t.kind === 'error' ? 'alert' : 'info'" [size]="18" />
          </span>
          <div class="toast-text">
            <div class="toast-title">{{ t.title }}</div>
            @if (t.message) {
              <div class="toast-msg">{{ t.message }}</div>
            }
          </div>
          <button class="btn btn-ghost btn-icon btn-sm" (click)="toast.dismiss(t.id)" aria-label="Dismiss">
            <app-icon name="x" [size]="14" />
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    .toasts { position: fixed; right: 20px; bottom: 20px; z-index: 100; display: flex; flex-direction: column; gap: 10px; width: min(380px, calc(100vw - 40px)); }
    .toast { display: flex; align-items: flex-start; gap: 10px; padding: 12px 10px 12px 14px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-lg); }
    .toast-icon { margin-top: 1px; }
    .success .toast-icon { color: var(--success); }
    .error .toast-icon { color: var(--danger); }
    .info .toast-icon { color: var(--primary); }
    .toast-text { flex: 1; min-width: 0; }
    .toast-title { font-weight: 600; font-size: 13.5px; }
    .toast-msg { margin-top: 2px; font-size: 13px; color: var(--text-2); }
  `],
})
export class ToastsComponent {
  readonly toast = inject(ToastService);
}
