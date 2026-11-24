import { Anchor, Group, Text, Tooltip } from '@mantine/core';
import { Link } from 'react-router';
import { paths } from '../../paths';

/**
 * The variations whose sweep produced the run, each a link to its page. "—" with a reason when none did; a plain "—"
 * when the backend does not say (an older one sends no `variations`).
 */
export function RunVariations({ variations }: { variations: string[] | undefined }) {
  if (variations === undefined)
    return (
      <Text size="sm" c="dimmed">
        —
      </Text>
    );
  if (variations.length === 0)
    return (
      <Tooltip label="Not run by a sweep variation" withArrow>
        <Text span size="sm" c="dimmed">
          —
        </Text>
      </Tooltip>
    );
  return (
    <Group gap={6} wrap="nowrap">
      {variations.map((label) => (
        <Anchor
          key={label}
          component={Link}
          to={paths.automationVariation(label)}
          ff="monospace"
          size="sm"
          fw={600}
          onClick={(e) => e.stopPropagation()}
          aria-label={`Open variation ${label}`}
        >
          {label}
        </Anchor>
      ))}
    </Group>
  );
}
