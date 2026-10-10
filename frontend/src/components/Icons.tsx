import type { ReactNode, SVGProps } from 'react';

/** A few inline stroke icons, so the console needs no icon-library dependency. */
type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Icon({ size = 18, children, ...rest }: IconProps & { children: ReactNode }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const IconDashboard = (p: IconProps) => (
  <Icon {...p}>
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  </Icon>
);

export const IconDatabase = (p: IconProps) => (
  <Icon {...p}>
    <ellipse cx="12" cy="5.5" rx="8" ry="2.5" />
    <path d="M4 5.5v6c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-6" />
    <path d="M4 11.5v6c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-6" />
  </Icon>
);

export const IconPlay = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M10 8.5l5 3.5-5 3.5z" />
  </Icon>
);

export const IconList = (p: IconProps) => (
  <Icon {...p}>
    <path d="M9 6h11M9 12h11M9 18h11" />
    <path d="M4 6h.01M4 12h.01M4 18h.01" />
  </Icon>
);

export const IconSatellite = (p: IconProps) => (
  <Icon {...p}>
    <path d="M5 19a9 9 0 0 1 0-14" />
    <path d="M8.5 15.5a4 4 0 0 1 0-7" />
    <circle cx="13" cy="12" r="1.5" />
    <path d="M15.5 8.5a4 4 0 0 1 0 7" />
    <path d="M19 5a9 9 0 0 1 0 14" />
  </Icon>
);

export const IconCapture = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="8" />
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
  </Icon>
);

export const IconSun = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Icon>
);

export const IconMoon = (p: IconProps) => (
  <Icon {...p}>
    <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" />
  </Icon>
);

export const IconCopy = (p: IconProps) => (
  <Icon {...p}>
    <rect x="8" y="8" width="12" height="12" rx="2" />
    <path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" />
  </Icon>
);

export const IconCheck = (p: IconProps) => (
  <Icon {...p}>
    <path d="M5 12.5l4.5 4.5L19 7.5" />
  </Icon>
);

export const IconX = (p: IconProps) => (
  <Icon {...p}>
    <path d="M6 6l12 12M18 6L6 18" />
  </Icon>
);

export const IconUpload = (p: IconProps) => (
  <Icon {...p}>
    <path d="M4 17v2a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-2" />
    <path d="M12 4v12M7 9l5-5 5 5" />
  </Icon>
);

export const IconDownload = (p: IconProps) => (
  <Icon {...p}>
    <path d="M4 17v2a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-2" />
    <path d="M12 4v12M7 11l5 5 5-5" />
  </Icon>
);

export const IconAlert = (p: IconProps) => (
  <Icon {...p}>
    <path d="M12 3l9.5 17h-19z" />
    <path d="M12 10v4M12 17h.01" />
  </Icon>
);

export const IconInfo = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v5M12 8h.01" />
  </Icon>
);

export const IconFile = (p: IconProps) => (
  <Icon {...p}>
    <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
    <path d="M14 3v5h5" />
  </Icon>
);

export const IconSearch = (p: IconProps) => (
  <Icon {...p}>
    <circle cx="11" cy="11" r="7" />
    <path d="M20 20l-4-4" />
  </Icon>
);

export const IconCompare = (p: IconProps) => (
  <Icon {...p}>
    <path d="M8 3v18M16 3v18" />
    <path d="M3 8h5M16 16h5" />
  </Icon>
);

export const IconPlus = (p: IconProps) => (
  <Icon {...p}>
    <path d="M12 5v14M5 12h14" />
  </Icon>
);

export const IconKeyboard = (p: IconProps) => (
  <Icon {...p}>
    <rect x="2.5" y="6" width="19" height="12" rx="2" />
    <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10" />
  </Icon>
);
