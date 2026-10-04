/** Shared by the initial route guard and expired-session recovery. */
export function isProtectedPath(pathname: string): boolean {
  return (
    ["/me", "/checkout", "/organizer", "/admin"].some(
      (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
    ) || /^\/shows\/[^/]+\/[^/]+\/(queue|seats)\/?$/.test(pathname)
  );
}
