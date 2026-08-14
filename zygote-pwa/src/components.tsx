import { Fragment, useEffect, useRef, useState } from 'react';
import type { Telemetry } from './lib/api';
import { switchModel } from './lib/api';
import {
  IconLogo,
  IconPlus,
  IconDoc,
  IconGear,
  IconContextDoc,
  IconAtom,
  IconCopy,
  IconThumbUp,
  IconThumbDown,
  IconShare,
  IconChecklist,
  IconTerminal,
  IconChevronDown,
  IconStop,
  IconSend,
  IconCloudOff,
} from './icons';

export const LOCAL_MODELS = ['LFM2.5-1.2B'];

export interface SessionMeta {
  session_id: string;
  title: string;
  updated_at: number;
}

// ---------------------------------------------------------------- top bar

/**
 * Minimal top bar: circle logo (left) opens the sidebar drawer; the to-dos
 * icon (right) toggles the todos panel. No title, no tabs, no text.
 */
export function TopBar({
  onOpenSidebar,
  todosCount,
  onToggleTodos,
  todosVisible,
}: {
  onOpenSidebar: () => void;
  todosCount: number;
  onToggleTodos: () => void;
  todosVisible: boolean;
}) {
  return (
    <div className="topbar">
      <button className="logo-circle" onClick={onOpenSidebar} title="Menu">
        <IconLogo size={22} />
      </button>
      <button
        className={`icon-btn ${todosVisible ? 'active' : ''}`}
        onClick={onToggleTodos}
        title="To-dos"
      >
        <IconChecklist size={20} />
        {todosCount > 0 ? <span className="badge">{todosCount}</span> : null}
      </button>
    </div>
  );
}

/** Drawer opened by the logo: brand, new session, sessions, settings. */
export function SidebarDrawer({
  open,
  sessions,
  activeId,
  onClose,
  onNewChat,
  onSelectSession,
  onOpenSettings,
}: {
  open: boolean;
  sessions: SessionMeta[];
  activeId: string;
  onClose: () => void;
  onNewChat: () => void;
  onSelectSession: (id: string) => void;
  onOpenSettings: () => void;
}) {
  if (!open) return null;
  return (
    <>
      <div className="sb-scrim" onClick={onClose} aria-hidden />
      <nav className="sidebar-drawer" aria-label="Navigation">
        <div className="sb-scroll">
          <button className="sb-brand" onClick={onClose}>
            <span className="sb-logo">
              <IconLogo size={24} />
            </span>
            <span className="sb-wordmark">zygote</span>
          </button>

          <button className="sb-new" onClick={onNewChat}>
            <IconPlus size={16} />
            <span>New Session</span>
          </button>

          <div className="sb-sessions">
            <div className="sb-label">Sessions</div>
            {sessions.length === 0 ? (
              <div className="sb-empty">New chats appear here.</div>
            ) : (
              sessions.map((s) => (
                <button
                  key={s.session_id}
                  className={`sb-session ${s.session_id === activeId ? 'active' : ''}`}
                  onClick={() => {
                    onSelectSession(s.session_id);
                    onClose();
                  }}
                  title={s.title || s.session_id}
                >
                  <span className="sb-ic">
                    <IconDoc size={15} />
                  </span>
                  <span className="sb-sid">{s.title || s.session_id.slice(0, 12)}</span>
                </button>
              ))
            )}
          </div>
        </div>

        <div className="sb-foot">
          <button className="sb-footrow" onClick={onOpenSettings}>
            <span className="sb-ic">
              <IconGear size={15} />
            </span>
            Settings
          </button>
        </div>
      </nav>
    </>
  );
}

// ---------------------------------------------------------------- tabs

export type TabId = 'chat';

export function Tabs({ active, onChange }: { active: TabId; onChange: (t: TabId) => void }) {
  return (
    <div className="tabs chat-width" role="tablist">
      <button
        role="tab"
        aria-selected={active === 'chat'}
        className={`tab ${active === 'chat' ? 'active' : ''}`}
        onClick={() => onChange('chat')}
      >
        Chat
      </button>
    </div>
  );
}

// ---------------------------------------------------------------- message types

