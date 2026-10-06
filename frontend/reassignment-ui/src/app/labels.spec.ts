import { confidenceLevel, deadlineState, percent, sourceInfo, timeAgo } from './labels';

describe('labels', () => {
  const now = new Date('2026-10-06T12:00:00');

  describe('deadlineState', () => {
    it('is null without a deadline', () => {
      expect(deadlineState(null, now, 30)).toBeNull();
    });

    it('counts down inside the at-risk window', () => {
      const s = deadlineState('2026-10-06T12:20:00', now, 30)!;
      expect(s.kind).toBe('risk');
      expect(s.label).toBe('Due in 20m');
    });

    it('shows the clock time when comfortably ahead', () => {
      const s = deadlineState('2026-10-06T14:30:00', now, 30)!;
      expect(s.kind).toBe('ok');
      expect(s.label).toContain('Due ');
    });

    it('says how late an order is, in hours past an hour', () => {
      const s = deadlineState('2026-10-06T10:45:00', now, 30)!;
      expect(s.kind).toBe('late');
      expect(s.label).toBe('Late 1h 15m');
    });

    it('accepts the backend\'s microsecond timestamps', () => {
      expect(deadlineState('2026-10-06T12:10:00.123456', now, 30)!.kind).toBe('risk');
    });
  });

  describe('sourceInfo', () => {
    it('names the AI provider', () => {
      expect(sourceInfo('ai:groq')).toEqual(jasmine.objectContaining({ kind: 'ai', label: 'AI · Groq' }));
    });

    it('flags a rule-based answer standing in for a failed AI', () => {
      const info = sourceInfo('rule-based (AI fallback: CIRCUIT_OPEN)');
      expect(info.kind).toBe('fallback');
      expect(info.detail).toContain('CIRCUIT_OPEN');
    });

    it('handles plain rule-based and unknown sources', () => {
      expect(sourceInfo('rule-based').kind).toBe('rule');
      expect(sourceInfo(null).label).toBe('Unknown source');
    });
  });

  it('buckets confidence', () => {
    expect(confidenceLevel(0.9)).toBe('high');
    expect(confidenceLevel(0.6)).toBe('medium');
    expect(confidenceLevel(0.4)).toBe('low');
    expect(percent(0.756)).toBe('76%');
  });

  it('formats elapsed time', () => {
    expect(timeAgo('2026-10-06T11:59:55', now)).toBe('just now');
    expect(timeAgo('2026-10-06T11:15:00', now)).toBe('45m ago');
    expect(timeAgo('2026-10-04T12:00:00', now)).toBe('2d ago');
    expect(timeAgo(null, now)).toBe('');
  });
});
