import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Order, OrderStatus } from '../models';
import { AGENT_STATUS, ORDER_STATUS, timeAgo } from '../labels';
import { ApiService, errorMessage } from '../services/api.service';
import { StoreService } from '../services/store.service';
import { ToastService } from '../services/toast.service';
import { AvatarComponent } from '../ui/avatar.component';
import { IconComponent } from '../ui/icon.component';

type Filter = 'ALL' | OrderStatus;

/** Every order across all statuses, with search and a "mark delivered" action. */
@Component({
  selector: 'app-orders-page',
  standalone: true,
  imports: [FormsModule, RouterLink, AvatarComponent, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Orders</h1>
          <p class="page-sub">Every order and where it stands. Orders that need a new agent are handled in the queue.</p>
        </div>
      </div>

      <section class="card">
        <div class="toolbar">
          <div class="tabs">
            @for (f of filters; track f) {
              <button [class.on]="filter() === f" (click)="filter.set(f)">
                {{ f === 'ALL' ? 'All' : orderLabels[f].label }}
                <span class="count">{{ countFor(f) }}</span>
              </button>
            }
          </div>
          <div class="input-icon search">
            <app-icon name="search" [size]="15" />
            <input class="input" placeholder="Search orders or agents" [ngModel]="query()" (ngModelChange)="query.set($event)" />
          </div>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th>Order</th><th>Agent</th><th>Status</th><th>Created</th><th class="right"></th></tr>
            </thead>
            <tbody>
              @for (o of rows(); track o.id) {
                <tr>
                  <td>
                    <div class="mono strong">{{ o.id }}</div>
                    <div class="muted">{{ o.description }}</div>
                  </td>
                  <td>
                    @if (store.agentById().get(o.assignedAgentId); as a) {
                      <div class="row">
                        <app-avatar [agentId]="a.id" [agentName]="a.name" [status]="a.status" size="sm" />
                        <div>
                          <div>{{ a.name }}</div>
                          <div class="subtle small">{{ agentLabels[a.status].label }}</div>
                        </div>
                      </div>
                    } @else {
                      <span class="mono subtle">{{ o.assignedAgentId }}</span>
                    }
                  </td>
                  <td><span class="badge" [class]="'badge tone-' + orderLabels[o.status].tone"><span class="dot"></span>{{ orderLabels[o.status].label }}</span></td>
                  <td class="muted nowrap" [attr.title]="o.createdAt">{{ ago(o.createdAt) }}</td>
                  <td class="right">
                    @if (o.status === 'REASSIGNMENT_PENDING') {
                      <a routerLink="/queue" class="btn btn-secondary btn-sm">Review<app-icon name="arrow-right" [size]="14" /></a>
                    } @else if (o.status === 'ASSIGNED' || o.status === 'REASSIGNED') {
                      <button class="btn btn-ghost btn-sm" [disabled]="busy() === o.id" (click)="markDelivered(o)">
                        <app-icon name="check-circle" [size]="14" />Mark delivered
                      </button>
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="5">
                  <div class="empty">
                    <span class="empty-icon"><app-icon name="package" [size]="22" /></span>
                    <h3>{{ store.loaded() ? 'No orders here' : 'Loading orders…' }}</h3>
                    @if (store.loaded()) { <p>Try another filter, or create an order with <strong>New order</strong>.</p> }
                  </div>
                </td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
  styles: [`
    .toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; flex-wrap: wrap; border-bottom: 1px solid var(--border); padding-right: 14px; }
    .toolbar .tabs { border-bottom: 0; }
    .search { width: 260px; margin: 8px 0; }
    .search .input { height: 32px; }
    .toolbar .tabs { min-width: 0; max-width: 100%; overflow-x: auto; scrollbar-width: none; }
    @media (max-width: 640px) { .toolbar { padding: 0 12px; } .search { width: 100%; } }
    .strong { font-weight: 600; }
    .small { font-size: 12px; }
    .right { text-align: right; }
  `],
})
export class OrdersPage {
  readonly store = inject(StoreService);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly orderLabels = ORDER_STATUS;
  readonly agentLabels = AGENT_STATUS;
  readonly filters: Filter[] = ['ALL', 'REASSIGNMENT_PENDING', 'ASSIGNED', 'REASSIGNED', 'DELIVERED'];
  readonly filter = signal<Filter>('ALL');
  readonly query = signal('');
  readonly busy = signal<string | null>(null);

  readonly rows = computed(() => {
    const q = this.query().trim().toLowerCase();
    const f = this.filter();
    return this.store.orders()
      .filter(o => f === 'ALL' || o.status === f)
      .filter(o => !q
        || o.id.toLowerCase().includes(q)
        || o.description.toLowerCase().includes(q)
        || this.store.agentName(o.assignedAgentId).toLowerCase().includes(q))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  });

  countFor(f: Filter): number {
    return f === 'ALL' ? this.store.orders().length : this.store.orders().filter(o => o.status === f).length;
  }

  ago(iso: string): string {
    return timeAgo(iso, this.store.lastUpdated() ?? new Date());
  }

  markDelivered(o: Order): void {
    this.busy.set(o.id);
    this.api.updateOrderStatus(o.id, 'DELIVERED').subscribe({
      next: () => {
        this.busy.set(null);
        this.toast.success(`${o.id} delivered`, `${this.store.agentName(o.assignedAgentId)} has one fewer order.`);
        this.store.refresh();
      },
      error: err => {
        this.busy.set(null);
        this.toast.error('Couldn\'t mark as delivered', errorMessage(err));
      },
    });
  }
}
