import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../services/api.service';
import { StoreService } from '../services/store.service';
import { ToastService } from '../services/toast.service';
import { IconComponent } from '../ui/icon.component';

/** "New order" modal: an order is created pre-assigned to an AVAILABLE agent. */
@Component({
  selector: 'app-create-order-dialog',
  standalone: true,
  imports: [FormsModule, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open) {
      <div class="backdrop fade-in" (click)="close()"></div>
      <div class="dialog card fade-in" role="dialog" aria-modal="true" aria-labelledby="new-order-title" (keydown.escape)="close()">
        <header class="card-header">
          <div>
            <div class="card-title" id="new-order-title">New order</div>
            <div class="card-sub">Assigned straight to an agent who's taking orders</div>
          </div>
          <button class="btn btn-ghost btn-icon btn-sm" (click)="close()" aria-label="Close"><app-icon name="x" /></button>
        </header>
        <form class="card-body form" (ngSubmit)="submit()">
          <div class="field">
            <label for="desc">Description</label>
            <input id="desc" name="desc" class="input" [(ngModel)]="description" required autofocus
                   placeholder="e.g. Groceries - Koramangala to HSR Layout" />
          </div>
          <div class="field">
            <label for="agent">Agent</label>
            <select id="agent" name="agent" class="select" [(ngModel)]="agentId" required>
              @for (a of store.availableAgents(); track a.id) {
                <option [value]="a.id">{{ a.name }} · {{ a.activeOrderCount }} active orders</option>
              }
            </select>
            <span class="hint">Only Available agents are listed (Busy agents aren't taking new orders).</span>
          </div>
          <div class="row foot">
            <span class="spacer"></span>
            <button type="button" class="btn btn-secondary" (click)="close()">Cancel</button>
            <button type="submit" class="btn btn-primary" [disabled]="!description.trim() || !agentId || saving()">
              @if (saving()) { <app-icon name="loader" class="spin" /> } Create order
            </button>
          </div>
        </form>
      </div>
    }
  `,
  styles: [`
    .backdrop { position: fixed; inset: 0; background: rgba(15, 17, 23, 0.45); backdrop-filter: blur(2px); z-index: 50; }
    .dialog { position: fixed; z-index: 51; left: 50%; top: 18vh; transform: translateX(-50%); width: min(460px, calc(100vw - 32px)); box-shadow: var(--shadow-lg); }
    .form { display: flex; flex-direction: column; gap: 16px; }
    .foot { padding-top: 4px; }
  `],
})
export class CreateOrderDialogComponent {
  private readonly api = inject(ApiService);
  readonly store = inject(StoreService);
  private readonly toast = inject(ToastService);

  @Output() closed = new EventEmitter<void>();

  private isOpen = false;
  @Input() set open(value: boolean) {
    this.isOpen = value;
    if (value) {
      this.description = '';
      this.agentId = this.store.availableAgents()[0]?.id ?? '';
    }
  }
  get open(): boolean {
    return this.isOpen;
  }

  description = '';
  agentId = '';
  readonly saving = signal(false);

  close(): void {
    this.closed.emit();
  }

  submit(): void {
    if (!this.description.trim() || !this.agentId) {
      return;
    }
    this.saving.set(true);
    this.api.createOrder(this.description.trim(), this.agentId).subscribe({
      next: order => {
        this.saving.set(false);
        this.toast.success(`${order.id} created`, `Assigned to ${this.store.agentName(order.assignedAgentId)}.`);
        this.store.refresh();
        this.close();
      },
      error: err => {
        this.saving.set(false);
        this.toast.error('Couldn\'t create the order', errorMessage(err));
      },
    });
  }
}
