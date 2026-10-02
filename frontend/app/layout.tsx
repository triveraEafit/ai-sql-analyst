import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AI SQL Analyst",
  description: "Ask plain-English questions about the sample e-commerce database.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="min-h-screen antialiased">{children}</body>
    </html>
  );
}