"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import ErrorBanner from "./components/ErrorBanner";
import ExampleQuestions from "./components/ExampleQuestions";
import LoadingIndicator from "./components/LoadingIndicator";
import QuestionForm from "./components/QuestionForm";
import RecentQuestions from "./components/RecentQuestions";
import ResultsPanel from "./components/ResultsPanel";
import { getHistory, postQuery } from "./lib/api";
import { MAX_QUESTION_LENGTH } from "./lib/config";
import type { InteractionSummary, QueryResponse } from "./lib/types";

export default function Home() {
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<QueryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<InteractionSummary[]>([]);

  // Guards against overlapping requests even if a submit slips through.
  const inFlight = useRef(false);

  const refreshHistory = useCallback(async () => {
    try {
      setHistory(await getHistory());
    } catch {
      // History is non-critical; keep the previous list on failure.
    }
  }, []);

  useEffect(() => {
    void refreshHistory();
  }, [refreshHistory]);

  const runQuery = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (loading || inFlight.current) {
        return;
      }
      // Mirror the client-side validation guards (empty/whitespace/over-length).
      if (trimmed.length === 0 || text.length > MAX_QUESTION_LENGTH) {
        return;
      }

      inFlight.current = true;
      setLoading(true);
      setError(null);
      try {
        const response = await postQuery(text);
        setResult(response);
        setError(null);
      } catch (e) {
        setResult(null);
        setError(e instanceof Error ? e.message : "Something went wrong. Please try again.");
      } finally {
        setLoading(false);
        inFlight.current = false;
        void refreshHistory();
      }
    },
    [loading, refreshHistory],
  );

  const handlePickExample = useCallback(
    (example: string) => {
      setQuestion(example);
      void runQuery(example);
    },
    [runQuery],
  );

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-6 px-4 py-8">
      <header>
        <h1 className="text-2xl font-bold text-slate-900">AI SQL Analyst</h1>
        <p className="mt-1 text-sm text-slate-600">
          Ask a plain-English question about the sample e-commerce database.
        </p>
      </header>

      <div className="grid grid-cols-1 gap-8 lg:grid-cols-3">
        <div className="flex flex-col gap-5 lg:col-span-2">
          <QuestionForm
            question={question}
            onQuestionChange={setQuestion}
            onSubmit={() => void runQuery(question)}
            loading={loading}
          />
          <ExampleQuestions onPick={handlePickExample} loading={loading} />

          {loading ? <LoadingIndicator /> : null}
          {!loading && error ? <ErrorBanner message={error} /> : null}
          {!loading && !error && result ? <ResultsPanel result={result} /> : null}
        </div>

        <aside className="lg:col-span-1">
          <RecentQuestions items={history} />
        </aside>
      </div>
    </main>
  );
}