import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getTelemetry,
  getSkills,
  getSession,
  getSessions,
  createSession,
  getTodos,
  runAgent,
  ApiError,
  type Telemetry,
  type AgentEvent,
  type TrajectoryEvent,
} from './lib/api';
import {
  TopBar,
  SidebarDrawer,
  MessageList,
  OfflineState,
  EmptyState,
  TodosPanel,
  Composer,
  StatusBar,
  SettingsModal,
  loadSettings,
  looksLikeJson,
  type ChatItem,
  type SessionMeta,
  type TodoItem,
  type SettingsState,
} from './components';

const PROBE_INTERVAL = 3000;

function lastAssistantIdx(arr: ChatItem[]): number {
  for (let i = arr.length - 1; i >= 0; i--) {
    if (arr[i].kind === 'assistant' || arr[i].kind === 'json') return i;
  }
  return -1;
}

function truncate(s: string, n: number) {
  if (s.length <= n) return s;
  return s.slice(0, n).replace(/\s+\S*$/, '') + '…';
}

function shortModel(f: string): string {
  // Map GGUF filenames back to the clean display names used in the picker.
  if (f.includes('1.2B')) return 'LFM2.5-1.2B';
  if (f.includes('230M')) return 'LFM2.5-230M';
  return f.replace(/\.gguf$/, '');
}

/** Rebuilds the chat stream from a session's logged trajectory. */
function itemsFromTrajectory(events: TrajectoryEvent[]): ChatItem[] {
  const out: ChatItem[] = [];
  let id = 1;
  let lastBash: number | null = null;
  for (const ev of events) {
    switch (ev.kind) {
      case 'user':
        out.push({ kind: 'user', id: id++, text: ev.text ?? '' });
        break;
      case 'assistant':
        // Strip tool-call markup from logged raw text too (replay path).
        {
          const t = (ev.text ?? '').replace(/<\|tool_call_start\|>[\s\S]*?<\|tool_call_end\|>/g, '').trim();
          if (t) {
            out.push(
              looksLikeJson(t)
                ? { kind: 'json', id: id++, text: t }
                : { kind: 'assistant', id: id++, text: t }
            );
          }
        }
        break;
      case 'think':
        out.push({ kind: 'think', id: id++, text: ev.text ?? '' });
        break;
      case 'tool_start':
        out.push({ kind: 'bash', id: id++, command: ev.text ?? '' });
        lastBash = out.length - 1;
        break;
      case 'tool_result':
        if (lastBash !== null) {
          const b = out[lastBash] as Extract<ChatItem, { kind: 'bash' }>;
          b.output = (ev.text ?? '').slice(0, 2000);
        }
        break;
      default:
        break;
    }
  }
  return out;
}

