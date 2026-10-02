import { API_BASE_URL } from "./config";
import type { ApiErrorBody, InteractionSummary, QueryResponse } from "./types";

const NETWORK_ERROR = "Cannot reach the server.";
const GENERIC_ERROR = "Something went wrong. Please try again.";

/**
 * Reads an error message from a failed Response. Falls back to a generic message
 * when the body is missing or not valid JSON (e.g. an HTML 500 page).
 */
async function errorMessageFrom(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as ApiErrorBody;
    if (body && typeof body.message === "string" && body.message.trim().length > 0) {
      return body.message;
    }
    return GENERIC_ERROR;
  } catch {
    return GENERIC_ERROR;
  }
}

/** POST /api/query. Throws an Error carrying a user-safe message on failure. */
export async function postQuery(question: string): Promise<QueryResponse> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/api/query`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });
  } catch {
    // fetch rejects on network/DNS/connection failures.
    throw new Error(NETWORK_ERROR);
  }

  if (!response.ok) {
    throw new Error(await errorMessageFrom(response));
  }

  try {
    return (await response.json()) as QueryResponse;
  } catch {
    throw new Error(GENERIC_ERROR);
  }
}

/** GET /api/history. Throws an Error carrying a user-safe message on failure. */
export async function getHistory(): Promise<InteractionSummary[]> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/api/history`, { method: "GET" });
  } catch {
    throw new Error(NETWORK_ERROR);
  }

  if (!response.ok) {
    throw new Error(await errorMessageFrom(response));
  }

  try {
    const data = (await response.json()) as InteractionSummary[];
    return Array.isArray(data) ? data : [];
  } catch {
    throw new Error(GENERIC_ERROR);
  }
}