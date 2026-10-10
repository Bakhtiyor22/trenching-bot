import {
  Card,
  createTheme,
  Kbd,
  NavLink,
  Paper,
  Table,
  Tooltip,
  type MantineColorsTuple,
} from '@mantine/core';

/**
 * Trading-terminal palette (see the "Trenching Console Redesign" canvas). Dark first: near-black
 * ground, slightly lighter panels, hairline borders, amber as the one accent. Mantine reads `dark`
 * from light to dark: [0] text, [2] dimmed, [4] borders, [6] panels, [7] page background.
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

const amber: MantineColorsTuple = [
  '#FFF8E6',
  '#FDEDC4',
  '#FADF96',
  '#F7D068',
  '#F4C34A',
  '#F2B43C',
  '#D99E2B',
  '#B9841F',
  '#966A16',
  '#73500E',
];

/** Raw colors for charts and inline accents, where a Mantine color name does not reach. */
export const terminal = {
  ground: '#0A0D10',
  panel: '#10151A',
  raised: '#151B21',
  line: '#1F2830',
  text: '#E7ECEF',
  dimmed: '#93A1AD',
  accent: '#F2B43C',
  gain: '#3DDC97',
  loss: '#FF6B6B',
  info: '#5AA9FF',
  violet: '#C792EA',
  neutral: '#2A343D',
  /** Rejected scenarios: orange, apart from loss red. */
  rejected: '#F7A04A',
} as const;

export const FONT_MONO = '"JetBrains Mono", ui-monospace, "Cascadia Code", Menlo, Consolas, monospace';

export const theme = createTheme({
  colors: { dark, amber },
  primaryColor: 'amber',
  primaryShade: { light: 7, dark: 5 },
  autoContrast: true,
  defaultRadius: 'sm',
  fontFamily: '"IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: FONT_MONO,
  headings: { fontWeight: '600', sizes: { h2: { fontSize: '1.25rem' }, h3: { fontSize: '1.05rem' }, h4: { fontSize: '0.95rem' } } },
  components: {
    Card: Card.extend({ defaultProps: { withBorder: true, radius: 'md', shadow: 'none' } }),
    Paper: Paper.extend({ defaultProps: { radius: 'md' } }),
    Table: Table.extend({ defaultProps: { highlightOnHover: true, verticalSpacing: 'xs' } }),
    Tooltip: Tooltip.extend({ defaultProps: { color: 'dark.8', withArrow: true } }),
    NavLink: NavLink.extend({ defaultProps: { variant: 'light' } }),
    Kbd: Kbd.extend({ defaultProps: { size: 'xs' } }),
  },
});
