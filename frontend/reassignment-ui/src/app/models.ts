/** Shapes returned by the backend API (see README → API). */

export type AgentStatus = 'AVAILABLE' | 'BUSY' | 'OFFLINE';
export type OrderStatus = 'ASSIGNED' | 'REASSIGNMENT_PENDING' | 'REASSIGNED' | 'DELIVERED';
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED';
export type TriggerReason = 'INITIAL' | 'AGENT_OFFLINE';

export interface Agent {
  id: string;
  name: string;
  status: AgentStatus;
  activeOrderCount: number;
  currentZone: string | null;
  maxCapacity: number | null;
}

export interface Order {
  id: string;
  description: string;
  assignedAgentId: string;
  status: OrderStatus;
  createdAt: string;
  pickupZone: string | null;
  dropoffZone: string | null;
  slaDeadline: string | null;
}

export interface Suggestion {
  id: string;
  orderId: string;
  recommendedAgentId: string;
  confidence: number;
  reasoning: string;
  status: SuggestionStatus;
  triggerReason: TriggerReason;
  /** e.g. "ai:gemini", "rule-based", "rule-based (AI fallback: TIMEOUT)" */
  source: string | null;
  createdAt: string;
  decidedAt: string | null;
}

export interface StrategyInfo {
  active: string;
  available: string[];
}
