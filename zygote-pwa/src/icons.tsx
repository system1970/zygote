// Minimal inline SVG icon set. stroke=currentColor, viewBox 0 0 24 24.
interface P {
  size?: number;
  className?: string;
}

function base(size: number, filled = false) {
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: filled ? 'currentColor' : 'none',
    stroke: filled ? 'none' : 'currentColor',
    strokeWidth: 1.7,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  };
}

export const IconLogo = ({ size = 22, className }: P) => (
  <svg {...base(size, true)} className={className}>
    <path d="M12 3.5a7.2 7.2 0 1 0 0 14.4 7.2 7.2 0 0 0 0-14.4Zm0 2.2a5 5 0 1 1 0 10 5 5 0 0 1 0-10Z" />
    <path d="M15.7 11.2c1 .6 1.7 1.3 2.2 2.1.6 1 .5 2.1-.1 2.9-.8-1.1-.9-2.2-.3-3.2.5-.9.1-1.5-.6-2.1Z" />
  </svg>
);

export const IconPlus = ({ size = 18, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M12 5v14M5 12h14" />
  </svg>
);

export const IconDoc = ({ size = 18, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M14 3H7a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V8l-4-5Z" />
    <path d="M14 3v5h5M9.5 13h5M9.5 16.5h5" />
  </svg>
);

export const IconSearch = ({ size = 18, className }: P) => (
  <svg {...base(size)} className={className}>
    <circle cx="11" cy="11" r="6" />
    <path d="m20 20-3.4-3.4" />
  </svg>
);

export const IconGear = ({ size = 18, className }: P) => (
  <svg {...base(size)} className={className}>
    <path
      d="M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4Z"
      fill="currentColor"
      stroke="none"
    />
    <path
      d="M19.4 13a7.4 7.4 0 0 0 .1-1 7.4 7.4 0 0 0-.1-1l2-1.6-2-3.4-2.4 1a7.5 7.5 0 0 0-1.7-1L15 3.5h-4l-.3 2.5a7.5 7.5 0 0 0-1.7 1l-2.4-1-2 3.4 2 1.6a7.4 7.4 0 0 0 0 2l-2 1.6 2 3.4 2.4-1a7.5 7.5 0 0 0 1.7 1l.3 2.5h4l.3-2.5a7.5 7.5 0 0 0 1.7-1l2.4 1 2-3.4-2-1.6Z"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinejoin="round"
    />
  </svg>
);

export const IconNodes = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <circle cx="6" cy="6" r="2.2" />
    <circle cx="18" cy="6" r="2.2" />
    <circle cx="12" cy="18" r="2.2" />
    <path d="M7.6 7.2 10.8 16M16.4 7.2 13.2 16M8.2 6h7.6" />
  </svg>
);

export const IconDownload = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M12 3v11M7 10l5 5 5-5M5 20h14" />
  </svg>
);

export const IconContextDoc = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M6 3h8l4 4v14H6z" />
    <path d="M14 3v4h4M9.5 12h5M9.5 15.5h5" />
  </svg>
);

export const IconAtom = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <circle cx="12" cy="12" r="2.1" />
    <ellipse cx="12" cy="12" rx="8.5" ry="3.4" />
    <ellipse cx="12" cy="12" rx="3.4" ry="8.5" />
  </svg>
);

export const IconCopy = ({ size = 16, className }: P) => (
  <svg {...base(size)} className={className}>
    <rect x="9" y="9" width="11" height="11" rx="2" />
    <path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" />
  </svg>
);

export const IconThumbUp = ({ size = 16, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M7 10v10M7 10l2.6-5.4A2.6 2.6 0 0 1 12 7.3v3.2h4.6a2 2 0 0 1 2 2.3l-1 5A2 2 0 0 1 15.6 20H7" />
  </svg>
);

export const IconThumbDown = ({ size = 16, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M17 14V4M17 14l-2.6 5.4A2.6 2.6 0 0 1 12 16.7v-3.2H7.4a2 2 0 0 1-2-2.3l1-5A2 2 0 0 1 8.4 4H17" />
  </svg>
);

export const IconShare = ({ size = 16, className }: P) => (
  <svg {...base(size)} className={className}>
    <circle cx="6" cy="12" r="2.4" />
    <circle cx="18" cy="5.5" r="2.4" />
    <circle cx="18" cy="18.5" r="2.4" />
    <path d="m8.3 10.8 7.4-3.6M8.3 13.2l7.4 3.6" />
  </svg>
);

export const IconChecklist = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <rect x="3" y="4" width="4" height="4" rx="1" />
    <path d="M4.6 6l.9 1 1.5-1.6M11 6h8M11 12h8M11 18h8" />
    <rect x="3" y="10" width="4" height="4" rx="1" />
    <path d="M4.6 12l.9 1 1.5-1.6" />
    <rect x="3" y="16" width="4" height="4" rx="1" />
    <path d="M4.6 18l.9 1 1.5-1.6" />
  </svg>
);

export const IconTerminal = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="m7 9 3 3-3 3M13 15h4" />
  </svg>
);

export const IconChevronDown = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="m6 9 6 6 6-6" />
  </svg>
);

export const IconChevronUp = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="m6 15 6-6 6 6" />
  </svg>
);

export const IconShield = ({ size = 14, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6l-7-3Z" />
  </svg>
);

export const IconStop = ({ size = 18, className }: P) => (
  <svg {...base(size, true)} className={className}>
    <rect x="7" y="7" width="10" height="10" rx="2" />
  </svg>
);

export const IconSend = ({ size = 18, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="m4 12 15-8-5 16-3-6-7-2Z" />
  </svg>
);

export const IconBulb = ({ size = 13, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M9 18h6M10.2 21h3.6M12 3a6 6 0 0 0-3.5 10.9c.8.6 1.5 1.6 1.5 2.1h4c0-.5.7-1.5 1.5-2.1A6 6 0 0 0 12 3Z" />
  </svg>
);

export const IconCpu = ({ size = 13, className }: P) => (
  <svg {...base(size)} className={className}>
    <rect x="6" y="6" width="12" height="12" rx="2" />
    <rect x="10" y="10" width="4" height="4" />
    <path d="M9 2.5V6M15 2.5V6M9 18v3.5M15 18v3.5M2.5 9H6M2.5 15H6M18 9h3.5M18 15h3.5" />
  </svg>
);

export const IconCloudOff = ({ size = 16, className }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M6.5 8.5A4.6 4.6 0 0 0 7 17.6h9.5a3.6 3.6 0 0 0 .7-7.1A6 6 0 0 0 6.5 8.5Z" />
    <path d="M3 3l18 18" />
  </svg>
);
