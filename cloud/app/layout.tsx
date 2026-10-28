import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Health Connect Summaries",
  description:
    "Private Health Connect summaries with automatic phone sync and read-only access from your ChatGPT plugin.",
  robots: { index: false, follow: false },
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
