# Changelog

## 0.5.0 - 2026-10-27

- Generic Android build: without `-PhcSiteUrl`, the app accepts a connection code from any public
  HTTPS Site after you confirm its hostname. Pinned builds behave as before.
- Generic builds refuse IP-literal, local, special-use, and explicit-port origins, and hostnames
  that resolve to non-public addresses. A stored pairing always stays on its own origin.
- First run shows three short steps: connect, choose health data, sync and ChatGPT access.
- Release signing from environment variables only; incomplete signing settings fail the build.
- Public source preparation: license, notices, docs, CI, and a privacy scan.

## 0.4.0

- Simplified main screen: one status, last sync, and one contextual action; settings and details
  collapsed.

## 0.3.0

- The app shows whether ChatGPT access is on, from a status check that sends no health data.

## 0.2.0

- Automatic background sync after a one-time pairing with a connection code.

## 0.1.0

- Manual JSON export from Health Connect, browser upload, and read-only MCP tools.
