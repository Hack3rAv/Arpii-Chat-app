export function securityHeaders(allowedOrigin: string): Record<string, string> {
  return {
    "Content-Type": "application/json; charset=utf-8",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Cache-Control": "no-store, max-age=0",
    "Access-Control-Allow-Origin": allowedOrigin,
    "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Device-Id, X-Request-Id",
    "Access-Control-Max-Age": "86400",
  };
}

export function jsonResponse(
  data: unknown,
  status: number,
  allowedOrigin = "*",
  extraHeaders?: Record<string, string>
): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...securityHeaders(allowedOrigin),
      ...(extraHeaders ?? {}),
    },
  });
}

export function errorResponse(
  code: string,
  message: string,
  status: number,
  allowedOrigin = "*",
  extraHeaders?: Record<string, string>
): Response {
  return jsonResponse({ error: { code, message } }, status, allowedOrigin, extraHeaders);
}
