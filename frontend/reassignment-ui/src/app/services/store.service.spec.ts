import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Agent, Order, Suggestion } from '../models';
import { StoreService, topicsOf } from './store.service';

function agent(id: string, active: number, extra: Partial<Agent> = {}): Agent {
  return { id, name: id, status: 'AVAILABLE', activeOrderCount: active, currentZone: null, maxCapacity: null,
    lastHeartbeatAt: null, statusNote: null, ...extra };
}

function order(id: string, extra: Partial<Order> = {}): Order {
  return { id, description: id, assignedAgentId: 'A1', status: 'REASSIGNMENT_PENDING', createdAt: '2026-10-06T09:00:00',
    pickupZone: null, dropoffZone: null, slaDeadline: null, recommendedAgentId: null, followedRecommendation: null,
    ...extra };
}

function suggestion(orderId: string, agentId: string, extra: Partial<Suggestion> = {}): Suggestion {
  return { id: 'S-' + orderId, orderId, recommendedAgentId: agentId, confidence: 0.8, reasoning: '', status: 'PENDING',
    triggerReason: 'AGENT_OFFLINE', source: 'rule-based', routingMillis: 1, createdAt: '2026-10-06T09:00:00',
    decidedAt: null, ...extra };
}

describe('StoreService', () => {
  let store: StoreService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    store = TestBed.inject(StoreService);
    store.config.set({ defaultMaxCapacity: 4, defaultSlaMinutes: 120, slaAtRiskMinutes: 30,
      zones: [{ id: 'KORAMANGALA', name: 'Koramangala', neighbours: [] }], demoTools: false });
  });

  it('measures load (active + queued suggestions) against the agent\'s or the fleet\'s capacity', () => {
    const own = agent('A1', 1, { maxCapacity: 2 });
    const fleetDefault = agent('A2', 2);
    store.agents.set([own, fleetDefault]);
    store.suggestions.set([suggestion('O1', 'A1'), suggestion('O2', 'A2')]);

    expect(store.loadOfCapacity(own)).toBe('2/2');
    expect(store.isFull(own)).toBeTrue();
    expect(store.loadOfCapacity(fleetDefault)).toBe('3/4');
    expect(store.isFull(fleetDefault)).toBeFalse();
  });

  it('treats a fleet default of 0 as no limit', () => {
    store.config.update(c => ({ ...c!, defaultMaxCapacity: 0 }));
    const a = agent('A1', 9);
    expect(store.isFull(a)).toBeFalse();
    expect(store.loadOfCapacity(a)).toBe('9');
  });

  it('puts waiting orders with the soonest deadline first, undated ones last', () => {
    store.orders.set([
      order('none'),
      order('later', { slaDeadline: '2026-10-06T15:00:00' }),
      order('soon', { slaDeadline: '2026-10-06T12:10:00' }),
      order('delivered', { status: 'DELIVERED' }),
    ]);
    expect(store.waitingOrders().map(o => o.id)).toEqual(['soon', 'later', 'none']);
  });

  it('lists orders still with their agent that have an open deadline re-plan', () => {
    store.orders.set([
      order('risky', { status: 'ASSIGNED', slaDeadline: '2026-10-06T12:10:00' }),
      order('fine', { status: 'ASSIGNED' }),
      order('stranded'),
    ]);
    store.suggestions.set([
      suggestion('risky', 'A2', { triggerReason: 'SLA_RISK' }),
      suggestion('fine', 'A2', { triggerReason: 'SLA_RISK', status: 'REJECTED' }),
      suggestion('stranded', 'A2', { triggerReason: 'SLA_RISK' }),
    ]);
    expect(store.atRiskOrders().map(o => o.id)).toEqual(['risky']);
  });

  it('re-reads only the lists a change event names', () => {
    expect(topicsOf('{"topics":["agents"]}')).toEqual(['agents']);
    expect(topicsOf('{"topics":["orders","suggestions","activity"]}')).toEqual(['orders', 'suggestions']);
    expect(topicsOf('{"topics":["activity"]}')).toEqual([]); // Insights reloads itself
    expect(topicsOf('not json')).toEqual(['agents', 'orders', 'suggestions', 'settings']);
  });

  it('names zones from the config', () => {
    expect(store.zoneName('KORAMANGALA')).toBe('Koramangala');
    expect(store.zoneName('ELSEWHERE')).toBe('ELSEWHERE');
    expect(store.zoneName(null)).toBeNull();
  });
});
