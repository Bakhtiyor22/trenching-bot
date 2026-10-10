import {
  ActionIcon,
  AppShell,
  Box,
  Burger,
  Group,
  Kbd,
  Modal,
  NavLink,
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
import type { ReactNode } from 'react';
import { NavLink as RouterNavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useActuatorHealth, useSolUsdtPrice } from '../api/queries';
import { trimFractionZeros } from '../lib/decimal';
import { paths } from '../paths';
import { terminal } from '../theme';
import { CommandPalette } from './CommandPalette';
import {
  IconCapture,
  IconCompare,
  IconDashboard,
  IconDatabase,
  IconKeyboard,
  IconList,
  IconMoon,
  IconPlay,
  IconSatellite,
  IconSearch,
  IconSun,
} from './Icons';
import { RunWatcher } from './RunWatcher';

const NAV_ITEMS: Array<{ to: string; label: string; icon: ReactNode; end?: boolean }> = [
  { to: paths.dashboard, label: 'Dashboard', icon: <IconDashboard />, end: true },
  { to: paths.runs, label: 'Runs', icon: <IconList />, end: false },
  { to: paths.compare, label: 'Compare', icon: <IconCompare /> },
  { to: paths.datasets, label: 'Datasets', icon: <IconDatabase /> },
  { to: paths.newRun, label: 'New run', icon: <IconPlay /> },
  { to: paths.captures, label: 'Captures', icon: <IconCapture /> },
  { to: paths.solana, label: 'Solana', icon: <IconSatellite /> },
];

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
      <ActionIcon variant="subtle" color="gray" size="lg" onClick={() => setColorScheme(next)} aria-label={`Switch to ${next} mode`}>
        {computed === 'dark' ? <IconSun /> : <IconMoon />}
      </ActionIcon>
    </Tooltip>
  );
}

/** Backend health from `/actuator/health`, with a pulsing dot while it is up. */
export function BackendHealthIndicator() {
  const { data, isError, isPending } = useActuatorHealth();
  const status = isPending ? 'CHECKING' : isError ? 'UNREACHABLE' : (data?.status ?? 'UNKNOWN');
  const up = status === 'UP';
  const color = up ? terminal.gain : status === 'CHECKING' || status === 'UNKNOWN' ? terminal.dimmed : terminal.loss;
  const label = up ? 'Backend up' : status === 'CHECKING' ? 'Checking backend' : `Backend ${status.toLowerCase()}`;
  return (
    <Tooltip label={`Actuator health: ${status}`}>
      <Group gap={8} wrap="nowrap" px="sm" h={36} aria-label={label} visibleFrom="xs" style={{ border: `1px solid var(--mantine-color-default-border)`, borderRadius: 6 }}>
        <span className={up ? 'console-live-dot' : undefined} style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: color }} />
        <Text size="sm">{label}</Text>
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
      <Group gap={8} wrap="nowrap" px="sm" h={36} visibleFrom="md" style={{ border: `1px solid var(--mantine-color-default-border)`, borderRadius: 6 }}>
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
    <UnstyledButton
      onClick={() => spotlight.open()}
      aria-label="Open command palette"
      h={36}
      px="sm"
      style={{ border: `1px solid var(--mantine-color-default-border)`, borderRadius: 6, background: 'var(--mantine-color-default)', flex: '0 1 300px', minWidth: 0 }}
    >
      <Group gap={10} wrap="nowrap">
        <IconSearch size={15} color="var(--mantine-color-dimmed)" />
        <Text size="sm" c="dimmed" style={{ flex: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          Search runs, datasets, mints
        </Text>
        <Group gap={2} wrap="nowrap" visibleFrom="sm">
          <Kbd>Ctrl</Kbd>
          <Kbd>K</Kbd>
        </Group>
      </Group>
    </UnstyledButton>
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
  if (to === paths.runs) return pathname.startsWith(paths.runs) && !pathname.startsWith(paths.newRun);
  if (end) return pathname === to;
  return pathname === to || pathname.startsWith(`${to}/`);
}

function Brand() {
  return (
    <Group gap={10} wrap="nowrap">
      <Box
        w={30}
        h={30}
        ff="monospace"
        fw={700}
        fz={13}
        style={{ borderRadius: 6, background: terminal.accent, color: terminal.ground, display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}
        aria-hidden
      >
        TB
      </Box>
      <Stack gap={0}>
        <Text fw={600} size="sm" style={{ letterSpacing: '0.06em' }}>
          TRENCHING
        </Text>
        <Text size="xs" c="dimmed" ff="monospace">
          paper · read-only
        </Text>
      </Stack>
    </Group>
  );
}

export function AppLayout() {
  const [opened, { toggle, close }] = useDisclosure();
  const [help, { open: openHelp, close: closeHelp }] = useDisclosure();
  const { pathname } = useLocation();
  const navigate = useNavigate();

  useHotkeys([
    ['n', () => void navigate(paths.newRun)],
    ['shift+/', openHelp],
  ]);

  return (
    <AppShell
      header={{ height: 60 }}
      navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="lg"
    >
      <CommandPalette />
      <RunWatcher />
      <ShortcutsModal opened={help} onClose={closeHelp} />

      <AppShell.Header>
        <Group h="100%" px="md" gap="sm" wrap="nowrap">
          <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
          <Box hiddenFrom="sm">
            <Brand />
          </Box>
          <Box style={{ flex: 1 }} />
          <PaletteButton />
          <SolTicker />
          <BackendHealthIndicator />
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm" aria-label="Main navigation">
        <AppShell.Section px={6} pt={4} pb="lg" visibleFrom="sm">
          <Brand />
        </AppShell.Section>
        <AppShell.Section grow>
          <Stack gap={2}>
            {NAV_ITEMS.map((item) => (
              <NavLink
                key={item.to}
                component={RouterNavLink}
                to={item.to}
                end={item.end}
                label={item.label}
                leftSection={item.icon}
                active={isActive(pathname, item.to, item.end)}
                onClick={close}
                style={{ borderRadius: 6 }}
              />
            ))}
          </Stack>
        </AppShell.Section>
        <AppShell.Section>
          <Text size="xs" c="dimmed" p="xs" lh={1.5} style={{ border: '1px solid var(--mantine-color-default-border)', borderRadius: 6 }} aria-label="Paper trading notice">
            Every fill, position and P&amp;L here is simulated from replayed data. Nothing is traded, and Solana is only ever read.
          </Text>
          <Group justify="space-between" mt="xs">
            <UnstyledButton onClick={openHelp} aria-label="Show keyboard shortcuts" p={6}>
              <Group gap={6}>
                <IconKeyboard size={16} color="var(--mantine-color-dimmed)" />
                <Text size="xs" c="dimmed">
                  Shortcuts <Kbd>?</Kbd>
                </Text>
              </Group>
            </UnstyledButton>
            <ColorSchemeToggle />
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
