export function generatedName(label: string, subject: string, at: Date, shorten = true): string {
  const short =
    shorten && subject.length > 9 ? `${subject.slice(0, 4)}…${subject.slice(-4)}` : subject;
  const day = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' }).format(at);
  const time = new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(at);
  return `${label} · ${short} · ${day} ${time}`;
}
