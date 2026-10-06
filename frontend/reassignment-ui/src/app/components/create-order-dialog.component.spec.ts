import { ComponentFixture, TestBed, fakeAsync, flush, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Agent, Recommendation } from '../models';
import { StoreService } from '../services/store.service';
import { CreateOrderDialogComponent } from './create-order-dialog.component';

function agent(id: string, active: number): Agent {
  return { id, name: 'Agent ' + id, status: 'AVAILABLE', activeOrderCount: active, currentZone: null, maxCapacity: null,
    lastHeartbeatAt: null, statusNote: null };
}

function rec(agentId: string, confidence: number): Recommendation {
  return { recommendedAgentId: agentId, confidence, reasoning: 'because', source: 'rule-based', routingMillis: 3 };
}

describe('CreateOrderDialogComponent', () => {
  let fixture: ComponentFixture<CreateOrderDialogComponent>;
  let dialog: CreateOrderDialogComponent;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CreateOrderDialogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const store = TestBed.inject(StoreService);
    store.agents.set([agent('A1', 0), agent('A2', 3)]);
    store.config.set({ defaultMaxCapacity: 6, defaultSlaMinutes: 120, slaAtRiskMinutes: 30, zones: [], demoTools: false });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreateOrderDialogComponent);
    dialog = fixture.componentInstance;
  });

  afterEach(() => http.verify());

  it('asks for a recommendation on open and pre-selects the best pick', fakeAsync(() => {
    dialog.open = true;
    tick();
    const req = http.expectOne('/api/routing/recommend');
    expect(req.request.body).toEqual({ description: '', pickupZone: null, dropoffZone: null });
    req.flush({ strategy: 'rule-based', options: [rec('A2', 0.9), rec('A1', 0.5)] });

    expect(dialog.agentId()).toBe('A2');
    expect(dialog.topPick()).toBe('A2');
  }));

  it('waits for a pause in typing, but asks at once when a zone is picked', fakeAsync(() => {
    dialog.open = true;
    tick();
    http.expectOne('/api/routing/recommend').flush({ strategy: 'rule-based', options: [rec('A1', 0.8)] });

    dialog.onDescription('Cake');
    tick(300);
    http.expectNone('/api/routing/recommend');
    tick(600);
    expect(http.expectOne('/api/routing/recommend').request.body.description).toBe('Cake');

    dialog.onZone('pickup', 'KORAMANGALA');
    tick();
    expect(http.expectOne('/api/routing/recommend').request.body.pickupZone).toBe('KORAMANGALA');
  }));

  it('keeps the agent ops picked when a newer recommendation arrives', fakeAsync(() => {
    dialog.open = true;
    tick();
    http.expectOne('/api/routing/recommend').flush({ strategy: 'rule-based', options: [rec('A1', 0.8)] });

    dialog.pick('A2');
    dialog.onZone('pickup', 'KORAMANGALA');
    tick();
    http.expectOne('/api/routing/recommend').flush({ strategy: 'rule-based', options: [rec('A1', 0.9)] });

    expect(dialog.agentId()).toBe('A2');
  }));

  it('creates the order with zones, deadline and the recommendation that was shown', fakeAsync(() => {
    dialog.open = true;
    tick();
    http.expectOne('/api/routing/recommend').flush({ strategy: 'rule-based', options: [rec('A1', 0.8)] });

    dialog.description.set('Cake');
    dialog.pickupZone.set('KORAMANGALA');
    dialog.slaMinutes.set(60);
    dialog.pick('A2');
    dialog.submit();

    const req = http.expectOne('/api/orders');
    expect(req.request.body).toEqual({
      description: 'Cake', assignedAgentId: 'A2', recommendedAgentId: 'A1',
      pickupZone: 'KORAMANGALA', dropoffZone: null, slaMinutes: 60,
    });
    req.flush({ id: 'ORD-1', assignedAgentId: 'A2', followedRecommendation: false });
    tick();
    // a successful create refreshes the store; then let the toast's timer run out
    http.match(() => true).forEach(r => r.flush([]));
    flush();
  }));
});
