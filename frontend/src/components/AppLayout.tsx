import {
  ActionIcon,
  AppShell,
  Box,
  Burger,
  Divider,
  Group,
  Kbd,
  Modal,
  NavLink,
  ScrollArea,
  Stack,
  Table,
  Text,
  Tooltip,
  UnstyledButton,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useDisclosure, useHotkeys } from '@mantine/hooks';
import { spotlight } from '@mantine/spotlight';
import { useState, type ReactNode } from 'react';
import { NavLink as RouterNavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useActuatorHealth, useSolUsdtPrice } from '../api/queries';
import { trimFractionZeros } from '../lib/decimal';
import { paths } from '../paths';
import { terminal } from '../theme';
import { CommandPalette } from './CommandPalette';
import {
  IconAutomation,
  IconCapture,
  IconCompare,
  IconDashboard,
  IconDatabase,
  IconGauge,
  IconKeyboard,
  IconMoon,
  IconPlay,
  IconRuns,
  IconSatellite,
  IconSearch,
  IconSidebar,
  IconSun,
} from './Icons';
import { RunWatcher } from './RunWatcher';

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
}

/** The sidebar, grouped by what the user is doing. Automation's views live in its page tabs. */
const NAV_GROUPS: Array<{ label: string | null; items: NavItem[] }> = [
  { label: null, items: [{ to: paths.dashboard, label: 'Dashboard', icon: <IconDashboard />, end: true }] },
  {
    label: 'Research',
    items: [
      { to: paths.runs, label: 'Runs', icon: <IconRuns /> },
      { to: paths.newRun, label: 'New run', icon: <IconPlay /> },
      { to: paths.compare, label: 'Compare', icon: <IconCompare /> },
    ],
  },
  {
    label: 'Data',
    items: [
      { to: paths.captures, label: 'Captures', icon: <IconCapture /> },
      { to: paths.datasets, label: 'Datasets', icon: <IconDatabase /> },
      { to: paths.automation, label: 'Automation', icon: <IconAutomation /> },
    ],
  },
  {
    label: 'System',
    items: [
      { to: paths.usage, label: 'API usage', icon: <IconGauge /> },
      { to: paths.solana, label: 'Solana', icon: <IconSatellite /> },
    ],
  },
];

/** Whether the sidebar is collapsed to its icon rail, remembered per browser (a storage failure only forgets it). */
const RAIL_KEY = 'console.nav.rail.v1';

function readRail(): boolean {
  try {
    return window.localStorage.getItem(RAIL_KEY) === '1';
  } catch {
    return false;
  }
}

function writeRail(rail: boolean): void {
  try {
    window.localStorage.setItem(RAIL_KEY, rail ? '1' : '0');
  } catch {
    // Not remembered; the toggle still works for this page view.
  }
}

/** Every keyboard shortcut, shown by "?" and in the sidebar. Page-level ones are handled by their page. */
const SHORTCUTS: Array<{ keys: string[]; action: string }> = [
  { keys: ['Ctrl', 'K'], action: 'Command palette: jump to any run, dataset or page' },
  { keys: ['/'], action: 'Command palette' },
  { keys: ['N'], action: 'Start a new run' },
  { keys: ['?'], action: 'Show these shortcuts' },
  { keys: ['F'], action: 'Runs: focus the filter' },
  { keys: ['C'], action: 'Runs: compare the selected runs' },
  { keys: ['J', 'K'], action: 'Run detail: next / previous cycle' },
  { keys: ['U'], action: 'Run detail: switch SOL / USDT' },
];

function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('dark', { getInitialValueInEffect: true });
  const next = computed === 'dark' ? 'light' : 'dark';
  return (
    <Tooltip label={`Switch to ${next} mode`}>
      <ActionIcon
        variant="subtle"
        color="gray"
        size="lg"
        onClick={() => setColorScheme(next)}
        aria-label={`Switch to ${next} mode`}
      >
        {computed === 'dark' ? <IconSun /> : <IconMoon />}
      </ActionIcon>
    </Tooltip>
  );
}

const HEADER_PILL = {
  border: '1px solid var(--mantine-color-default-border)',
  borderRadius: 8,
} as const;

