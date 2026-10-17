import { Text, Tooltip } from '@mantine/core';
import { glossary, type GlossaryTerm } from '../lib/glossary';

export function Term({ children }: { children: string }) {
  const meaning = glossary[children as GlossaryTerm] ?? children;
  return (
    <Tooltip
      label={meaning}
      multiline
      w={280}
      withArrow
      events={{ hover: true, focus: true, touch: true }}
    >
      <Text
        component="button"
        type="button"
        inherit
        style={{
          color: 'inherit',
          background: 'none',
          border: 0,
          borderBottom: '1px dotted currentColor',
          padding: 0,
          cursor: 'help',
        }}
        aria-label={`${children}: ${meaning}`}
      >
        {children}
      </Text>
    </Tooltip>
  );
}
export function CodeText({ code }: { code: string | null | undefined }) {
  return code ? (
    <Text size="xs" c="dimmed" ff="monospace" style={{ overflowWrap: 'anywhere' }}>
      Technical code: {code}
    </Text>
  ) : null;
}
