import { NextResponse } from 'next/server';
import { BACKEND_BASE } from '../auth/_backend';

export const dynamic = 'force-dynamic';

/** Public readiness probe: check the actual catalog path without disclosing configuration. */
export async function GET() {
  let healthy = false;
  try {
    const response = await fetch(`${BACKEND_BASE}/shows`, {
      cache: 'no-store', signal: AbortSignal.timeout(8000),
    });
    const body: unknown = response.ok ? await response.json() : null;
    healthy = response.ok && typeof body === 'object' && body !== null &&
      'shows' in body && Array.isArray(body.shows);
  } catch {
    // Never expose upstream addresses, credentials or exception details.
  }
  return NextResponse.json({ status: healthy ? 'ok' : 'degraded' }, {
    status: healthy ? 200 : 503,
    headers: { 'Cache-Control': 'no-store' },
  });
}
