import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { Activity, MetricsSummary, SourceStats } from '../models';
import { percent, timeAgo } from '../labels';
import { ApiService } from '../services/api.service';
import { IconComponent } from '../ui/icon.component';

const ACTIVITY_STYLE: Record<string, { icon: string; tone: string }> = {
  AGENT_STATUS: { icon: 'users', tone: 'neutral' },
  AGENT_AUTO_OFFLINE: { icon: 'wifi-off', tone: 'danger' },
  ORDER_CREATED: { icon: 'plus', tone: 'info' },
  ORDER_DELIVERED: { icon: 'check-circle', tone: 'success' },
  ORDER_REASSIGNED: { icon: 'shuffle', tone: 'violet' },
  ORDER_KEPT: { icon: 'undo', tone: 'success' },
  SUGGESTION_CREATED: { icon: 'zap', tone: 'primary' },
  SUGGESTION_ACCEPTED: { icon: 'check', tone: 'success' },
  SUGGESTION_REJECTED: { icon: 'x', tone: 'danger' },
  SUGGESTIONS_WITHDRAWN: { icon: 'refresh', tone: 'warning' },
  STRATEGY_SWITCHED: { icon: 'scale', tone: 'violet' },
};

const SOURCE_ICON: Record<string, string> = { ai: 'sparkles', 'rule-based': 'scale', fallback: 'alert', unknown: 'clock' };

