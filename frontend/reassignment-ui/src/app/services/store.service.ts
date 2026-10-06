import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { Agent, AppConfig, Order, StrategyInfo, Suggestion } from '../models';
import { ApiService } from './api.service';

/**
 * Single source of truth for the UI, exposing derived views as signals.
 *
 * Stays current through the backend's live stream (GET /events): every committed
 * change pushes an event and the store re-reads at once, so suggestions created by
 * the background re-planning loop appear immediately. If the stream drops, it
 * falls back to polling every few seconds until it reconnects.
 */
/** Soonest deadline first; orders without one last. */
function byDeadline(a: Order, b: Order): number {
  if (a.slaDeadline === b.slaDeadline) return 0;
  if (!a.slaDeadline) return 1;
  if (!b.slaDeadline) return -1;
  return a.slaDeadline.localeCompare(b.slaDeadline);
}

@Injectable({ providedIn: 'root' })
export class StoreService implements OnDestroy {
  /** Polling interval while the live stream is down. */
  static readonly POLL_MS = 3000;
  /** Safety re-read while the live stream is up (it should never be needed). */
  static readonly LIVE_RESYNC_MS = 60000;

  private readonly api = inject(ApiService);
  private readonly timer: ReturnType<typeof setInterval>;
  private events: EventSource | null = null;
  private inFlight = false;
  private refreshAgain = false;

  readonly agents = signal<Agent[]>([]);
  readonly orders = signal<Order[]>([]);
  readonly suggestions = signal<Suggestion[]>([]);
  readonly strategy = signal<StrategyInfo | null>(null);
  /** Zones, default capacity and deadline rules (GET /config); loaded once. */
  readonly config = signal<AppConfig | null>(null);
  readonly loaded = signal(false);
  readonly connection = signal<'ok' | 'error'>('ok');
  /** True while the live stream (GET /events) is connected. */
  readonly live = signal(false);
  /** Increments on every pushed change, for pages that load their own data (Insights). */
  readonly changes = signal(0);
  readonly lastUpdated = signal<Date | null>(null);

  readonly agentById = computed(() => new Map(this.agents().map(a => [a.id, a])));

