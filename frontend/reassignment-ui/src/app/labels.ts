import { AgentStatus, OrderStatus } from './models';

/** Display helpers shared by every view: human labels, colour tones, formatting. */

export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'primary' | 'violet' | 'neutral';

export const AGENT_STATUS: Record<AgentStatus, { label: string; tone: Tone; hint: string }> = {
  AVAILABLE: { label: 'Available', tone: 'success', hint: 'On shift and taking new orders' },
  BUSY: { label: 'Busy', tone: 'warning', hint: 'Delivering current orders, not taking more' },
  OFFLINE: { label: 'Offline', tone: 'danger', hint: "Can't deliver; their orders get re-planned" },
};

export const AGENT_STATUSES: AgentStatus[] = ['AVAILABLE', 'BUSY', 'OFFLINE'];

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: Tone }> = {
  ASSIGNED: { label: 'Assigned', tone: 'info' },
  REASSIGNMENT_PENDING: { label: 'Needs agent', tone: 'warning' },
  REASSIGNED: { label: 'Reassigned', tone: 'violet' },
  DELIVERED: { label: 'Delivered', tone: 'success' },
};

export interface SourceInfo {
  kind: 'ai' | 'rule' | 'fallback';
  label: string;
  detail: string;
}

/** Turns a suggestion's source ("ai:gemini", "rule-based (AI fallback: TIMEOUT)") into display info. */
export function sourceInfo(source: string | null): SourceInfo {
  if (!source) {
    return { kind: 'rule', label: 'Unknown source', detail: 'Created before sources were recorded' };
  }
  if (source.startsWith('ai:')) {
    const provider = source.slice(3);
    const name = provider.charAt(0).toUpperCase() + provider.slice(1);
    return { kind: 'ai', label: `AI · ${name}`, detail: `Recommended by ${name}, checked against the live roster` };
  }
  const fallback = /\((?:AI )?fallback: ([^)]+)\)/.exec(source);
  if (fallback) {
    return {
      kind: 'fallback',
      label: 'Rule-based · AI fallback',
      detail: `The AI was skipped (${fallback[1]}), so the rule-based strategy answered`,
    };
  }
  return { kind: 'rule', label: 'Rule-based', detail: 'Lowest effective load wins (active + pending suggestions)' };
}

export type ConfidenceLevel = 'high' | 'medium' | 'low';

export function confidenceLevel(c: number): ConfidenceLevel {
  return c >= 0.8 ? 'high' : c >= 0.6 ? 'medium' : 'low';
}

export const CONFIDENCE_LABEL: Record<ConfidenceLevel, string> = {
  high: 'High confidence',
  medium: 'Medium confidence',
  low: 'Low confidence',
};

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

/** Stable colour per agent so the same person always has the same avatar colour. */
export function avatarHue(id: string): number {
  let h = 0;
  for (const ch of id) {
    h = (h * 31 + ch.charCodeAt(0)) % 360;
  }
  return (h * 47) % 360;
}

/** Backend timestamps are local date-times without a zone, e.g. "2026-10-05T01:00:21.634866". */
export function parseTime(iso: string | null): Date | null {
  if (!iso) {
    return null;
  }
  const d = new Date(iso.slice(0, 23));
  return isNaN(d.getTime()) ? null : d;
}

export function timeAgo(iso: string | null, now: Date = new Date()): string {
  const d = parseTime(iso);
  if (!d) {
    return '';
  }
  const s = Math.max(0, Math.round((now.getTime() - d.getTime()) / 1000));
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

export function percent(c: number): string {
  return `${Math.round(c * 100)}%`;
}

export interface DeadlineState {
  kind: 'late' | 'risk' | 'ok';
  /** Short text for a badge: "Late 12m", "Due in 20m", "Due 14:30". */
  label: string;
  /** Full time for a tooltip. */
  title: string;
}

/**
 * Where an order stands against its delivery deadline. "risk" = inside the
 * at-risk window the backend's SLA monitor uses (GET /config slaAtRiskMinutes).
 */
export function deadlineState(iso: string | null, now: Date, atRiskMinutes: number): DeadlineState | null {
  const due = parseTime(iso);
  if (!due) {
    return null;
  }
  const minutes = Math.round((due.getTime() - now.getTime()) / 60000);
  const clock = due.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const title = `Deliver by ${due.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}`;
  if (minutes < 0) {
    return { kind: 'late', label: `Late ${duration(-minutes)}`, title };
  }
  if (minutes <= atRiskMinutes) {
    return { kind: 'risk', label: `Due in ${duration(minutes)}`, title };
  }
  return { kind: 'ok', label: `Due ${clock}`, title };
}

function duration(minutes: number): string {
  if (minutes < 60) return `${minutes}m`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h}h ${m}m` : `${h}h`;
}
