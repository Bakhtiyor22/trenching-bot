import { Anchor, Group, Text, Tooltip } from '@mantine/core';
import { Link } from 'react-router';
import { paths } from '../../paths';
import { useVariationBook } from '../automation/useVariationBook';

/**
 * The variations whose sweep produced the run, each by its name as a link to its page (linked by its label), an
 * archived one marked so it never reads as current. "—" with a reason when none did; a plain "—" when the backend does
 * not say (an older one sends no `variations`).
 */
export function RunVariations({ variations }: { variations: string[] | undefined }) {
  const book = useVariationBook();
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
    <Group gap={8} wrap="nowrap">
      {variations.map((label) => {
        const name = book.name(label);
        const archived = book.archived(label);
        return (
          <Group key={label} gap={4} wrap="nowrap">
            <Anchor
              component={Link}
              to={paths.automationVariation(label)}
              ff="monospace"
              size="sm"
              fw={600}
              c={archived ? 'dimmed' : undefined}
              onClick={(e) => e.stopPropagation()}
              aria-label={`Open variation ${name}${archived ? ', archived' : ''}`}
            >
              {name}
            </Anchor>
            {archived && (
              <Text span size="xs" c="dimmed" aria-hidden>
                archived
              </Text>
            )}
          </Group>
        );
      })}
    </Group>
  );
}
