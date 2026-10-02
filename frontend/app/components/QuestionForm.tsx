"use client";

import { FormEvent } from "react";
import { MAX_QUESTION_LENGTH } from "../lib/config";

interface QuestionFormProps {
  question: string;
  onQuestionChange: (value: string) => void;
  onSubmit: () => void;
  loading: boolean;
}

export default function QuestionForm({
  question,
  onQuestionChange,
  onSubmit,
  loading,
}: QuestionFormProps) {
  const trimmedLength = question.trim().length;
  const length = question.length;
  const isEmpty = trimmedLength === 0;
  const isTooLong = length > MAX_QUESTION_LENGTH;
  const canSubmit = !loading && !isEmpty && !isTooLong;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (canSubmit) {
      onSubmit();
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2">
      <label htmlFor="question" className="text-sm font-medium text-slate-700">
        Ask a question about the e-commerce data
      </label>
      <textarea
        id="question"
        name="question"
        value={question}
        onChange={(e) => onQuestionChange(e.target.value)}
        rows={3}
        placeholder="e.g. top customers by spending"
        className="w-full resize-y rounded-md border border-slate-300 p-3 text-slate-900 shadow-sm focus:border-slate-500 focus:outline-none focus:ring-1 focus:ring-slate-500"
        aria-describedby="char-counter"
      />
      <div className="flex items-center justify-between">
        <span
          id="char-counter"
          className={`text-xs ${isTooLong ? "text-red-600" : "text-slate-500"}`}
        >
          {length}/{MAX_QUESTION_LENGTH}
          {isTooLong ? " - too long" : ""}
        </span>
        <button
          type="submit"
          disabled={!canSubmit}
          className="rounded-md bg-slate-800 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {loading ? "Running..." : "Ask"}
        </button>
      </div>
    </form>
  );
}