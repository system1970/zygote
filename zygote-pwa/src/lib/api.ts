// zygote — harness API client.
// Talks ONLY to the native Android app's local server (loopback). No mocks.

export const BASE = 'http://127.0.0.1:8787';

export class ApiError extends Error {
  status: number;
  constructor(message: string, status = 0) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function jfetch<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      headers: { Accept: 'application/json' },
      ...init,
    });
  } catch (e) {
    throw new ApiError(
      e instanceof TypeError && /fetch/i.test(e.message)
        ? `Cannot reach harness at ${BASE}`
        : (e as Error).message,
      0
    );
  }
  if (!res.ok) throw new ApiError(`HTTP ${res.status} ${res.statusText}`, res.status);
  return (await res.json()) as T;
}

// ---------------------------------------------------------------- telemetry

export interface Telemetry {
  model: string;
  tok_per_sec: number;
  ttft_ms: number;
  ram_total_mb: number;
  ram_mb: number;
  battery_pct: number;
  turns: number;
  steps: number;
  llm_time_ms: number;
  tool_time_ms: number;
  input_tokens: number;
}

export async function getTelemetry(sessionId?: string): Promise<Telemetry> {
  const q = sessionId ? `?session=${encodeURIComponent(sessionId)}` : '';
  return jfetch<Telemetry>(`/v1/telemetry${q}`);
}

// ------------------------------------------------------------------ skills

export interface Skill {
  name: string;
  description?: string;
  version?: string;
}

export async function getSkills(): Promise<Skill[]> {
  return jfetch<Skill[]>('/v1/skills');
}

// ----------------------------------------------------------------- sessions

export interface SessionMeta {
  session_id: string;
  title: string;
  updated_at: number;
}

export async function getSessions(): Promise<SessionMeta[]> {
  return jfetch<SessionMeta[]>('/v1/sessions');
}

export async function createSession(): Promise<string> {
  const o = await jfetch<{ session_id: string }>('/v1/sessions', { method: 'POST' });
  return o.session_id;
}

export interface TodoItem {
  content: string;
  status: string;
  priority: string;
  position: number;
}

export async function getTodos(sessionId: string): Promise<TodoItem[]> {
  return jfetch<TodoItem[]>(`/v1/session/${encodeURIComponent(sessionId)}/todos`);
}

// ------------------------------------------------------------- trajectory

export type TrajectoryEvent = {
  kind: string;
  text?: string;
  tool?: string;
  at?: number;
  seq?: number;
};

export async function getSession(sessionId: string): Promise<TrajectoryEvent[]> {
  const o = await jfetch<{ session_id: string; events: TrajectoryEvent[] }>(
    `/v1/session/${encodeURIComponent(sessionId)}`
  );
  return o.events ?? [];
}

export async function switchModel(model: string): Promise<{ model: string; loading?: boolean }> {
  return jfetch<{ model: string; loading?: boolean }>('/v1/model', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model }),
  });
}

// ------------------------------------------------------------- agent run

export interface RunRequest {
  message: string;
  session_id: string;
}

export type AgentEvent =
  | { type: 'text_delta'; delta: string }
  | { type: 'think'; text: string }
  | { type: 'think_delta'; delta: string }
  | { type: 'tool_start'; name: string; args?: Record<string, unknown> }
  | { type: 'tool_result'; name: string; output: string }
  | { type: 'status'; text: string }
  | { type: 'done'; final_text?: string; model?: string };

export interface NonStreamingRun {
  final_text?: string;
  events?: AgentEvent[];
}

/** Run the agent, streaming events via SSE with a graceful non-streaming
 *  fallback when the server replies with JSON instead of text/event-stream. */
export async function runAgent(
  req: RunRequest,
  onEvent: (e: AgentEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  let res: Response;
  try {
    res = await fetch(BASE + '/v1/agent/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(req),
      signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(`Cannot reach harness at ${BASE}`, 0);
  }
  if (!res.ok) throw new ApiError(`HTTP ${res.status} ${res.statusText}`, res.status);

  const ctype = res.headers.get('content-type') || '';
  if (!ctype.includes('text/event-stream')) {
    // Non-streaming fallback: { final_text, events }
    const data = (await res.json()) as NonStreamingRun;
    if (data.events) data.events.forEach(onEvent);
    if (data.final_text) onEvent({ type: 'text_delta', delta: data.final_text });
    return;
  }

  if (!res.body) throw new ApiError('Streaming body unavailable', 0);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = (rawLine: string) => {
    const line = rawLine.trim();
    if (!line || !line.startsWith('data:')) return;
    const data = line.slice(5).trim();
    if (!data || data === '[DONE]') return;
    try {
      onEvent(JSON.parse(data) as AgentEvent);
    } catch {
      // ignore malformed frames
    }
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      block.split('\n').forEach(dispatch);
    }
  }
}