/** Backend health from `/actuator/health`, with a pulsing dot while it is up. */
export function BackendHealthIndicator() {
  const { data, isError, isPending } = useActuatorHealth();
  const status = isPending ? 'CHECKING' : isError ? 'UNREACHABLE' : (data?.status ?? 'UNKNOWN');
  const up = status === 'UP';
  const color = up
    ? 'var(--console-gain)'
    : status === 'CHECKING' || status === 'UNKNOWN'
      ? 'var(--mantine-color-dimmed)'
      : 'var(--console-loss)';
  const label = up
    ? 'Backend up'
    : status === 'CHECKING'
      ? 'Checking backend'
      : `Backend ${status.toLowerCase().replace(/_/g, ' ')}`;
  const dot = (
    <span
      className={up ? 'console-live-dot' : undefined}
      style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: color }}
    />
  );
  return (
    <Tooltip label={`Actuator health: ${status}`}>
      <Group gap={8} wrap="nowrap" px="sm" h={36} aria-label={label} role="status" style={HEADER_PILL}>
        {dot}
        <Text size="sm" visibleFrom="xs">
          {label}
        </Text>
      </Group>
    </Tooltip>
  );
}

/** Live SOL/USDT from the backend's cached Binance ticker; hidden when no rate is available. */
function SolTicker() {
  const price = useSolUsdtPrice(true);
  const rate = price.data?.rate;
  if (!rate) return null;
  return (
    <Tooltip label="Binance SOLUSDT through the backend, refreshed every 30 seconds">
      <Group gap={8} wrap="nowrap" px="sm" h={36} visibleFrom="md" style={HEADER_PILL}>
        <Text size="sm" c="dimmed" ff="monospace">
          SOL/USDT
        </Text>
        <Text size="sm" ff="monospace" fw={500}>
          {trimFractionZeros(rate)}
        </Text>
      </Group>
    </Tooltip>
  );
}

