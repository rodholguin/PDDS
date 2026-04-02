const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

/** Generic JSON fetcher with unified error handling. */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...init?.headers },
    ...init,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body?.message ?? `HTTP ${res.status}: ${path}`);
  }
  return res.json() as Promise<T>;
}

/** Multipart upload — does NOT set Content-Type (browser does it with boundary). */
export async function upload<T>(path: string, formData: FormData): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'POST', body: formData });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body?.message ?? `Upload failed: HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

/** Streams a blob and triggers a browser download. */
export async function download(path: string, filename: string): Promise<void> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`Download failed: HTTP ${res.status}`);
  const blob = await res.blob();
  const url  = URL.createObjectURL(blob);
  const a    = Object.assign(document.createElement('a'), { href: url, download: filename });
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export { BASE };
