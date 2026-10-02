// Shapes mirroring the backend REST contract.

// A result row: column name -> value (value type is unknown and narrowed when rendered).
export type ResultRow = Record<string, unknown>;

export interface QueryResponse {
  table: ResultRow[];
  sql: string;
  explanation: string;
}

export interface InteractionSummary {
  id: number;
  question: string;
  generatedSql: string | null;
  resultSummary: string | null;
  explanation: string | null;
  status: "SUCCESS" | "FAILED" | string;
  latencyMs: number;
  createdAt: string;
}

// The safe error body returned by the backend advice: { code, message }.
export interface ApiErrorBody {
  code?: string;
  message?: string;
}