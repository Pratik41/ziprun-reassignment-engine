import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  kind: 'success' | 'error' | 'info';
  title: string;
  message?: string;
}

/** Small notification stack (replaces alert() and inline error banners). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 1;
  readonly toasts = signal<Toast[]>([]);

  success(title: string, message?: string): void {
    this.push({ kind: 'success', title, message });
  }

  error(title: string, message?: string): void {
    this.push({ kind: 'error', title, message }, 7000);
  }

  info(title: string, message?: string): void {
    this.push({ kind: 'info', title, message });
  }

  dismiss(id: number): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }

  private push(toast: Omit<Toast, 'id'>, ttlMs = 4500): void {
    const id = this.nextId++;
    this.toasts.update(list => [...list.slice(-3), { ...toast, id }]);
    setTimeout(() => this.dismiss(id), ttlMs);
  }
}