function PaletteButton() {
  return (
    <>
      <ActionIcon
        hiddenFrom="xs"
        variant="default"
        size={36}
        radius="md"
        onClick={() => spotlight.open()}
        aria-label="Open command palette"
      >
        <IconSearch size={16} />
      </ActionIcon>
      <UnstyledButton
        visibleFrom="xs"
        onClick={() => spotlight.open()}
        aria-label="Open command palette"
        h={36}
        px="sm"
        style={{ ...HEADER_PILL, background: 'var(--console-panel)', flex: '0 1 300px', minWidth: 0 }}
      >
        <Group gap={10} wrap="nowrap">
          <IconSearch size={15} color="var(--mantine-color-dimmed)" />
          <Text
            size="sm"
            c="dimmed"
            style={{ flex: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
          >
            Search runs, datasets, mints
          </Text>
          <Group gap={2} wrap="nowrap" visibleFrom="sm">
            <Kbd>Ctrl</Kbd>
            <Kbd>K</Kbd>
          </Group>
        </Group>
      </UnstyledButton>
    </>
  );
}

function ShortcutsModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  return (
    <Modal opened={opened} onClose={onClose} title="Keyboard shortcuts" size="lg">
      <Table withRowBorders={false} highlightOnHover={false}>
        <Table.Tbody>
          {SHORTCUTS.map((s) => (
            <Table.Tr key={s.action}>
              <Table.Td w={120}>
                <Group gap={4} wrap="nowrap">
                  {s.keys.map((k) => (
                    <Kbd key={k}>{k}</Kbd>
                  ))}
                </Group>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{s.action}</Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Text size="xs" c="dimmed" mt="sm">
        Single-key shortcuts are ignored while you type in a field.
      </Text>
    </Modal>
  );
}

/** Whether a nav item should be highlighted: "Runs" stays active on run detail, not on "New run". */
function isActive(pathname: string, to: string, end?: boolean): boolean {
  if (to === paths.runs)
    return pathname.startsWith(paths.runs) && !pathname.startsWith(paths.newRun);
  if (end) return pathname === to;
  return pathname === to || pathname.startsWith(`${to}/`);
}

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <Group gap={10} wrap="nowrap">
      <Box
        w={28}
        h={28}
        ff="monospace"
        fw={700}
        fz={12}
        style={{
          borderRadius: 7,
          background: terminal.accent,
          color: terminal.accentInk,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flex: 'none',
        }}
        aria-hidden
      >
        TB
      </Box>
      {!compact && (
        <Stack gap={0}>
          <Text fw={600} size="sm" style={{ letterSpacing: '0.06em' }}>
            TRENCHING
          </Text>
          <Text size="xs" c="dimmed">
            paper · read-only
          </Text>
        </Stack>
      )}
    </Group>
  );
}

function NavItemLink({
  item,
  rail,
  active,
  onClick,
}: {
  item: NavItem;
  rail: boolean;
  active: boolean;
  onClick: () => void;
}) {
  const link = (
    <NavLink
      component={RouterNavLink}
      to={item.to}
      end={item.end}
      label={rail ? undefined : item.label}
      aria-label={rail ? item.label : undefined}
      leftSection={item.icon}
      active={active}
      onClick={onClick}
      className="console-nav-link"
      data-rail={rail || undefined}
    />
  );
  return rail ? (
    <Tooltip label={item.label} position="right">
      {link}
    </Tooltip>
  ) : (
    link
  );
}

export function AppLayout() {
  const [opened, { toggle, close }] = useDisclosure();
  const [help, { open: openHelp, close: closeHelp }] = useDisclosure();
  const [railPreference, setRailPreference] = useState(readRail);
  const { pathname } = useLocation();
  // The full chart page wants the width, so it shows the rail; the other pages follow the user's choice.
  const chartPage = /^\/runs\/[^/]+\/chart$/.test(pathname);
  const rail = chartPage || railPreference;
  const navigate = useNavigate();

  useHotkeys([
    ['n', () => void navigate(paths.newRun)],
    ['shift+/', openHelp],
  ]);

  const toggleRail = () => {
    const next = !rail;
    setRailPreference(next);
    writeRail(next);
  };

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: rail ? 68 : 224, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="lg"
    >
      <CommandPalette />
      <RunWatcher />
      <ShortcutsModal opened={help} onClose={closeHelp} />

      <AppShell.Header>
        <Group h="100%" px="md" gap="sm" wrap="nowrap">
          <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
          <Box hiddenFrom="sm">
            <Brand compact />
          </Box>
          <Box visibleFrom="sm">
            <Brand />
          </Box>
          <Box style={{ flex: 1 }} />
          <PaletteButton />
          <SolTicker />
          <BackendHealthIndicator />
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p={rail ? 12 : 'sm'} aria-label="Main navigation">
        <AppShell.Section grow component={ScrollArea} type="never">
          <Stack gap={2}>
            {NAV_GROUPS.map((group, index) => (
              <Stack key={group.label ?? index} gap={2}>
                {group.label &&
                  (rail ? (
                    <Divider my={6} />
                  ) : (
                    <Text
                      c="dimmed"
                      fw={600}
                      px={10}
                      pt="sm"
                      pb={2}
                      style={{ fontSize: 11, letterSpacing: '0.06em', textTransform: 'uppercase' }}
                    >
                      {group.label}
                    </Text>
                  ))}
                {group.items.map((item) => (
                  <NavItemLink
                    key={item.to}
                    item={item}
                    rail={rail}
                    active={isActive(pathname, item.to, item.end)}
                    onClick={close}
                  />
                ))}
              </Stack>
            ))}
          </Stack>
        </AppShell.Section>
        <AppShell.Section>
          {!rail && (
            <Text
              size="xs"
              c="dimmed"
              p="xs"
              lh={1.5}
              style={{ background: 'var(--console-raised)', borderRadius: 8 }}
              aria-label="Paper trading notice"
            >
              Every fill, position and P&amp;L here is simulated from replayed data. Nothing is traded, and Solana
              is only ever read.
            </Text>
          )}
          <Group justify={rail ? 'center' : 'space-between'} mt="xs" gap={4}>
            {!rail && (
              <UnstyledButton onClick={openHelp} aria-label="Show keyboard shortcuts" p={6}>
                <Group gap={6}>
                  <IconKeyboard size={16} color="var(--mantine-color-dimmed)" />
                  <Text size="xs" c="dimmed">
                    Shortcuts <Kbd>?</Kbd>
                  </Text>
                </Group>
              </UnstyledButton>
            )}
            <Stack gap={2} style={{ flexDirection: rail ? 'column' : 'row' }}>
              <ColorSchemeToggle />
              {!chartPage && (
                <Box visibleFrom="sm">
                  <Tooltip label={rail ? 'Expand the sidebar' : 'Collapse the sidebar'}>
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      size="lg"
                      onClick={toggleRail}
                      aria-label={rail ? 'Expand the sidebar' : 'Collapse the sidebar'}
                    >
                      <IconSidebar />
                    </ActionIcon>
                  </Tooltip>
                </Box>
              )}
            </Stack>
          </Group>
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main>
        <Box maw={1600} mx="auto">
          <Outlet />
        </Box>
      </AppShell.Main>
    </AppShell>
  );
}
