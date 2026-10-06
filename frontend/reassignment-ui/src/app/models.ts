/** Shapes returned by the backend API (see README → API). */

export type AgentStatus = 'AVAILABLE' | 'BUSY' | 'OFFLINE';
export type OrderStatus = 'ASSIGNED' | 'REASSIGNMENT_PENDING' | 'REASSIGNED' | 'DELIVERED';
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED';
export type TriggerReason = 'INITIAL' | 'AGENT_OFFLINE' | 'SLA_RISK';

export interface Agent {
  id: string;
  name: string;
  status: AgentStatus;
  activeOrderCount: number;
  currentZone: string | null;
  maxCapacity: number | null;
  /** Last check-in from the agent's phone app; null = no app being monitored. */
  lastHeartbeatAt: string | null;
  /** Why the system last changed their status (e.g. automatic offline). */
  statusNote: string | null;
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
  /** Top pick the recommendation engine showed when the order was created (null if none). */
  recommendedAgentId: string | null;
  followedRecommendation: boolean | null;
  /** When the SLA monitor flagged it as likely to miss slaDeadline. */
  slaAlertedAt: string | null;
}

export interface Zone {
  id: string;
  name: string;
  neighbours: string[];
}

/** GET /config */
export interface AppConfig {
  /** 0 = no limit */
  defaultMaxCapacity: number;
  defaultSlaMinutes: number;
  slaAtRiskMinutes: number;
  zones: Zone[];
}

export interface NewOrderRequest {
  description: string;
  assignedAgentId: string;
  recommendedAgentId: string | null;
  pickupZone: string | null;
  dropoffZone: string | null;
  /** null = default deadline, 0 = none */
  slaMinutes: number | null;
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
  /** How long routing took, including any AI calls. */
  routingMillis: number | null;
  createdAt: string;
  decidedAt: string | null;
}

export interface Activity {
  id: number;
  at: string;
  type: string;
  actor: 'ops' | 'system';
  orderId: string | null;
  agentId: string | null;
  suggestionId: string | null;
  message: string;
}

export interface SourceStats {
  key: 'ai' | 'rule-based' | 'fallback' | 'unknown';
  label: string;
  total: number;
  accepted: number;
  rejected: number;
  expired: number;
  pending: number;
  acceptanceRate: number | null;
  avgConfidence: number | null;
  avgRoutingMs: number | null;
  p95RoutingMs: number | null;
}

export interface MetricsSummary {
  totalSuggestions: number;
  decided: number;
  acceptanceRate: number | null;
  aiFallbackRate: number | null;
  aiProviders: Record<string, number>;
  bySource: SourceStats[];
  newOrderPicks: { recommended: number; followed: number; followRate: number | null };
}

/** One ranked pick from POST /routing/recommend (same shape as a routing result). */
export interface Recommendation {
  recommendedAgentId: string;
  confidence: number;
  reasoning: string;
  source: string;
  routingMillis: number | null;
}

export interface RecommendResponse {
  strategy: string;
  options: Recommendation[];
}

export interface StrategyInfo {
  active: string;
  available: string[];
}
