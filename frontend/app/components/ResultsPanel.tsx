import type { QueryResponse, ResultRow } from "../lib/types";

function formatCell(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function columnsOf(table: ResultRow[]): string[] {
  const keys = new Set<string>();
  for (const row of table) {
    Object.keys(row).forEach((k) => keys.add(k));
  }
  return Array.from(keys);
}

interface ResultsPanelProps {
  result: QueryResponse;
}

export default function ResultsPanel({ result }: ResultsPanelProps) {
  const columns = columnsOf(result.table);

  return (
    <section className="flex flex-col gap-4" aria-label="Query results">
      <div className="overflow-x-auto rounded-md border border-slate-200">
        {result.table.length === 0 || columns.length === 0 ? (
          <p className="p-4 text-sm text-slate-500">No rows returned.</p>
        ) : (
          <table className="min-w-full border-collapse text-left text-sm">
            <thead className="bg-slate-100">
              <tr>
                {columns.map((col) => (
                  <th key={col} className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {result.table.map((row, rowIndex) => (
                <tr key={rowIndex} className="border-t border-slate-100 odd:bg-white even:bg-slate-50">
                  {columns.map((col) => (
                    <td key={col} className="whitespace-nowrap px-3 py-2 text-slate-800">
                      {formatCell(row[col])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {result.explanation ? (
        <p className="text-sm leading-relaxed text-slate-700">{result.explanation}</p>
      ) : null}

      <details className="rounded-md border border-slate-200 bg-slate-50 p-3">
        <summary className="cursor-pointer text-sm font-medium text-slate-700">Show SQL</summary>
        <pre className="mt-2 overflow-x-auto whitespace-pre-wrap break-words text-xs text-slate-800">
          {result.sql}
        </pre>
      </details>
    </section>
  );
}