export type ChatItem =
  | { kind: 'user'; id: number; text: string }
  | { kind: 'context'; id: number; source: string }
  | { kind: 'think'; id: number; text: string }
  | { kind: 'assistant'; id: number; text: string; model?: string }
  | { kind: 'json'; id: number; text: string; model?: string }
  | { kind: 'todo'; id: number; completed: number; total: number; note: string }
  | { kind: 'bash'; id: number; command: string; output?: string }
  | { kind: 'status'; id: number; text: string };

export function copyText(text: string) {
  try {
    void navigator.clipboard?.writeText(text);
  } catch {
    /* clipboard unavailable */
  }
}

/** A metadata/tool row: [16px leading] [title] · [summary fill-truncate]. */
function MetaRow({
  icon,
  title,
  summary,
}: {
  icon: React.ReactNode;
  title: string;
  summary?: string;
}) {
  return (
    <div className="row-meta">
      <span className="leading">{icon}</span>
      <span style={{ flex: 'none' }}>{title}</span>
      {summary ? (
        <>
          <span className="sep" />
          <span
            className="dim"
            style={{ flex: '1 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          >
            {summary}
          </span>
        </>
      ) : null}
    </div>
  );
}

function AssistantActions({ text }: { text: string }) {
  return (
    <div className="action-row">
      <button className="action-btn" title="Copy" onClick={() => copyText(text)}>
        <IconCopy size={16} />
      </button>
      <button className="action-btn" title="Good response">
        <IconThumbUp size={16} />
      </button>
      <button className="action-btn" title="Bad response">
        <IconThumbDown size={16} />
      </button>
      <button className="action-btn" title="Share">
        <IconShare size={16} />
      </button>
    </div>
  );
}

/** Model attribution tag, e.g. "· LFM2.5-230M" (router-aware harness). */
function ModelTag({ model }: { model?: string }) {
  if (!model) return null;
  const short = model.replace(/\.gguf$/, '').replace(/^LFM2\.5-/, 'LFM2.5-');
  return <span className="model-tag">· {short}</span>;
}

/** True when the text is structured output (JSON block) → render as JSON card. */
export function looksLikeJson(s: string): boolean {
  const t = s.trim();
  return (t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'));
}

function ThinkRow({ text, streaming }: { text: string; streaming: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const wasStreaming = useRef(false);
  // Auto-expand while reasoning streams in; auto-collapse when the turn ends.
  useEffect(() => {
    if (streaming) {
      setExpanded(true);
      wasStreaming.current = true;
    } else if (wasStreaming.current) {
      setExpanded(false);
      wasStreaming.current = false;
    }
  }, [streaming]);
  return (
    <div className="msg">
      <button className="think-toggle" onClick={() => setExpanded((v) => !v)}>
        <span className="think-icon">
          <IconAtom size={16} />
        </span>
        <span className="think-label">Think</span>
        <span className="think-chev">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded ? (
        <div className="think-body">{text}</div>
      ) : (
        <div className="think-collapsed">{text}</div>
      )}
    </div>
  );
}

function MessageRow({ item, streaming }: { item: ChatItem; streaming: boolean }) {
  switch (item.kind) {
    case 'user':
      return (
        <div className="msg">
          <div className="user-row">
            <div className="user-stack">
              <div className="bubble-user">{item.text}</div>
            </div>
            <button className="copy-inline" title="Copy" onClick={() => copyText(item.text)}>
              <IconCopy size={16} />
            </button>
          </div>
        </div>
      );
    case 'context':
      return (
        <div className="msg">
          <MetaRow icon={<IconContextDoc size={16} />} title="Context injection" summary={item.source} />
        </div>
      );
    case 'think':
      return <ThinkRow text={item.text} streaming={streaming} />;
    case 'assistant':
      return (
        <div className="msg">
          <div className={`assistant-text${streaming ? ' streaming' : ''}`}>{item.text}</div>
          <div className="assistant-meta">
            <ModelTag model={item.model} />
          </div>
          <AssistantActions text={item.text} />
        </div>
      );
    case 'json':
      return (
        <div className="msg">
          <div className="json-block">
            <div className="json-head">
              <span className="json-label">JSON</span>
              <ModelTag model={item.model} />
            </div>
            <pre className="json-body">{item.text}</pre>
          </div>
          <AssistantActions text={item.text} />
        </div>
      );
    case 'todo':
      return (
        <div className="msg">
          <MetaRow
            icon={<IconChecklist size={16} />}
            title="Update to-do list"
            summary={`${item.completed}/${item.total} completed${item.note ? ` · ${item.note}` : ''}`}
          />
        </div>
      );
    case 'bash':
      return (
        <div className="msg">
          <MetaRow icon={<IconTerminal size={16} />} title="Bash" summary={item.command} />
          {item.output ? <pre className="tool-body">{item.output}</pre> : null}
        </div>
      );
    case 'status':
      return (
        <div className="msg">
          <span className="status-line">
            {item.text}
            {streaming ? <span className="elapsed" /> : null}
          </span>
        </div>
      );
  }
}

export function MessageList({
  items,
  running,
  elapsed,
  children,
}: {
  items: ChatItem[];
  running: boolean;
  elapsed: number;
  children?: React.ReactNode;
}) {
  // Only the LAST status row is the live one — earlier ones are historical.
  let lastStatusIdx = -1;
  for (let i = items.length - 1; i >= 0; i--) {
    if (items[i].kind === 'status') {
      lastStatusIdx = i;
      break;
    }
  }
  return (
    <div className="chat-width">
      <div className="column">
        {items.map((item, i) => {
          if (item.kind === 'status' && i === lastStatusIdx && running) {
            return (
              <div className="msg" key={item.id}>
                <span className="status-line">
                  {item.text}
                  <span className="elapsed">{elapsed}s</span>
                </span>
              </div>
            );
          }
          return (
            <MessageRow
              key={item.id}
              item={item}
              streaming={running && i === items.length - 1 && item.kind === 'assistant'}
            />
          );
        })}
        {children}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- offline / empty

export function OfflineState({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="offline chat-width">
      <div className="offline-icon">
        <IconCloudOff size={30} />
      </div>
      <h3>Harness offline</h3>
      <div className="sub">
        zygote is not reachable at <b>127.0.0.1:8787</b>.
        <br />
        Start the native app, then retry.
      </div>
      <button className="btn-retry" onClick={onRetry}>
        Retry connection
      </button>
    </div>
  );
}

export function EmptyState({ skills }: { skills: number }) {
  return (
    <div className="empty chat-width">
      <div className="sub">
        Agent ready — send a message below.
        {skills > 0 ? ` ${skills} skills loaded.` : ''}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- to-dos

export interface TodoItem {
  content: string;
  status: string;
  priority: string;
  position: number;
}

const TODO_STATUS_LABEL: Record<string, string> = {
  todo: 'Todo',
  'in-progress': 'In progress',
  done: 'Done',
  canceled: 'Canceled',
};

/**
 * To-dos panel above the composer. Visibility is controlled ONLY by the
 * To-dos button in the header (no swipe gestures, no header-click toggle).
 */
export function TodosPanel({
  todos,
  collapsed,
}: {
  todos: TodoItem[];
  collapsed: boolean;
}) {
  const inProgress = todos.filter((t) => t.status === 'in-progress').length;
  const pending = todos.filter((t) => t.status === 'todo').length;
  const total = todos.length;

  if (collapsed) return null;
  return (
    <div className="todos">
      <div className="todos-body">
        <div className="todos-header">
          <span className="lead">
            <IconChecklist size={14} />
          </span>
          <span className="todos-title">To-dos</span>
          <span className="todos-summary">
            {inProgress} in progress · {pending} pending
          </span>
        </div>
        {total > 0 ? (
          <div className="todo-list">
            {todos.map((t) => (
              <div className="todo-item" key={t.position}>
                <span className={`glyph ${t.status === 'done' ? 'done' : ''}`}>
                  {t.status === 'done' ? <IconChecklist size={12} /> : <IconCircle size={10} />}
                </span>
                <span className={t.status === 'done' ? 'todo-done' : ''}>{t.content}</span>
                <span className="todo-status">{TODO_STATUS_LABEL[t.status] ?? t.status}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="todo-empty">The agent will track its plan here.</div>
        )}
      </div>
    </div>
  );
}

function IconCircle({ size = 10 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" fill="none" aria-hidden>
      <circle cx="6" cy="6" r="4.5" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

// ---------------------------------------------------------------- composer

function ModelDropdown({
  open,
  model,
  onSelect,
  onClose,
}: {
  open: boolean;
  model: string;
  onSelect: (m: string) => void;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <div className="menu-wrap">
      <div className="dropdown">
        <div className="dropdown-label">Model</div>
        {LOCAL_MODELS.map((m) => (
          <button key={m} className={`dropdown-opt ${model === m ? 'selected' : ''}`} onClick={() => onSelect(m)}>
            <span>{m}</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="tag-local">local</span>
              {model === m ? <span className="check">✓</span> : null}
            </span>
          </button>
        ))}
      </div>
      <div style={{ position: 'fixed', inset: 0 }} onClick={onClose} aria-hidden />
    </div>
  );
}

export function Composer({
  running,
  model,
  onModelChange,
  onSend,
  onStop,
}: {
  running: boolean;
  model: string;
  onModelChange: (m: string) => void;
  onSend: (msg: string) => void;
  onStop: () => void;
}) {
  const [text, setText] = useState('');
  const [modelOpen, setModelOpen] = useState(false);
  const [switching, setSwitching] = useState(false);

  const submit = () => {
    const msg = text.trim();
    if (!msg || running) return;
    onSend(msg);
    setText('');
  };

  const pickModel = (m: string) => {
    setModelOpen(false);
    if (m === model) return;
    setSwitching(true);
    // Fire the switch; the telemetry probe will confirm the new model.
    void switchModel(m)
      .catch(() => {})
      .finally(() => setSwitching(false));
    onModelChange(m);
  };

  return (
    <div className="composer">
      <textarea
        rows={1}
        placeholder="Message the agent"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
        autoFocus
      />
      <div className="composer-tools">
        <div className="tools">
          <button className="btn-plus" title="Attach">
            <IconPlus size={16} />
          </button>
        </div>

        <div className="trailing">
          <div className="menu-wrap">
            <button className="chip chip-model" onClick={() => setModelOpen((v) => !v)}>
              {switching ? <span className="spin-ring small" /> : null}
              <span className="model-name">{model}</span>
              <span className="chev">
                <IconChevronDown size={12} />
              </span>
            </button>
            <ModelDropdown
              open={modelOpen}
              model={model}
              onSelect={pickModel}
              onClose={() => setModelOpen(false)}
            />
          </div>

          <div className="send-area">
            {running ? (
              <>
                <span className="spin-ring" />
                <button className="btn-round btn-stop" title="Stop" onClick={onStop}>
                  <IconStop size={16} />
                </button>
              </>
            ) : (
              <button className="btn-round btn-send" title="Send" disabled={!text.trim()} onClick={submit}>
                <IconSend size={17} />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- status bar

function fmtDuration(ms: number): string {
  if (ms <= 0) return '—';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Honest status bar. Every number is real and per-session:
 *   model · tok/s (live or last run) · TTFT (last run) · RAM · battery · runs/steps
 * No invented cache-hit %, no server-lifetime aggregates.
 */
export function StatusBar({
  telemetry,
  online,
  hidden,
  liveTokPerSec,
  running,
}: {
  telemetry: Telemetry | null;
  online: boolean;
  hidden: boolean;
  liveTokPerSec?: number | null;
  running: boolean;
}) {
  if (hidden) return null;
  const t = telemetry;
  const model = t?.model ? shortModelName(t.model) : '—';
  // Live in-flight speed beats the last-run number while streaming.
  const tok = running && liveTokPerSec ? liveTokPerSec : t?.tok_per_sec ?? 0;
  const tokText = running
    ? `${tok > 0 ? tok.toFixed(0) : '…'} tok/s`
    : `${tok > 0 ? tok.toFixed(0) : '—'} tok/s`;
  const ttft = t && t.ttft_ms > 0 ? fmtDuration(t.ttft_ms) : '—';
  const ram = t ? `${(t.ram_mb / 1024).toFixed(1)}G` : '—';
  const batt = t && t.battery_pct >= 0 ? `${t.battery_pct}%` : '—';

  const groups = [
    model,
    running ? `⚡ ${tokText}` : tokText,
    `TTFT ${ttft}`,
    `RAM ${ram} · ${batt}`,
  ];

  return (
    <footer className="statusbar">
      {groups.map((g, i) => (
        <Fragment key={i}>
          {i > 0 && (
            <>
              <span className="sep">|</span>{' '}
            </>
          )}
          <span>{g}</span>
        </Fragment>
      ))}
    </footer>
  );
}

/** "LFM2.5-1.2B-Instruct-Q4_0.gguf" → "1.2B" (compact for the bar). */
function shortModelName(f: string): string {
  const m = f.replace(/\.gguf$/, '');
  const b = m.match(/(\d+(?:\.\d+)?B)/i)?.[1];
  return b ? `LFM2.5-${b}` : m;
}

// ---------------------------------------------------------------- settings

export interface SettingsState {
  theme: 'dark' | 'light';
  telemetry: boolean;
}

const SETTINGS_KEY = 'zygote-settings';

export function loadSettings(): SettingsState {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (raw) {
      const o = JSON.parse(raw) as Partial<SettingsState>;
      return { theme: o.theme === 'light' ? 'light' : 'dark', telemetry: o.telemetry !== false };
    }
  } catch {
    /* defaults */
  }
  return { theme: 'dark', telemetry: true };
}

export function saveSettings(s: SettingsState) {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(s));
  } catch {
    /* storage unavailable */
  }
}

/** dsh-style settings modal: centered, section rows, Esc to close. */
export function SettingsModal({
  settings,
  onSettings,
  onClose,
}: {
  settings: SettingsState;
  onSettings: (s: SettingsState) => void;
  onClose: () => void;
}) {
  const [theme, setTheme] = useState(settings.theme);
  const [telemetry, setTelemetry] = useState(settings.telemetry);

  useEffect(() => {
    setTheme(settings.theme);
    setTelemetry(settings.telemetry);
  }, [settings]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const apply = (next: SettingsState) => {
    onSettings(next);
    saveSettings(next);
  };

  return (
    <div className="settings-backdrop" onClick={onClose}>
      <div className="settings-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Settings">
        <div className="settings-head">
          <h2>Settings</h2>
          <button className="settings-x" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <div className="settings-section">
          <div className="settings-section-title">Appearance</div>
          <div className="settings-row">
            <div className="settings-row-label">
              <div className="settings-row-name">Theme</div>
              <div className="settings-row-desc">Dark or light interface.</div>
            </div>
            <div className="seg">
              <button
                className={`seg-btn ${theme === 'dark' ? 'active' : ''}`}
                onClick={() => apply({ ...settings, theme: 'dark' })}
              >
                Dark
              </button>
              <button
                className={`seg-btn ${theme === 'light' ? 'active' : ''}`}
                onClick={() => apply({ ...settings, theme: 'light' })}
              >
                Light
              </button>
            </div>
          </div>
        </div>

        <div className="settings-section">
          <div className="settings-section-title">Interface</div>
          <div className="settings-row">
            <div className="settings-row-label">
              <div className="settings-row-name">Telemetry bar</div>
              <div className="settings-row-desc">Show live tok/s, TTFT and RAM in the footer.</div>
            </div>
            <button
              className={`switch ${telemetry ? 'on' : ''}`}
              role="switch"
              aria-checked={telemetry}
              onClick={() => apply({ ...settings, telemetry: !telemetry })}
            >
              <span className="switch-knob" />
            </button>
          </div>
        </div>

        <div className="settings-section">
          <div className="settings-section-title">Model</div>
          <div className="settings-row">
            <div className="settings-row-label">
              <div className="settings-row-name">Router</div>
              <div className="settings-row-desc">
                Auto picks the fast tier (LFM2.5-230M) for simple turns and the heavy tier
                (LFM2.5-2.6B) for hard ones — all on-device.
              </div>
            </div>
          </div>
        </div>

        <div className="settings-foot">zygote · on-device agent · no cloud</div>
      </div>
    </div>
  );
}