/** Is the AI earning its keep? Outcomes per strategy, plus a timeline of everything that happened. */
@Component({
  selector: 'app-insights-page',
  standalone: true,
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Insights</h1>
          <p class="page-sub">How each routing strategy performs with real decisions, and a timeline of everything the
            system and ops did.</p>
        </div>
        <span class="subtle small">Refreshes every 5s</span>
      </div>

      <div class="kpis">
        @for (k of kpis(); track k.label) {
          <div class="kpi card">
            <div class="kpi-icon" [class]="'kpi-icon tone-' + k.tone"><app-icon [name]="k.icon" [size]="16" /></div>
            <div>
              <div class="kpi-label">{{ k.label }}</div>
              <div class="kpi-value">{{ k.value }}</div>
              <div class="kpi-detail subtle">{{ k.detail }}</div>
            </div>
          </div>
        }
      </div>

      <section class="card">
        <header class="card-header">
          <div>
            <div class="card-title">By source</div>
            <div class="card-sub">Acceptance rate = accepted ÷ (accepted + rejected). Expired suggestions were withdrawn by
              the system, so they don't count either way.</div>
          </div>
        </header>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th>Source</th><th class="num">Suggestions</th><th class="num">Accepted</th><th class="num">Rejected</th>
                <th class="num">Expired</th><th>Acceptance rate</th><th class="num">Avg confidence</th><th class="num">Response time</th></tr>
            </thead>
            <tbody>
              @for (s of metrics()?.bySource ?? []; track s.key) {
                <tr>
                  <td><span class="source" [class]="'source ' + s.key"><app-icon [name]="sourceIcon[s.key]" [size]="14" />{{ s.label }}</span></td>
                  <td class="num">{{ s.total }}</td>
                  <td class="num">{{ s.accepted }}</td>
                  <td class="num">{{ s.rejected }}</td>
                  <td class="num subtle">{{ s.expired }}</td>
                  <td>
                    @if (s.acceptanceRate !== null) {
                      <div class="rate">
                        <div class="meter" [class]="'meter ' + rateLevel(s.acceptanceRate)"><span [style.width.%]="s.acceptanceRate * 100"></span></div>
                        <span class="rate-value">{{ pct(s.acceptanceRate) }}</span>
                      </div>
                    } @else { <span class="subtle">No decisions yet</span> }
                  </td>
                  <td class="num">{{ s.avgConfidence !== null ? pct(s.avgConfidence) : '-' }}</td>
                  <td class="num">{{ timing(s) }}</td>
                </tr>
              } @empty {
                <tr><td colspan="8"><div class="empty"><h3>{{ loaded() ? 'No suggestions yet' : 'Loading…' }}</h3></div></td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section class="card">
        <header class="card-header">
          <div>
            <div class="card-title">Activity</div>
            <div class="card-sub">Latest {{ activity().length }} events, newest first</div>
          </div>
        </header>
        <ol class="timeline">
          @for (a of activity(); track a.id) {
            <li class="event">
              <span class="event-icon" [class]="'event-icon tone-' + style(a).tone"><app-icon [name]="style(a).icon" [size]="14" /></span>
              <div class="event-body">
                <div class="event-msg">{{ a.message }}</div>
                <div class="event-meta subtle">
                  <span class="badge" [class]="a.actor === 'system' ? 'badge tone-primary' : 'badge tone-neutral'">
                    {{ a.actor === 'system' ? 'System' : 'Ops' }}
                  </span>
                  {{ ago(a.at) }}
                </div>
              </div>
            </li>
          } @empty {
            <li class="empty"><h3>{{ loaded() ? 'Nothing has happened yet' : 'Loading…' }}</h3>
              <p>Change an agent's status or create an order and it will show up here.</p></li>
          }
        </ol>
      </section>
    </div>
  `,
  styles: [`
    .small { font-size: 12.5px; }
    .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
    .kpi { display: flex; gap: 12px; padding: 14px 16px; }
    .kpi-icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 8px; flex: none; }
    .kpi-label { font-size: 12.5px; color: var(--text-2); font-weight: 500; }
    .kpi-value { font-size: 24px; font-weight: 650; letter-spacing: -0.02em; line-height: 1.25; font-variant-numeric: tabular-nums; }
    .kpi-detail { font-size: 12px; }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .source { display: inline-flex; align-items: center; gap: 6px; font-weight: 500; }
    .source.ai { color: var(--violet-text); }
    .source.fallback { color: var(--warning-text); }
    .rate { display: flex; align-items: center; gap: 8px; min-width: 140px; }
    .rate .meter { flex: 1; }
    .rate-value { font-weight: 600; font-variant-numeric: tabular-nums; min-width: 38px; text-align: right; }
    .timeline { list-style: none; margin: 0; padding: 6px 18px 12px; }
    .event { display: flex; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); }
    .event:last-child { border-bottom: 0; }
    .event-icon { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 50%; flex: none; }
    .event-body { min-width: 0; }
    .event-msg { font-size: 13.5px; }
    .event-meta { display: flex; align-items: center; gap: 8px; margin-top: 3px; font-size: 12px; }
    .event-meta .badge { height: 18px; font-size: 11px; }
    @media (max-width: 900px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
  `],
})
export class InsightsPage implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly timer = setInterval(() => this.load(), 5000);

  readonly metrics = signal<MetricsSummary | null>(null);
  readonly activity = signal<Activity[]>([]);
  readonly loaded = signal(false);
  readonly now = signal(new Date());
  readonly sourceIcon = SOURCE_ICON;
  readonly pct = percent;

  readonly kpis = computed(() => {
    const m = this.metrics();
    const ai = m?.bySource.find(s => s.key === 'ai');
    const providers = m ? Object.entries(m.aiProviders).map(([p, n]) => `${p} ${n}`).join(' · ') : '';
    return [
      { label: 'Suggestions made', value: `${m?.totalSuggestions ?? '-'}`, icon: 'zap', tone: 'primary',
        detail: m ? `${m.decided} decided by ops` : '' },
      { label: 'Acceptance rate', value: m?.acceptanceRate != null ? percent(m.acceptanceRate) : '-', icon: 'check-circle', tone: 'success',
        detail: 'Across all sources' },
      { label: 'AI fallback rate', value: m?.aiFallbackRate != null ? percent(m.aiFallbackRate) : '-', icon: 'alert', tone: 'warning',
        detail: providers ? `Answered by: ${providers}` : 'No AI suggestions yet' },
      { label: 'AI response time', value: ai?.avgRoutingMs != null ? this.seconds(ai.avgRoutingMs) : '-', icon: 'clock', tone: 'violet',
        detail: ai?.p95RoutingMs != null ? `p95 ${this.seconds(ai.p95RoutingMs)}` : 'Average per suggestion' },
    ];
  });

  constructor() {
    this.load();
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  style(a: Activity) {
    return ACTIVITY_STYLE[a.type] ?? { icon: 'info', tone: 'neutral' };
  }

  ago(iso: string): string {
    return timeAgo(iso, this.now());
  }

  rateLevel(rate: number): string {
    return rate >= 0.7 ? 'high' : rate >= 0.4 ? 'medium' : 'low';
  }

  timing(s: SourceStats): string {
    if (s.avgRoutingMs == null) {
      return '-';
    }
    return `${this.seconds(s.avgRoutingMs)}` + (s.p95RoutingMs != null ? ` · p95 ${this.seconds(s.p95RoutingMs)}` : '');
  }

  private seconds(ms: number): string {
    return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
  }

  private load(): void {
    forkJoin({ metrics: this.api.getMetrics(), activity: this.api.getActivity(60) }).subscribe({
      next: ({ metrics, activity }) => {
        this.metrics.set(metrics);
        this.activity.set(activity);
        this.now.set(new Date());
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }
}
