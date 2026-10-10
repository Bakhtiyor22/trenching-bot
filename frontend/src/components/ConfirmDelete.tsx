import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useState, type ReactNode } from 'react';
import { ErrorState } from './StateViews';

/**
 * A delete button that opens a confirmation dialog stating what is removed and what is kept. Nothing is sent until the
 * dialog's Delete is clicked; Cancel closes it without a request.
 */
export function ConfirmDelete({
  label,
  title,
  children,
  onConfirm,
  disabled = false,
  compact = false,
}: {
  label: string;
  title: string;
  children: ReactNode;
  onConfirm: () => Promise<unknown>;
  disabled?: boolean;
  compact?: boolean;
}) {
  const [opened, { open, close }] = useDisclosure(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const confirm = async () => {
    setPending(true);
    setError(null);
    try {
      await onConfirm();
      close();
    } catch (cause) {
      setError(cause);
    } finally {
      setPending(false);
    }
  };
  return (
    <>
      <Button color="red" variant={compact ? 'subtle' : 'light'} size={compact ? 'xs' : 'sm'} onClick={open} disabled={disabled}>
        {label}
      </Button>
      <Modal opened={opened} onClose={close} title={title} centered>
        <Stack gap="md">
          <Text size="sm" component="div">{children}</Text>
          {error != null && <ErrorState error={error} title="Delete failed" />}
          <Group justify="flex-end">
            <Button variant="default" onClick={close} disabled={pending}>Cancel</Button>
            <Button color="red" onClick={() => void confirm()} loading={pending}>Delete</Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
