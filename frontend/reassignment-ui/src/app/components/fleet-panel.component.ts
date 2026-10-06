import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { StoreService } from '../services/store.service';
import { AgentStatusControlComponent } from '../ui/agent-status-control.component';
import { AvatarComponent } from '../ui/avatar.component';
import { IconComponent } from '../ui/icon.component';

/** Compact fleet list beside the queue: who's available, their load, and quick status changes. */
@Component({
  selector: 'app-fleet-panel',
  standalone: true,
  imports: [RouterLink, AgentStatusControlComponent, AvatarComponent, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card">
      <header class="card-header">
        <div>
          <div class="card-title">Fleet</div>
          <div class="card-sub">{{ store.agentCounts().AVAILABLE }} of {{ store.agents().length }} taking orders</div>
        </div>
        <a routerLink="/fleet" class="btn btn-ghost btn-sm">View all <app-icon name="arrow-right" [size]="14" /></a>
      </header>

      <ul class="agents">
        @for (a of agents(); track a.id) {
          <li class="agent">
            <div class="agent-top">
              <app-avatar [agentId]="a.id" [agentName]="a.name" [status]="a.status" size="sm" />
              <div class="who">
                <div class="name">
                  {{ a.name }}
                  @if (a.statusNote) {
                    <span class="note-icon" [attr.title]="a.statusNote"><app-icon name="wifi-off" [size]="12" /></span>
                  }
                </div>
              </div>
              <div class="load" [attr.title]="loadTitle(a.id, a.activeOrderCount)">
                <div class="meter neutral"><span [style.width.%]="loadPct(a.activeOrderCount)"></span></div>
                <span class="subtle nowrap">{{ a.activeOrderCount }}@if (pending(a.id)) {<span class="pend"> +{{ pending(a.id) }}</span>}</span>
              </div>
            </div>
            <app-agent-status-control [agent]="a" [compact]="true" [stretch]="true" class="ctl" />
          </li>
        } @empty {
          @for (i of [1, 2, 3, 4]; track i) {
            <li class="agent"><div class="skeleton" style="height: 26px; width: 100%"></div></li>
          }
        }
      </ul>
      <footer class="legend subtle">
        Bar = active orders · <span class="pend">+n</span> = suggestions queued for them
      </footer>
    </section>
  `,
  styles: [`
    .agents { list-style: none; margin: 0; padding: 6px 8px; }
    /* Two lines per agent: who + load on top, full-width status buttons below (never clipped) */
    .agent { display: flex; flex-direction: column; gap: 8px; padding: 10px 8px; border-radius: var(--radius); }
    .agent + .agent { border-top: 1px solid var(--border); border-radius: 0; }
    .agent-top { display: flex; align-items: center; gap: 10px; min-width: 0; }
    .ctl { padding-left: 36px; }
    .who { flex: 1; min-width: 0; }
    .name { font-weight: 500; font-size: 13.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .load { display: flex; align-items: center; gap: 8px; font-size: 12px; flex: none; width: 110px; }
    .load .meter { flex: 1; height: 4px; }
    .pend { color: var(--primary-text); font-weight: 600; }
    .note-icon { display: inline-flex; vertical-align: -1px; margin-left: 4px; color: var(--warning-text); cursor: help; }
    .legend { font-size: 11.5px; padding: 10px 16px; border-top: 1px solid var(--border); }
  `],
})
export class FleetPanelComponent {
  readonly store = inject(StoreService);

  /** Available first, then busy, then offline; alphabetical within each. */
  readonly agents = computed(() => {
    const rank = { AVAILABLE: 0, BUSY: 1, OFFLINE: 2 };
    return [...this.store.agents()].sort((a, b) => rank[a.status] - rank[b.status] || a.name.localeCompare(b.name));
  });

  private readonly maxLoad = computed(() => Math.max(6, ...this.store.agents().map(a => a.activeOrderCount)));

  loadPct(active: number): number {
    return Math.min(100, (active / this.maxLoad()) * 100);
  }

  pending(agentId: string): number {
    return this.store.pendingByAgent().get(agentId) ?? 0;
  }

  loadTitle(agentId: string, active: number): string {
    const p = this.pending(agentId);
    return `${active} active order${active === 1 ? '' : 's'}` + (p ? `, ${p} suggestion${p === 1 ? '' : 's'} queued` : '');
  }
}
