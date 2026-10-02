"use client";

// Exactly five example questions; clicking a chip fills the input and submits.
const EXAMPLES = [
  "top customers by spending",
  "revenue by month",
  "best-selling products",
  "orders by status",
  "customers by country",
] as const;

interface ExampleQuestionsProps {
  onPick: (question: string) => void;
  loading: boolean;
}

export default function ExampleQuestions({ onPick, loading }: ExampleQuestionsProps) {
  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs font-medium uppercase tracking-wide text-slate-500">
        Try an example
      </span>
      <div className="flex flex-wrap gap-2">
        {EXAMPLES.map((example) => (
          <button
            key={example}
            type="button"
            disabled={loading}
            onClick={() => onPick(example)}
            className="rounded-full border border-slate-300 bg-white px-3 py-1 text-sm text-slate-700 transition hover:border-slate-500 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {example}
          </button>
        ))}
      </div>
    </div>
  );
}