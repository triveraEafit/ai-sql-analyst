// Runtime configuration sourced from NEXT_PUBLIC_* env vars (inlined at build time).

const DEFAULT_API_BASE_URL = "http://localhost:8080";
const DEFAULT_MAX_QUESTION_LENGTH = 300;

export const API_BASE_URL: string =
  process.env.NEXT_PUBLIC_API_URL && process.env.NEXT_PUBLIC_API_URL.trim().length > 0
    ? process.env.NEXT_PUBLIC_API_URL.trim().replace(/\/+$/, "")
    : DEFAULT_API_BASE_URL;

function parseMaxLength(raw: string | undefined): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_MAX_QUESTION_LENGTH;
}

export const MAX_QUESTION_LENGTH: number = parseMaxLength(
  process.env.NEXT_PUBLIC_MAX_QUESTION_LENGTH,
);