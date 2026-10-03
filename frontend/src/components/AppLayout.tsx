import {
  ActionIcon,
  Alert,
  AppShell,
  Badge,
  Burger,
  Group,
  NavLink,
  Text,
  Title,
  Tooltip,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import type { ReactNode } from 'react';
import { NavLink as RouterNavLink, Outlet, useLocation } from 'react-router';
import { useActuatorHealth } from '../api/queries';
import { paths } from '../paths';
import {
  IconDashboard,
  IconDatabase,
  IconList,
  IconMoon,
  IconPlay,
  IconSatellite,
  IconSun,
} from './Icons';

const NAV_ITEMS: Array<{ to: string; label: string; icon: ReactNode; end?: boolean }> = [
  { to: paths.dashboard, label: 'Dashboard', icon: <IconDashboard />, end: true },
  { to: paths.datasets, label: 'Datasets', icon: <IconDatabase /> },
  { to: paths.newRun, label: 'New run', icon: <IconPlay /> },
  { to: paths.runs, label: 'Runs', icon: <IconList />, end: false },
  { to: paths.solana, label: 'Solana', icon: <IconSatellite /> },
];

function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light', { getInitialValueInEffect: true });
  const next = computed === 'dark' ? 'light' : 'dark';
  return (
    <Tooltip label={`Switch to ${next} mode`} withArrow>
      <ActionIcon
        variant="default"
        size="lg"
        onClick={() => setColorScheme(next)}
        aria-label={`Switch to ${next} mode`}
      >
        {computed === 'dark' ? <IconSun /> : <IconMoon />}
      </ActionIcon>
    </Tooltip>
  );
}

/** Small backend health dot driven by `/actuator/health`. */
export function BackendHealthIndicator() {
  const { data, isError, isPending } = useActuatorHealth();
  const status = isPending ? 'CHECKING' : isError ? 'UNREACHABLE' : (data?.status ?? 'UNKNOWN');
  const color =
    status === 'UP' ? 'teal' : status === 'CHECKING' || status === 'UNKNOWN' ? 'gray' : 'red';
  const label = status === 'UP' ? 'Backend up' : status === 'CHECKING' ? 'Checking backend' : `Backend ${status.toLowerCase()}`;
  return (
    <Tooltip label={`Actuator health: ${status}`} withArrow>
      <Badge
        variant="dot"
        color={color}
        size="lg"
        radius="sm"
        aria-label={label}
        visibleFrom="xs"
      >
        {label}
      </Badge>
    </Tooltip>
  );
}

/** Whether a nav item should be highlighted: "Runs" stays active on run detail, not on "New run". */
function isActive(pathname: string, to: string, end?: boolean): boolean {
  if (to === paths.runs) return pathname.startsWith(paths.runs) && !pathname.startsWith(paths.newRun);
  if (end) return pathname === to;
  return pathname === to || pathname.startsWith(`${to}/`);
}

export function AppLayout() {
  const [opened, { toggle, close }] = useDisclosure();
  const { pathname } = useLocation();
  return (
    <AppShell
      header={{ height: 60 }}
      navbar={{ width: 230, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="lg"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger
              opened={opened}
              onClick={toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label="Toggle navigation"
            />
            <Title order={1} size="h4" style={{ whiteSpace: 'nowrap' }}>
              Trenching Bot Console
            </Title>
            <Badge variant="light" color="grape" visibleFrom="md">
              Paper trading
            </Badge>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <BackendHealthIndicator />
            <ColorSchemeToggle />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm" aria-label="Main navigation">
        <AppShell.Section grow>
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
              style={{ borderRadius: 'var(--mantine-radius-sm)' }}
            />
          ))}
        </AppShell.Section>
        <AppShell.Section>
          <Text size="xs" c="dimmed" p="xs">
            Research console for replayed paper trades. Read-only toward Solana: it never signs or
            sends a transaction.
          </Text>
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main>
        <Alert
          variant="light"
          color="grape"
          mb="lg"
          py={8}
          aria-label="Paper trading notice"
        >
          <Text size="sm">
            <b>Paper-trading research console.</b> Every fill, position and P&amp;L here is simulated
            from replayed data. Nothing is traded, and Solana is only ever read.
          </Text>
        </Alert>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
