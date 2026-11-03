import {
  Anchor,
  Badge,
  Button,
  Card,
  createTheme,
  Kbd,
  NavLink,
  Paper,
  SegmentedControl,
  Table,
  Tooltip,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core';

/**
 * "Quiet ledger" palette (docs/plans/20261103-frontend-redesign.md, canvas artboard 13). Dark first:
 * near-black ground, slightly lighter panels, hairline borders; colour only where it means something,
 * amber as the one accent. Mantine reads `dark` from light to dark: [0] text, [2] dimmed, [4] borders,
 * [6] panels, [7] page background.
 */
const dark: MantineColorsTuple = [
  '#E7ECEF',
  '#C3CDD5',
  '#93A1AD',
  '#6B7885',
  '#1F2830',
  '#151B21',
  '#10151A',
  '#0A0D10',
  '#070A0C',
  '#050708',
];

/** [5] is the dark-scheme accent, [8] the light-scheme one (dark enough for text on white). */
const amber: MantineColorsTuple = [
  '#FFF8E6',
  '#FDEDC4',
  '#FADF96',
  '#F7D068',
  '#F4C34A',
  '#F2B43C',
  '#D99E2B',
  '#B9841F',
  '#8A6212',
  '#73500E',
];

/*
 * Status hues. Mantine colours text with shade [4] in the dark scheme and shade [7] in the light one,
 * so those two slots hold the validated tokens below; every `c="teal"`, `color="red"` and so on then
 * matches the charts. Validated with the dataviz palette script against both panels.
 */
const teal: MantineColorsTuple = [
  '#E6FBF2',
  '#C3F4DF',
  '#97EBC8',
  '#69E2B0',
  '#3DDC97',
  '#2BC384',
  '#1FA56F',
  '#0C7A54',
  '#0A6446',
  '#064A33',
];

const red: MantineColorsTuple = [
  '#FFECEC',
  '#FFD1D1',
  '#FFB0B0',
  '#FF8C8C',
  '#FF6B6B',
  '#F04F4F',
  '#E04444',
  '#C93030',
  '#A82626',
  '#7F1C1C',
];

const blue: MantineColorsTuple = [
  '#E8F3FF',
  '#CCE4FF',
  '#A6D0FF',
  '#7FBBFF',
  '#5AA9FF',
  '#3F94F0',
  '#2C80E0',
  '#1F6FD1',
  '#1858A6',
  '#11417A',
];

const orange: MantineColorsTuple = [
  '#FFF3E6',
  '#FDE0C2',
  '#FBC992',
  '#F9B46B',
  '#F7A04A',
  '#EB8A2C',
  '#D97416',
  '#A85200',
  '#8A4400',
  '#683300',
];

/** The colour tokens of one scheme; charts and inline accents read these where a Mantine colour name does not reach. */
export interface TerminalTokens {
  ground: string;
  panel: string;
  raised: string;
  line: string;
  lineStrong: string;
  grid: string;
  text: string;
  text2: string;
  dimmed: string;
  accent: string;
  accentInk: string;
  gain: string;
  loss: string;
  info: string;
  /** Rejected scenarios and anything stopped that needs attention: orange, apart from loss red. */
  rejected: string;
  neutral: string;
  /** Price-chart midpoint markers only. */
  violet: string;
  /** Compare's run identities A, B, C (dataviz reference slots 1–3, validated all-pairs). */
  series: readonly [string, string, string];
}

/** Dark scheme tokens, the console's default. */
export const terminal = {
  ground: '#0A0D10',
  panel: '#10151A',
  raised: '#151B21',
  line: '#1F2830',
  lineStrong: '#2A343D',
  grid: '#161D23',
  text: '#E7ECEF',
  text2: '#C3CDD5',
  dimmed: '#93A1AD',
  accent: '#F2B43C',
  accentInk: '#1A1300',
  gain: '#3DDC97',
  loss: '#FF6B6B',
  info: '#5AA9FF',
  rejected: '#F7A04A',
  neutral: '#6B7885',
  violet: '#C792EA',
  series: ['#3987E5', '#D95926', '#199E70'],
} as const satisfies TerminalTokens;

/** Light scheme tokens: the same roles, stepped for a white panel (text colours at least 4.5:1). */
export const terminalLight = {
  ground: '#F5F6F8',
  panel: '#FFFFFF',
  raised: '#F0F2F5',
  line: '#E2E6EB',
  lineStrong: '#CDD3DA',
  grid: '#EEF1F4',
  text: '#11161B',
  text2: '#2F3943',
  dimmed: '#5B6773',
  accent: '#B9841F',
  accentInk: '#1A1300',
  gain: '#0C7A54',
  loss: '#C93030',
  info: '#1F6FD1',
  rejected: '#A85200',
  neutral: '#8A96A3',
  violet: '#8E5BC9',
  series: ['#2A78D6', '#EB6834', '#1BAF7A'],
} as const satisfies TerminalTokens;

export function tokensFor(scheme: 'light' | 'dark'): TerminalTokens {
  return scheme === 'light' ? terminalLight : terminal;
}

export const FONT_MONO = '"JetBrains Mono", ui-monospace, "Cascadia Code", Menlo, Consolas, monospace';

export const theme = createTheme({
  colors: { dark, amber, teal, red, blue, orange },
  primaryColor: 'amber',
  primaryShade: { light: 8, dark: 5 },
  autoContrast: true,
  defaultRadius: 'sm',
  fontFamily: '"IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: FONT_MONO,
  headings: {
    fontWeight: '600',
    sizes: { h1: { fontSize: '1.375rem' }, h2: { fontSize: '1.375rem' }, h3: { fontSize: '0.9375rem' }, h4: { fontSize: '0.875rem' } },
  },
  components: {
    Anchor: Anchor.extend({ defaultProps: { underline: 'hover' } }),
    Badge: Badge.extend({ defaultProps: { radius: 'xl', tt: 'none', fw: 500 } }),
    Button: Button.extend({ defaultProps: { radius: 'md' } }),
    Card: Card.extend({ defaultProps: { withBorder: true, radius: 'md', shadow: 'none' } }),
    Paper: Paper.extend({ defaultProps: { radius: 'md' } }),
    SegmentedControl: SegmentedControl.extend({ defaultProps: { radius: 'md' } }),
    Table: Table.extend({ defaultProps: { highlightOnHover: true, verticalSpacing: 'xs' } }),
    Tooltip: Tooltip.extend({ defaultProps: { color: 'dark.8', withArrow: true } }),
    NavLink: NavLink.extend({ defaultProps: { variant: 'subtle' } }),
    Kbd: Kbd.extend({ defaultProps: { size: 'xs' } }),
  },
});

/** Page ground and readable dimmed ink in the light scheme (Mantine's default grey is under 4.5:1 on white). */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--mantine-color-body': terminalLight.ground,
    '--mantine-color-dimmed': terminalLight.dimmed,
    '--mantine-color-default-border': terminalLight.line,
  },
  dark: {
    '--mantine-color-body': terminal.ground,
  },
});
