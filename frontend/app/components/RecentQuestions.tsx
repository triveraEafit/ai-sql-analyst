import type { InteractionSummary } from "../lib/types";

interface RecentQuestionsProps {
  items: InteractionSummary[];
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

export default function RecentQuestions({ items }: RecentQuestionsProps) {
  return (
    <section aria-label="Recent questions" className="flex flex-col gap-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
        Recent questions
      </h2>
      {items.length === 0 ? (
        <p className="text-sm text-slate-500">No questions yet.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((item) => (
            <li
              key={item.id}
              className="rounded-md border border-slate-200 bg-white p-3 text-sm shadow-sm"
            >
              <p className="font-medium text-slate-800">{item.question}</p>
              <div className="mt-1 flex flex-wrap items-center gap-2 text-xs">
                <span
                  className={`rounded-full px-2 py-0.5 font-medium ${
                    item.status === "SUCCESS"
                      ? "bg-green-100 text-green-700"
                      : "bg-red-100 text-red-700"
                  }`}
                >
                  {item.status}
                </span>
                <span className="text-slate-400">{formatWhen(item.createdAt)}</span>
              </div>
              {item.resultSummary ? (
                <p className="mt-1 text-xs text-slate-500">{item.resultSummary}</p>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}