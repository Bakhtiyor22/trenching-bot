import { Alert, Button, Group, Stack, Text, TextInput } from '@mantine/core';
import { Dropzone, type FileRejection } from '@mantine/dropzone';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { Link } from 'react-router';
import { errorMessage } from '../../api/client';
import { useImportSample, useUploadDataset } from '../../api/queries';
import type { DatasetRegistration } from '../../api/types';
import { IconAlert, IconFile, IconUpload, IconX } from '../../components/Icons';
import { formatBytes } from '../../lib/format';
import { paths } from '../../paths';
import { MAX_UPLOAD_BYTES, validateDatasetFile } from './datasetFile';

function RegistrationNotice({ result }: { result: DatasetRegistration }) {
  const link = (
    <Text span component={Link} to={paths.dataset(result.dataset.id)} c="blue" td="underline">
      {result.dataset.name}
    </Text>
  );
  return result.created ? (
    <Alert color="teal" variant="light" title="Dataset stored">
      {link} was validated and stored.
    </Alert>
  ) : (
    <Alert color="blue" variant="light" title="Dataset already existed">
      This exact content is already stored as {link}. Datasets are identified by their checksum, so
      nothing new was created and the existing name was kept.
    </Alert>
  );
}

export function DatasetUpload() {
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const [result, setResult] = useState<DatasetRegistration | null>(null);
  const upload = useUploadDataset();
  const sample = useImportSample();

  const choose = (files: File[]) => {
    const picked = files[0];
    if (!picked) return;
    upload.reset();
    setResult(null);
    const problem = validateDatasetFile(picked);
    setClientError(problem);
    setFile(problem ? null : picked);
  };

  const reject = (rejections: FileRejection[]) => {
    const first = rejections[0];
    upload.reset();
    setResult(null);
    setFile(null);
    setClientError(first ? (validateDatasetFile(first.file) ?? 'This file cannot be uploaded.') : null);
  };

  const submit = () => {
    if (!file) return;
    upload.mutate(
      { file, name: name.trim() || undefined },
      {
        onSuccess: (registration) => {
          setResult(registration);
          setFile(null);
          setName('');
          notifications.show({
            color: registration.created ? 'teal' : 'blue',
            title: registration.created ? 'Dataset uploaded' : 'Dataset already existed',
            message: registration.dataset.name,
          });
        },
      },
    );
  };

  const importSample = () => {
    upload.reset();
    sample.mutate(undefined, {
      onSuccess: (registration) => {
        setResult(registration);
        notifications.show({
          color: registration.created ? 'teal' : 'blue',
          title: registration.created ? 'Sample imported' : 'Sample already imported',
          message: registration.dataset.name,
        });
      },
    });
  };

  return (
    <Stack gap="md">
      <Dropzone
        onDrop={choose}
        onReject={reject}
        maxSize={MAX_UPLOAD_BYTES}
        maxFiles={1}
        multiple={false}
        accept={{ 'application/json': ['.json'] }}
        loading={upload.isPending}
        aria-label="Drop a replay dataset JSON file here, or press Enter to choose one"
      >
        <Group justify="center" gap="lg" mih={110} style={{ pointerEvents: 'none' }}>
          <Dropzone.Accept>
            <IconUpload size={40} color="var(--mantine-color-blue-6)" />
          </Dropzone.Accept>
          <Dropzone.Reject>
            <IconX size={40} color="var(--mantine-color-red-6)" />
          </Dropzone.Reject>
          <Dropzone.Idle>
            <IconFile size={40} color="var(--mantine-color-dimmed)" />
          </Dropzone.Idle>
          <div>
            <Text size="md" fw={600}>
              Drag a replay dataset here or click to choose
            </Text>
            <Text size="sm" c="dimmed">
              Schema v1 JSON, up to 20 MB. The backend validates it fully, including the checksum.
            </Text>
          </div>
        </Group>
      </Dropzone>

      {clientError && (
        <Alert color="red" variant="light" icon={<IconAlert />} title="File not accepted" role="alert">
          {clientError}
        </Alert>
      )}

      {file && (
        <Group align="flex-end" gap="sm">
          <Stack gap={2} style={{ flex: '1 1 220px' }}>
            <Text size="sm" fw={500}>
              Selected file
            </Text>
            <Text size="sm" ff="monospace">
              {file.name} · {formatBytes(file.size)}
            </Text>
          </Stack>
          <TextInput
            label="Name (optional)"
            placeholder={file.name.replace(/\.json$/i, '')}
            value={name}
            onChange={(event) => setName(event.currentTarget.value)}
            maxLength={128}
            style={{ flex: '1 1 220px' }}
          />
          <Button onClick={submit} loading={upload.isPending} leftSection={<IconUpload size={16} />}>
            Upload and validate
          </Button>
        </Group>
      )}

      {upload.isError && (
        <Alert color="red" variant="light" icon={<IconAlert />} title="Upload rejected" role="alert">
          {errorMessage(upload.error)}
        </Alert>
      )}
      {sample.isError && (
        <Alert color="red" variant="light" icon={<IconAlert />} title="Sample import failed" role="alert">
          {errorMessage(sample.error)}
        </Alert>
      )}
      {result && <RegistrationNotice result={result} />}

      <Group justify="space-between" gap="sm">
        <Text size="sm" c="dimmed">
          No dataset yet? Import the bundled synthetic sample (weekly-buy-v1).
        </Text>
        <Button variant="light" onClick={importSample} loading={sample.isPending}>
          Import sample dataset
        </Button>
      </Group>
    </Stack>
  );
}
