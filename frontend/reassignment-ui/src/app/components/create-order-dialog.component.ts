import { ChangeDetectionStrategy, Component, DestroyRef, EventEmitter, Input, Output, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, catchError, distinctUntilChanged, map, of, switchMap, tap, timer } from 'rxjs';
import { Recommendation } from '../models';
import { confidenceLevel, percent, sourceInfo } from '../labels';
import { ApiService, errorMessage } from '../services/api.service';
import { StoreService } from '../services/store.service';
import { ToastService } from '../services/toast.service';
import { AvatarComponent } from '../ui/avatar.component';
import { IconComponent } from '../ui/icon.component';

interface RecommendQuery {
  text: string;
  pickup: string;
  dropoff: string;
  force: boolean;
}

function duration(minutes: number): string {
  return minutes < 60 ? `${minutes} min` : `${minutes / 60} hour${minutes === 60 ? '' : 's'}`;
}

/**
 * "New order" modal. The active routing strategy (AI or rule-based) recommends
 * the best Available agents by effective load, refreshed as the description is
 * typed; the top pick is pre-selected, and ops can still choose anyone Available.
 */
@Component({
  selector: 'app-create-order-dialog',
  standalone: true,
  imports: [FormsModule, AvatarComponent, IconComponent],
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
            <input id="desc" name="desc" class="input" [ngModel]="description()" (ngModelChange)="onDescription($event)"
                   required autofocus placeholder="e.g. Groceries - Koramangala to HSR Layout" />
          </div>

          <div class="grid3">
            <div class="field">
              <label for="pickup">Pickup zone</label>
              <select id="pickup" name="pickup" class="select" [ngModel]="pickupZone()" (ngModelChange)="onZone('pickup', $event)">
                <option value="">Not set</option>
                @for (z of zones(); track z.id) { <option [value]="z.id">{{ z.name }}</option> }
              </select>
            </div>
            <div class="field">
              <label for="dropoff">Drop-off zone</label>
              <select id="dropoff" name="dropoff" class="select" [ngModel]="dropoffZone()" (ngModelChange)="onZone('dropoff', $event)">
                <option value="">Not set</option>
                @for (z of zones(); track z.id) { <option [value]="z.id">{{ z.name }}</option> }
              </select>
            </div>
            <div class="field">
              <label for="sla">Deliver within</label>
              <select id="sla" name="sla" class="select" [ngModel]="slaMinutes()" (ngModelChange)="slaMinutes.set(+$event)">
                @for (s of slaOptions(); track s.minutes) { <option [value]="s.minutes">{{ s.label }}</option> }
              </select>
            </div>
          </div>

          <div class="field">
            <div class="rec-head">
              <label>Recommended agents</label>
              @if (loading()) {
                <span class="subtle rec-status"><app-icon name="loader" [size]="13" class="spin" />{{ strategy() === 'ai' ? 'Asking the AI…' : 'Ranking…' }}</span>
              } @else if (source()) {
                <span class="rec-status" [class]="'rec-status source-' + source()!.kind" [attr.title]="source()!.detail">
                  <app-icon [name]="source()!.kind === 'ai' ? 'sparkles' : 'scale'" [size]="13" />{{ source()!.label }}
                </span>
              }
            </div>

            @if (loading() && !options().length) {
              @for (i of [1, 2]; track i) { <div class="skeleton rec-skeleton"></div> }
            } @else if (error()) {
              <div class="rec-empty subtle"><app-icon name="alert" [size]="14" />Couldn't get a recommendation ({{ error() }}). Pick an agent below.</div>
            } @else if (!options().length) {
              <div class="rec-empty subtle"><app-icon name="info" [size]="14" />No Available agents right now.</div>
            } @else {
              <div class="recs" role="radiogroup" aria-label="Recommended agents" [class.stale]="loading()">
                @for (o of options(); track o.recommendedAgentId; let first = $first) {
                  <button type="button" class="rec" role="radio" [attr.aria-checked]="agentId() === o.recommendedAgentId"
                          [class.on]="agentId() === o.recommendedAgentId" (click)="pick(o.recommendedAgentId)">
                    <app-avatar [agentId]="o.recommendedAgentId" [agentName]="store.agentName(o.recommendedAgentId)" status="AVAILABLE" size="sm" />
                    <span class="rec-body">
                      <span class="rec-top">
                        <span class="rec-name">{{ store.agentName(o.recommendedAgentId) }}</span>
                        @if (first) { <span class="badge tone-primary">Best pick</span> }
                        <span class="spacer"></span>
                        <span class="rec-conf" [class]="'rec-conf conf-' + level(o.confidence)">{{ pct(o.confidence) }}</span>
                      </span>
                      <span class="rec-load subtle">{{ loadText(o.recommendedAgentId) }}</span>
                      <span class="rec-why" [attr.title]="o.reasoning">{{ o.reasoning }}</span>
                    </span>
                  </button>
                }
              </div>
            }
          </div>

          <div class="field">
            <label for="agent">Or choose any Available agent</label>
            <select id="agent" name="agent" class="select" [ngModel]="agentId()" (ngModelChange)="pick($event)" required>
              @for (a of store.availableAgents(); track a.id) {
                <option [value]="a.id">{{ a.name }} · {{ store.loadOfCapacity(a) }} orders{{ store.isFull(a) ? ' · full' : '' }}{{ a.id === topPick() ? ' · recommended' : '' }}</option>
              }
            </select>
            <span class="hint">Busy and Offline agents aren't taking new orders.</span>
          </div>

          <div class="row foot">
            @if (agentId() && topPick() && agentId() !== topPick()) {
              <span class="subtle override"><app-icon name="info" [size]="13" />Not the recommended agent</span>
            }
            <span class="spacer"></span>
            <button type="button" class="btn btn-secondary" (click)="close()">Cancel</button>
            <button type="submit" class="btn btn-primary" [disabled]="!description().trim() || !agentId() || saving()">
              @if (saving()) { <app-icon name="loader" class="spin" /> } Create order
            </button>
          </div>
        </form>
      </div>
    }
  `,
  styles: [`
    .backdrop { position: fixed; inset: 0; background: rgba(15, 17, 23, 0.45); backdrop-filter: blur(2px); z-index: 50; }
    .dialog {
      /* centred with margins, not transform: the fade-in animation owns transform */
      position: fixed; z-index: 51; left: 0; right: 0; top: 8vh; margin: 0 auto;
      width: min(540px, calc(100vw - 24px)); max-height: 86vh; overflow-y: auto; box-shadow: var(--shadow-lg);
    }
    .form { display: flex; flex-direction: column; gap: 16px; }
    .grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
    @media (max-width: 520px) { .grid3 { grid-template-columns: 1fr 1fr; } .grid3 .field:last-child { grid-column: 1 / -1; } }
    .foot { padding-top: 4px; flex-wrap: wrap; gap: 8px; }
    .override { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; }

    .rec-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .rec-status { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 500; }
    .source-ai { color: var(--violet-text); }
    .source-fallback { color: var(--warning-text); }
    .source-rule { color: var(--text-2); }
    .rec-skeleton { height: 64px; border-radius: var(--radius); }
    .rec-skeleton + .rec-skeleton { margin-top: 8px; }
    .rec-empty { display: flex; align-items: center; gap: 6px; font-size: 12.5px; padding: 10px 12px; border: 1px dashed var(--border); border-radius: var(--radius); }

    .recs { display: flex; flex-direction: column; gap: 8px; transition: opacity 0.15s; }
    .recs.stale { opacity: 0.55; }
    .rec {
      display: flex; gap: 10px; align-items: flex-start; width: 100%; text-align: left;
      padding: 10px 12px; border-radius: var(--radius); cursor: pointer;
      border: 1px solid var(--border); background: var(--surface); color: inherit; font: inherit;
      transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
    }
    .rec:hover { background: var(--surface-hover); }
    .rec.on { border-color: var(--primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 18%, transparent); }
    .rec-body { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
    .rec-top { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .rec-name { font-weight: 600; font-size: 13.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .rec-conf { font-size: 12.5px; font-weight: 650; font-variant-numeric: tabular-nums; }
    .conf-high { color: var(--success-text); }
    .conf-medium { color: var(--warning-text); }
    .conf-low { color: var(--danger-text); }
    .rec-load { font-size: 12px; }
    .rec-why {
      font-size: 12.5px; color: var(--text-2); line-height: 1.45;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
    }
  `],
})
export class CreateOrderDialogComponent {
  private readonly api = inject(ApiService);
  readonly store = inject(StoreService);
  private readonly toast = inject(ToastService);

  @Output() closed = new EventEmitter<void>();

  /** Wait for a pause in typing before asking again (AI calls take a few seconds). */
  private static readonly DEBOUNCE_MS = 800;

  private isOpen = false;
  @Input() set open(value: boolean) {
    const opening = value && !this.isOpen;
    this.isOpen = value;
    if (opening) {
      this.description.set('');
      this.pickupZone.set('');
      this.dropoffZone.set('');
      this.slaMinutes.set(this.store.config()?.defaultSlaMinutes ?? 120);
      this.agentId.set(this.store.availableAgents()[0]?.id ?? '');
      this.userPicked = false;
      this.options.set([]);
      this.ask(true);
    }
  }
  get open(): boolean {
    return this.isOpen;
  }

  readonly description = signal('');
  readonly pickupZone = signal('');
  readonly dropoffZone = signal('');
  /** 0 = no deadline */
  readonly slaMinutes = signal(120);
  readonly agentId = signal('');

  readonly zones = computed(() => this.store.config()?.zones ?? []);
  readonly slaOptions = computed(() => {
    const fallback = this.store.config()?.defaultSlaMinutes ?? 120;
    const choices = [30, 60, 120, 240, 480];
    if (!choices.includes(fallback)) choices.push(fallback);
    return [...choices.sort((a, b) => a - b).map(m => ({ minutes: m, label: duration(m) + (m === fallback ? ' (default)' : '') })),
      { minutes: 0, label: 'No deadline' }];
  });
  readonly saving = signal(false);

  readonly options = signal<Recommendation[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly topPick = computed(() => this.options()[0]?.recommendedAgentId ?? null);
  readonly source = computed(() => {
    const top = this.options()[0];
    return top ? sourceInfo(top.source) : null;
  });
  readonly strategy = computed(() => this.store.strategy()?.active ?? null);

  readonly pct = percent;
  readonly level = confidenceLevel;

  /** True once ops picks an agent themselves: new recommendations then stop moving their choice. */
  private userPicked = false;
  private readonly requests = new Subject<RecommendQuery>();

  constructor() {
    this.requests.pipe(
      // opening or picking a zone asks straight away; typing waits for a pause
      // (a timer, not debounceTime on of(): that would emit as soon as of() completes)
      switchMap(req => req.force ? of(req) : timer(CreateOrderDialogComponent.DEBOUNCE_MS).pipe(map(() => req))),
      map(req => ({ ...req, text: req.text.trim() })),
      distinctUntilChanged((prev, next) => !next.force && prev.text === next.text
        && prev.pickup === next.pickup && prev.dropoff === next.dropoff),
      tap(() => { this.loading.set(true); this.error.set(null); }),
      // switchMap cancels a slower in-flight request when the inputs change again
      switchMap(({ text, pickup, dropoff }) => this.api.recommendAgents(text, pickup || null, dropoff || null).pipe(
        map(res => ({ options: res.options, error: null as string | null })),
        catchError(err => of({ options: [] as Recommendation[], error: errorMessage(err) })),
      )),
      takeUntilDestroyed(inject(DestroyRef)),
    ).subscribe(({ options, error }) => {
      this.loading.set(false);
      this.error.set(error);
      this.options.set(options);
      if (!this.userPicked && options.length) {
        this.agentId.set(options[0].recommendedAgentId);
      }
    });
  }

  onDescription(text: string): void {
    this.description.set(text);
    this.ask(false);
  }

  onZone(which: 'pickup' | 'dropoff', zone: string): void {
    (which === 'pickup' ? this.pickupZone : this.dropoffZone).set(zone);
    this.ask(false, true);
  }

  private ask(force: boolean, immediate = force): void {
    this.requests.next({ text: this.description(), pickup: this.pickupZone(), dropoff: this.dropoffZone(), force: immediate });
  }

  pick(agentId: string): void {
    this.agentId.set(agentId);
    this.userPicked = true;
  }

  loadText(agentId: string): string {
    const agent = this.store.agentById().get(agentId);
    if (!agent) {
      return '';
    }
    const pending = this.store.pendingByAgent().get(agentId) ?? 0;
    const capacity = this.store.capacityOf(agent);
    const zone = this.store.zoneName(agent.currentZone);
    return `${agent.activeOrderCount} active` + (pending ? ` + ${pending} queued` : '')
      + (capacity ? ` · ${this.store.loadOfCapacity(agent)} of capacity` : '') + (zone ? ` · ${zone}` : '');
  }

  close(): void {
    this.closed.emit();
  }

  submit(): void {
    const description = this.description().trim();
    const agentId = this.agentId();
    if (!description || !agentId) {
      return;
    }
    this.saving.set(true);
    this.api.createOrder({
      description,
      assignedAgentId: agentId,
      recommendedAgentId: this.topPick(),
      pickupZone: this.pickupZone() || null,
      dropoffZone: this.dropoffZone() || null,
      slaMinutes: this.slaMinutes(),
    }).subscribe({
      next: order => {
        this.saving.set(false);
        const followed = order.followedRecommendation;
        this.toast.success(`${order.id} created`, `Assigned to ${this.store.agentName(order.assignedAgentId)}`
          + (followed === true ? ' (recommended pick).' : '.'));
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