export default function App() {
  const idRef = useRef(1);
  const sessionIdRef = useRef<string>('');
  const abortRef = useRef<AbortController | null>(null);
  // The assistant bubble belonging to the CURRENT turn — a new turn must
  // never append into or overwrite a previous turn's message.
  const turnAssistantRef = useRef<number | null>(null);

  const [items, setItems] = useState<ChatItem[]>([]);
  const [running, setRunning] = useState(false);
  const [model, setModel] = useState('LFM2.5-1.2B');
  const [online, setOnline] = useState(false);
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null);
  const [skills, setSkills] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  // Live in-flight tok/s: chars received since the first token of this run,
  // ÷ ~4 chars/token ÷ elapsed — the honest streaming rate.
  const [liveTok, setLiveTok] = useState<number | null>(null);
  const streamStartRef = useRef<number | null>(null);
  const streamCharsRef = useRef(0);
  const [todos, setTodos] = useState<TodoItem[]>([]);
  const [todosCollapsed, setTodosCollapsed] = useState(false);
  const [sessions, setSessions] = useState<SessionMeta[]>([]);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<SettingsState>(() => loadSettings());
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const activeTitle =
    sessions.find((s) => s.session_id === sessionIdRef.current)?.title || 'zygote';

  // Apply theme to <html data-theme="...">.
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', settings.theme);
  }, [settings.theme]);

  // ---------------- connection probe + telemetry ----------------
  const probeFailures = useRef(0);
  const probe = async () => {
    try {
      // Session-scoped telemetry: counters/tok/s/TTFT are per-session now.
      const t = await getTelemetry(sessionIdRef.current || undefined);
      probeFailures.current = 0;
      setOnline(true);
      setTelemetry(t);
      if (t.model) setModel(shortModel(t.model));
      return true;
    } catch {
      // Require 2 consecutive failures before showing the offline screen —
      // a single transient blip (model switch, GC pause, WebView hiccup)
      // should not yank the whole UI to "server not connected".
      probeFailures.current += 1;
      if (probeFailures.current >= 2) {
        setOnline(false);
        setTelemetry(null);
      }
      return false;
    }
  };

  useEffect(() => {
    void probe();
    const h = setInterval(() => void probe(), PROBE_INTERVAL);
    return () => clearInterval(h);
  }, []);

  // ---------------- sessions ----------------
  const refreshSessions = useCallback(async () => {
    try {
      const list = await getSessions();
      setSessions(list);
      if (!sessionIdRef.current && list.length > 0) {
        sessionIdRef.current = list[0].session_id;
        const evs = await getSession(list[0].session_id);
        setItems(itemsFromTrajectory(evs));
      }
    } catch {
      /* offline */
    }
  }, []);

  useEffect(() => {
    void refreshSessions();
  }, [refreshSessions]);

  const newSession = useCallback(async () => {
    try {
      const sid = await createSession();
      sessionIdRef.current = sid;
    } catch {
      sessionIdRef.current = 'zygote-' + Date.now().toString(36);
    }
    setItems([]);
    setTodos([]);
    setTodosCollapsed(false);
    void refreshSessions();
  }, [refreshSessions]);

  const selectSession = useCallback(async (id: string) => {
    sessionIdRef.current = id;
    setItems([]);
    setTodos([]);
    setTodosCollapsed(false);
    try {
      const evs = await getSession(id);
      setItems(itemsFromTrajectory(evs));
      setTodos(await getTodos(id));
    } catch {
      /* offline */
    }
  }, []);

  // ---------------- run ----------------
  const clean = (s: string) =>
    s.replace(/<\|tool_call_start\|>[\s\S]*?<\|tool_call_end\|>/g, '').trim();

  const onEvent = (e: AgentEvent) => {
    switch (e.type) {
      case 'text_delta': {
        const delta = e.delta ?? '';
        if (!delta) break;
        // Live streaming rate: chars ÷ ~4 chars/token ÷ elapsed since first token.
        const now = performance.now();
        if (streamStartRef.current === null) streamStartRef.current = now;
        streamCharsRef.current += delta.length;
        const secs = (now - streamStartRef.current) / 1000;
        if (secs >= 1) setLiveTok(streamCharsRef.current / 4 / secs);
        // IMPORTANT: assign the ref synchronously HERE, not inside the setItems
        // updater. React runs updaters during render (after this SSE loop), so
        // a ref written inside the updater is still null when 'done' arrives —
        // that race pushed a SECOND bubble and duplicated every response.
        if (turnAssistantRef.current === null) {
          turnAssistantRef.current = idRef.current++;
        }
        const id = turnAssistantRef.current;
        setItems((prev) => {
          const arr = [...prev];
          const idx = arr.findIndex((it) => it.id === id);
          if (idx >= 0) {
            const cur = arr[idx] as Extract<ChatItem, { kind: 'assistant' | 'json' }>;
            // Clean the WHOLE accumulated text each time — a partial
            // <|tool_call_start|> fragment is stripped once its matching
            // <|tool_call_end|> arrives, so markup never shows as prose.
            arr[idx] = { ...cur, text: clean(cur.text + delta) };
          } else {
            arr.push({ kind: 'assistant', id, text: clean(delta) });
          }
          return arr;
        });
        break;
      }
      case 'done': {
        const finalText = clean(e.final_text ?? '');
        const m = e.model ? shortModel(e.model) : undefined;
        if (!finalText) break;
        const id = turnAssistantRef.current;
        setItems((prev) => {
          const arr = [...prev];
          const idx = id !== null ? arr.findIndex((it) => it.id === id) : -1;
          const kind = looksLikeJson(finalText) ? 'json' : 'assistant';
          if (idx >= 0) {
            const cur = arr[idx] as Extract<ChatItem, { kind: 'assistant' | 'json' }>;
            arr[idx] = { ...cur, text: finalText, model: m, kind } as ChatItem;
          } else {
            arr.push({ kind, id: idRef.current++, text: finalText, model: m } as ChatItem);
          }
          return arr;
        });
        turnAssistantRef.current = null;
        // Reset the live-stream counter for the next run.
        streamStartRef.current = null;
        streamCharsRef.current = 0;
        setLiveTok(null);
        void loadTodos(sessionIdRef.current);
        void refreshSessions();
        break;
      }
      case 'think':
        setItems((prev) => [
          ...prev,
          { kind: 'think', id: idRef.current++, text: truncate(e.text ?? '', 420) },
        ]);
        break;
      case 'think_delta': {
        const delta = e.delta ?? '';
        if (!delta) break;
        // Append to the OPEN think row (the last 'think' item) — streaming
        // reasoning, live, instead of a silent wait.
        setItems((prev) => {
          const arr = [...prev];
          for (let i = arr.length - 1; i >= 0; i--) {
            const it = arr[i];
            if (it.kind === 'think') {
              arr[i] = { ...it, text: truncate(it.text + delta, 420) } as ChatItem;
              break;
            }
          }
          return arr;
        });
        break;
      }
      case 'tool_start': {
        const name = e.name ?? 'tool';
        setItems((prev) => [...prev, { kind: 'bash', id: idRef.current++, command: name }]);
        break;
      }
      case 'tool_result': {
        const out = (e.output ?? '').trim();
        setItems((prev) => {
          const arr = [...prev];
          let idx = -1;
          for (let i = arr.length - 1; i >= 0; i--) {
            if (arr[i].kind === 'bash') {
              idx = i;
              break;
            }
          }
          if (idx >= 0) {
            const cur = arr[idx] as Extract<ChatItem, { kind: 'bash' }>;
            arr[idx] = { ...cur, output: out ? truncate(out, 2000) : undefined };
          }
          return arr;
        });
        void loadTodos(sessionIdRef.current);
        break;
      }
      case 'status':
        setItems((prev) => [...prev, { kind: 'status', id: idRef.current++, text: e.text || 'Deep diving…' }]);
        break;
      default:
        break;
    }
  };

  const handleSend = (message: string) => {
    // CRITICAL: start a fresh turn. If the previous run errored or was aborted,
    // 'done' never fired and turnAssistantRef still points at the OLD
    // assistant bubble — the next text_delta would append into / replace it.
    turnAssistantRef.current = null;
    setItems((prev) => [...prev, { kind: 'user', id: idRef.current++, text: message }]);
    setRunning(true);
    const controller = new AbortController();
    abortRef.current = controller;
    const sid = sessionIdRef.current || 'anon';
    void runAgent({ message, session_id: sid }, onEvent, controller.signal)
      .catch((err) => {
        if ((err as Error).name === 'AbortError') return;
        if (err instanceof ApiError && err.status === 0) setOnline(false);
        setItems((prev) => [
          ...prev,
          { kind: 'assistant', id: idRef.current++, text: `Error: ${(err as Error).message}` },
        ]);
      })
      .finally(() => {
        setRunning(false);
        abortRef.current = null;
      });
  };

  const handleStop = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    setRunning(false);
  };

  const loadTodos = useCallback(async (sid: string) => {
    try {
      setTodos(await getTodos(sid));
    } catch {
      /* offline */
    }
  }, []);

  // Elapsed timer while running.
  useEffect(() => {
    if (!running) {
      setElapsed(0);
      return;
    }
    const start = Date.now();
    const h = setInterval(() => setElapsed(Math.round((Date.now() - start) / 1000)), 1000);
    return () => clearInterval(h);
  }, [running]);

  // Skills count.
  useEffect(() => {
    getSkills()
      .then((s) => setSkills(s.length))
      .catch(() => {});
  }, []);

  const showOffline = !online;
  const showEmpty = online && items.length === 0;

  return (
    <div className="app">
      <SidebarDrawer
        open={sidebarOpen}
        sessions={sessions}
        activeId={sessionIdRef.current}
        onClose={() => setSidebarOpen(false)}
        onNewChat={() => {
          setSidebarOpen(false);
          void newSession();
        }}
        onSelectSession={(id) => void selectSession(id)}
        onOpenSettings={() => {
          setSidebarOpen(false);
          setSettingsOpen(true);
        }}
      />
      <div className="main">
        <TopBar
          onOpenSidebar={() => setSidebarOpen(true)}
          todosCount={todos.length}
          onToggleTodos={() => setTodosCollapsed((c) => !c)}
          todosVisible={!todosCollapsed}
        />

        <div className="stream">
          {showOffline ? (
            <OfflineState onRetry={() => void probe()} />
          ) : (
            <MessageList items={items} running={running} elapsed={elapsed}>
              {showEmpty ? <EmptyState skills={skills} /> : null}
            </MessageList>
          )}
        </div>

        {online && <TodosPanel todos={todos} collapsed={todosCollapsed} />}

        <Composer
          running={running}
          model={model}
          onModelChange={setModel}
          onSend={handleSend}
          onStop={handleStop}
        />

        <StatusBar
          telemetry={telemetry}
          online={online}
          hidden={!settings.telemetry}
          liveTokPerSec={liveTok}
          running={running}
        />
      </div>

      {settingsOpen && (
        <SettingsModal
          settings={settings}
          onSettings={(s) => setSettings(s)}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </div>
  );
}