  /** Open (PENDING) suggestions per order, newest first. */
  readonly openSuggestionsByOrder = computed(() => {
    const map = new Map<string, Suggestion[]>();
    for (const s of this.suggestions()) {
      if (s.status !== 'PENDING') continue;
      const list = map.get(s.orderId) ?? [];
      list.push(s);
      map.set(s.orderId, list);
    }
    map.forEach(list => list.sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
    return map;
  });

  /** Orders waiting for a new agent: soonest deadline first, then newest. */
  readonly waitingOrders = computed(() =>
    this.orders()
      .filter(o => o.status === 'REASSIGNMENT_PENDING')
      .sort((a, b) => byDeadline(a, b) || b.createdAt.localeCompare(a.createdAt)));

  /** Orders still with their agent that the SLA monitor wants to move (open SLA_RISK suggestion). */
  readonly atRiskOrders = computed(() => {
    const flagged = new Set(this.suggestions()
      .filter(s => s.status === 'PENDING' && s.triggerReason === 'SLA_RISK')
      .map(s => s.orderId));
    return this.orders()
      .filter(o => o.status !== 'REASSIGNMENT_PENDING' && o.status !== 'DELIVERED' && flagged.has(o.id))
      .sort(byDeadline);
  });

  readonly zoneNames = computed(() => new Map((this.config()?.zones ?? []).map(z => [z.id, z.name])));

  readonly pendingSuggestions = computed(() => this.suggestions().filter(s => s.status === 'PENDING'));

  readonly availableAgents = computed(() =>
    this.agents()
      .filter(a => a.status === 'AVAILABLE')
      .sort((a, b) => a.activeOrderCount - b.activeOrderCount));

  readonly agentCounts = computed(() => {
    const counts = { AVAILABLE: 0, BUSY: 0, OFFLINE: 0 };
    this.agents().forEach(a => counts[a.status]++);
    return counts;
  });

  /** Pending suggestions recommending each agent (part of their effective load). */
  readonly pendingByAgent = computed(() => {
    const map = new Map<string, number>();
    this.pendingSuggestions().forEach(s => map.set(s.recommendedAgentId, (map.get(s.recommendedAgentId) ?? 0) + 1));
    return map;
  });

  constructor() {
    this.refresh();
    this.connectLive();
    this.timer = setInterval(() => {
      const last = this.lastUpdated()?.getTime() ?? 0;
      if (!this.live() || Date.now() - last > StoreService.LIVE_RESYNC_MS) {
        this.refresh();
      }
    }, StoreService.POLL_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    this.events?.close();
  }

  /** EventSource reconnects by itself after an error; we re-read on every (re)connect to catch up. */
  private connectLive(): void {
    if (typeof EventSource === 'undefined') {
      return;
    }
    this.events = this.api.openEvents();
    this.events.addEventListener('hello', () => {
      this.live.set(true);
      this.refresh();
    });
    this.events.addEventListener('change', () => {
      this.changes.update(n => n + 1);
      this.refresh();
    });
    this.events.onerror = () => this.live.set(false);
  }

  refresh(): void {
    if (this.inFlight) {
      // a change arrived mid-read: read again afterwards so nothing is missed
      this.refreshAgain = true;
      return;
    }
    if (!this.config()) {
      this.api.getConfig().subscribe({ next: c => this.config.set(c), error: () => undefined });
    }
    this.inFlight = true;
    forkJoin({
      agents: this.api.getAgents(),
      orders: this.api.getOrders(),
      suggestions: this.api.getSuggestions(),
      strategy: this.api.getRoutingStrategy(),
    }).subscribe({
      next: data => {
        this.agents.set(data.agents);
        this.orders.set(data.orders);
        this.suggestions.set(data.suggestions);
        this.strategy.set(data.strategy);
        this.connection.set('ok');
        this.loaded.set(true);
        this.lastUpdated.set(new Date());
        this.inFlight = false;
        this.refreshPending();
      },
      error: () => {
        this.connection.set('error');
        this.inFlight = false;
        this.refreshPending();
      },
    });
  }

  private refreshPending(): void {
    if (this.refreshAgain) {
      this.refreshAgain = false;
      this.refresh();
    }
  }

  agentName(id: string): string {
    return this.agentById().get(id)?.name ?? id;
  }

  zoneName(id: string | null | undefined): string | null {
    return id ? this.zoneNames().get(id) ?? id : null;
  }

  /** The agent's own limit, else the fleet default; 0 = no limit. */
  capacityOf(agent: Agent): number {
    return agent.maxCapacity && agent.maxCapacity > 0 ? agent.maxCapacity : this.config()?.defaultMaxCapacity ?? 0;
  }

  /** Active orders plus suggestions queued for them: what routing ranks and caps on. */
  effectiveLoad(agent: Agent): number {
    return agent.activeOrderCount + (this.pendingByAgent().get(agent.id) ?? 0);
  }

  isFull(agent: Agent): boolean {
    const capacity = this.capacityOf(agent);
    return capacity > 0 && this.effectiveLoad(agent) >= capacity;
  }

  /** "4/6" (or "4" with no limit). */
  loadOfCapacity(agent: Agent): string {
    const capacity = this.capacityOf(agent);
    return capacity > 0 ? `${this.effectiveLoad(agent)}/${capacity}` : `${this.effectiveLoad(agent)}`;
  }

  /** True when this agent is the only AVAILABLE one (backend refuses to take them off duty). */
  isLastAvailable(agent: Agent): boolean {
    return agent.status === 'AVAILABLE' && this.agentCounts().AVAILABLE === 1;
  }
}
