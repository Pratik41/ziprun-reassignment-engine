import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Agent, Order } from '../models';
import { AGENT_STATUS, ORDER_STATUS, timeAgo } from '../labels';
import { HeartbeatSimulatorService } from '../services/heartbeat-simulator.service';
import { StoreService } from '../services/store.service';
import { ToastService } from '../services/toast.service';
import { AgentStatusControlComponent } from '../ui/agent-status-control.component';
import { AvatarComponent } from '../ui/avatar.component';
import { IconComponent } from '../ui/icon.component';

/** Every agent with status control, load, and the orders they're carrying. */
@Component({
  selector: 'app-fleet-page',
  standalone: true,
  imports: [AgentStatusControlComponent, AvatarComponent, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Fleet</h1>
          <p class="page-sub">Who's on shift, what they're carrying, and their status. Changing a status re-plans
            affected orders in the background.</p>
        </div>
        <div class="row">
          @for (s of summary(); track s.label) {
            <span class="badge" [class]="'badge tone-' + s.tone"><span class="dot"></span>{{ s.count }} {{ s.label }}</span>
          }
        </div>
      </div>

      <div class="grid">
        @for (a of agents(); track a.id) {
          <article class="card agent">
            <header class="agent-head">
              <app-avatar [agentId]="a.id" [agentName]="a.name" [status]="a.status" size="lg" />
              <div class="who">
                <div class="name">{{ a.name }}</div>
                <div class="mono subtle">{{ a.id }}</div>
              </div>
              <span class="badge" [class]="'badge tone-' + statusLabels[a.status].tone">{{ statusLabels[a.status].label }}</span>
            </header>

            <div class="stats">
              <div><div class="stat-value">{{ a.activeOrderCount }}</div><div class="stat-label">active orders</div></div>
              <div><div class="stat-value">{{ store.pendingByAgent().get(a.id) ?? 0 }}</div><div class="stat-label">suggested to them</div></div>
              <div><div class="stat-value">{{ effective(a.id, a.activeOrderCount) }}</div><div class="stat-label">effective load</div></div>
            </div>

            <div class="orders">
              @for (o of ordersOf(a.id).slice(0, 4); track o.id) {
                <div class="order">
                  <span class="mono">{{ o.id }}</span>
                  <span class="desc">{{ o.description }}</span>
                  <span class="badge" [class]="'badge tone-' + orderLabels[o.status].tone">{{ orderLabels[o.status].label }}</span>
                </div>
              } @empty {
                <div class="subtle none">No active orders</div>
              }
              @if (ordersOf(a.id).length > 4) {
                <div class="subtle more">+{{ ordersOf(a.id).length - 4 }} more</div>
              }
            </div>

            @if (a.statusNote) {
              <div class="callout tone-warning note"><app-icon name="wifi-off" [size]="14" /><span class="grow">{{ a.statusNote }}</span></div>
            }

            <div class="app-row" [class.live]="sim.isConnected(a.id)">
              <app-icon [name]="sim.isConnected(a.id) ? 'activity' : 'wifi-off'" [size]="14" />
              <span class="grow">{{ appStatus(a) }}</span>
              <button class="btn btn-ghost btn-sm" (click)="toggleApp(a)"
                      [attr.title]="sim.isConnected(a.id) ? 'Stop sending heartbeats' : 'Simulate this agent\\'s phone app sending heartbeats'">
                {{ sim.isConnected(a.id) ? 'Disconnect app' : 'Connect app' }}
              </button>
            </div>

            <footer class="agent-foot">
              <app-agent-status-control [agent]="a" />
              @if (store.isLastAvailable(a)) {
                <span class="subtle hint" title="At least one agent must stay Available"><app-icon name="info" [size]="13" />Last available</span>
              }
            </footer>
          </article>
        }
      </div>
    </div>
  `,
  styles: [`
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(320px, 100%), 1fr)); gap: 16px; }
    .agent { display: flex; flex-direction: column; }
    .agent-head { display: flex; align-items: center; gap: 12px; padding: 16px 18px 12px; }
    .who { flex: 1; min-width: 0; }
    .name { font-weight: 600; font-size: 15px; }
    .stats { display: grid; grid-template-columns: repeat(3, 1fr); padding: 0 18px 12px; gap: 8px; }
    .stat-value { font-size: 18px; font-weight: 650; font-variant-numeric: tabular-nums; }
    .stat-label { font-size: 11.5px; color: var(--text-3); }
    .orders { flex: 1; border-top: 1px solid var(--border); padding: 8px 10px; display: flex; flex-direction: column; gap: 2px; }
    .order { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: var(--radius-sm); font-size: 13px; }
    .order:hover { background: var(--surface-hover); }
    .order .desc { flex: 1; min-width: 0; color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .none, .more { font-size: 12.5px; padding: 6px 8px; }
    .agent-foot { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-top: 1px solid var(--border); background: var(--surface-2); border-radius: 0 0 var(--radius-lg) var(--radius-lg); flex-wrap: wrap; }
    .hint { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; }
    .note { margin: 0 14px 10px; font-size: 12.5px; }
    .app-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px 6px 18px; border-top: 1px solid var(--border); font-size: 12.5px; color: var(--text-3); }
    .app-row.live { color: var(--success-text); }
    .app-row .grow { flex: 1; }
  `],
})
export class FleetPage {
  readonly store = inject(StoreService);
  readonly sim = inject(HeartbeatSimulatorService);
  private readonly toast = inject(ToastService);
  readonly statusLabels = AGENT_STATUS;
  readonly orderLabels = ORDER_STATUS;

  readonly agents = computed(() => {
    const rank = { AVAILABLE: 0, BUSY: 1, OFFLINE: 2 };
    return [...this.store.agents()].sort((a, b) => rank[a.status] - rank[b.status] || a.name.localeCompare(b.name));
  });

  readonly summary = computed(() => {
    const c = this.store.agentCounts();
    return [
      { label: 'available', count: c.AVAILABLE, tone: 'success' },
      { label: 'busy', count: c.BUSY, tone: 'warning' },
      { label: 'offline', count: c.OFFLINE, tone: 'danger' },
    ];
  });

  private readonly activeOrdersByAgent = computed(() => {
    const map = new Map<string, Order[]>();
    for (const o of this.store.orders()) {
      if (o.status === 'DELIVERED') continue;
      const list = map.get(o.assignedAgentId) ?? [];
      list.push(o);
      map.set(o.assignedAgentId, list);
    }
    return map;
  });

  ordersOf(agentId: string): Order[] {
    return this.activeOrdersByAgent().get(agentId) ?? [];
  }

  effective(agentId: string, active: number): number {
    return active + (this.store.pendingByAgent().get(agentId) ?? 0);
  }

  appStatus(a: Agent): string {
    const seen = a.lastHeartbeatAt ? `last heartbeat ${timeAgo(a.lastHeartbeatAt, this.store.lastUpdated() ?? new Date())}` : '';
    if (this.sim.isConnected(a.id)) {
      return `Phone app connected${seen ? ' · ' + seen : ''}`;
    }
    if (a.lastHeartbeatAt && a.status !== 'OFFLINE') {
      return `App silent · ${seen} (auto-offline after 60s)`;
    }
    return 'No phone app connected';
  }

  toggleApp(a: Agent): void {
    const first = a.name.split(' ')[0];
    if (this.sim.isConnected(a.id)) {
      this.sim.disconnect(a.id);
      this.toast.info(`${first}'s app disconnected`,
        `If no heartbeat arrives for 60 seconds, ${first} is marked Offline automatically and their orders are re-planned.`);
    } else {
      this.sim.connect(a.id);
      this.toast.info(`Simulating ${first}'s phone app`, 'Sending a heartbeat every 10 seconds from this browser tab.');
    }
  }
}
