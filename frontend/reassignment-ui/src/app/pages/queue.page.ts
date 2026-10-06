import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { StoreService } from '../services/store.service';
import { QueueItemComponent } from '../components/queue-item.component';
import { FleetPanelComponent } from '../components/fleet-panel.component';
import { IconComponent } from '../ui/icon.component';
import { percent } from '../labels';

interface Kpi {
  label: string;
  value: string;
  detail: string;
  icon: string;
  tone: string;
}

/** Home view: what needs attention right now, and who can take it. */
@Component({
  selector: 'app-queue-page',
  standalone: true,
  imports: [QueueItemComponent, FleetPanelComponent, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Reassignment queue</h1>
          <p class="page-sub">
            Orders whose agent can't deliver them, or that are about to miss their deadline. Suggestions appear here
            automatically. Nothing moves until you accept.
          </p>
        </div>
        <button class="btn btn-secondary btn-sm" (click)="store.refresh()">
          <app-icon name="refresh" [size]="14" />Refresh
        </button>
      </div>

      <div class="kpis">
        @for (k of kpis(); track k.label) {
          <div class="kpi card">
            <div class="kpi-icon" [class]="'kpi-icon tone-' + k.tone"><app-icon [name]="k.icon" [size]="16" /></div>
            <div class="kpi-text">
              <div class="kpi-label">{{ k.label }}</div>
              <div class="kpi-value">
                @if (store.loaded()) { {{ k.value }} } @else { <span class="skeleton" style="display:inline-block;width:48px;height:22px"></span> }
              </div>
              <div class="kpi-detail subtle">{{ k.detail }}</div>
            </div>
          </div>
        }
      </div>

      <div class="layout">
        <section class="queue">
          @if (!store.loaded()) {
            @for (i of [1, 2]; track i) {
              <div class="card skeleton-card"><div class="skeleton" style="height: 18px; width: 40%"></div>
                <div class="skeleton" style="height: 14px; width: 70%; margin-top: 10px"></div>
                <div class="skeleton" style="height: 56px; margin-top: 16px"></div></div>
            }
          } @else {
            @if (store.atRiskOrders().length) {
              <div class="section-label"><app-icon name="clock" [size]="14" />Deadline at risk · {{ store.atRiskOrders().length }}</div>
              @for (o of store.atRiskOrders(); track o.id) {
                <app-queue-item [order]="o" />
              }
              @if (store.waitingOrders().length) {
                <div class="section-label"><app-icon name="package" [size]="14" />Needs a new agent · {{ store.waitingOrders().length }}</div>
              }
            }
            @for (o of store.waitingOrders(); track o.id) {
              <app-queue-item [order]="o" />
            } @empty {
              @if (!store.atRiskOrders().length) {
                <div class="card">
                  <div class="empty">
                    <span class="empty-icon"><app-icon name="inbox" [size]="22" /></span>
                    <h3>All clear</h3>
                    <p>No orders need a new agent. Set an agent to <strong>Offline</strong> in the fleet panel to watch the
                      re-planning loop queue suggestions here.</p>
                  </div>
                </div>
              }
            }
          }
        </section>

        <aside class="side">
          <app-fleet-panel />
          <section class="card how">
            <div class="card-body">
              <div class="how-title"><app-icon name="info" [size]="14" />How suggestions work</div>
              <ul>
                <li><strong>Offline</strong> agent: their orders land here with a suggested replacement.</li>
                <li><strong>Busy</strong> agent: keeps their orders, stops being suggested.</li>
                <li><strong>Available</strong> again: waiting orders are re-balanced across the fleet.</li>
                <li><strong>Close to its deadline</strong>: if another agent could start it sooner, it's suggested here.</li>
                <li>Agents at their <strong>capacity</strong> aren't suggested; nearby agents are preferred.</li>
              </ul>
            </div>
          </section>
        </aside>
      </div>
    </div>
  `,
  styles: [`
    .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
    .kpi { display: flex; gap: 12px; padding: 14px 16px; align-items: flex-start; }
    .kpi-icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 8px; flex: none; }
    .kpi-label { font-size: 12.5px; color: var(--text-2); font-weight: 500; }
    .kpi-value { font-size: 24px; font-weight: 650; letter-spacing: -0.02em; line-height: 1.25; font-variant-numeric: tabular-nums; }
    .kpi-detail { font-size: 12px; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 384px; gap: 20px; align-items: start; }
    .queue { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
    .side { display: flex; flex-direction: column; gap: 14px; position: sticky; top: 20px; }
    .skeleton-card { padding: 18px; }
    .section-label { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em; color: var(--text-2); }
    .how-title { display: flex; align-items: center; gap: 6px; font-weight: 600; font-size: 13px; margin-bottom: 8px; }
    .how ul { margin: 0; padding-left: 18px; color: var(--text-2); font-size: 12.5px; display: flex; flex-direction: column; gap: 4px; }
    @media (max-width: 1180px) { .layout { grid-template-columns: minmax(0, 1fr); } .side { position: static; } }
    @media (max-width: 900px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    @media (max-width: 480px) { .kpis { gap: 10px; } .kpi { padding: 12px; gap: 0; } .kpi-icon { display: none; } .kpi-value { font-size: 20px; } }
  `],
})
export class QueuePage {
  readonly store = inject(StoreService);

  readonly kpis = computed<Kpi[]>(() => {
    const waiting = this.store.waitingOrders();
    const withSuggestion = waiting.filter(o => this.store.openSuggestionsByOrder().has(o.id)).length;
    const counts = this.store.agentCounts();
    const pending = this.store.pendingSuggestions();
    const auto = pending.filter(s => s.triggerReason !== 'INITIAL').length;
    const atRisk = this.store.atRiskOrders().length;
    const ai = pending.filter(s => s.source?.startsWith('ai:')).length;
    const fallbacks = pending.filter(s => s.source?.includes('fallback')).length;
    const avgConfidence = pending.length ? pending.reduce((sum, s) => sum + s.confidence, 0) / pending.length : 0;

    return [
      {
        label: 'Waiting orders', value: `${waiting.length}`, icon: 'package', tone: waiting.length ? 'warning' : 'success',
        detail: (waiting.length ? `${withSuggestion} with a suggestion` : 'Nothing stranded')
          + (atRisk ? ` · ${atRisk} deadline${atRisk === 1 ? '' : 's'} at risk` : ''),
      },
      {
        label: 'Available agents', value: `${counts.AVAILABLE} / ${this.store.agents().length}`, icon: 'users', tone: 'success',
        detail: `${counts.BUSY} busy · ${counts.OFFLINE} offline`,
      },
      {
        label: 'Open suggestions', value: `${pending.length}`, icon: 'zap', tone: 'primary',
        detail: `${auto} auto re-plan${auto === 1 ? '' : 's'}`,
      },
      {
        label: 'Avg confidence', value: pending.length ? percent(avgConfidence) : '-', icon: 'gauge', tone: 'violet',
        detail: pending.length ? `${ai} from AI · ${fallbacks} fallback${fallbacks === 1 ? '' : 's'}` : 'No open suggestions',
      },
    ];
  });
}
