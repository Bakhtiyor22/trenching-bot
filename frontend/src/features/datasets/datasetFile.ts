import { formatBytes } from '../../lib/format';

/** Server limit (`spring.servlet.multipart.max-file-size`); checked client-side before upload. */
export const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;

/** Client-side file check mirroring the backend limits. Returns an error message or null. */
export function validateDatasetFile(file: File): string | null {
  if (!file.name.toLowerCase().endsWith('.json')) return 'Only .json replay datasets can be uploaded.';
  if (file.size === 0) return 'The file is empty.';
  if (file.size > MAX_UPLOAD_BYTES) {
    return `The file is ${formatBytes(file.size)}; the limit is ${formatBytes(MAX_UPLOAD_BYTES)}.`;
  }
  return null;
}
