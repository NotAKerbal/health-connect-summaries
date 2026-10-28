# Health Connect Summaries

Ask ChatGPT about your own Android Health Connect data (steps, sleep, heart rate, weight,
distance, active calories) through a private ChatGPT plugin that you host yourself.

- **Android app** (`android/`): reads the daily totals you allow in Health Connect and syncs the
  last 30 days to your Site in the background.
- **Private Site** (`cloud/`): an owner-only [ChatGPT Sites](https://openai.com/policies/chatgpt-sites-terms/)
  app that stores the summaries and serves a read-only MCP server, which Sites publishes as your
  private ChatGPT plugin.

**Each person runs their own private copy.** There is no shared service: your phone talks only
to your own Site, and nobody else's Site or signing key is involved.

## Privacy in brief

- **Not ChatGPT Health.** This is separate from ChatGPT Health, does not connect to it, and its
  protections do not apply. It is not a medical device.
- **Your Site holds the data.** Daily aggregates are stored in your Site's database. Anyone who
  administers that Sites/hosting account can access it. There is no app-level encryption at rest.
- **ChatGPT reads only when you allow it.** The plugin's tools are read-only and stay off until
  you turn on ChatGPT access on your dashboard. Results then enter your ChatGPT conversations
  under your ChatGPT data settings.

More: [privacy](docs/privacy.md) · [security](docs/security.md).

## Get your own copy

You need a ChatGPT account where Sites and Site plugins are available (not every plan or region
has them), Codex or another agent with the Sites plugin, and an Android phone with Health
Connect. See [setup](docs/setup.md) for the full checklist and a copy/paste prompt.

## Docs

| | |
| --- | --- |
| [Setup](docs/setup.md) | Create your private Site, build the app, pair your phone. |
| [Architecture](docs/architecture.md) | Pairing, snapshot format, sync, and MCP tools. |
| [Security](docs/security.md) · [Privacy](docs/privacy.md) | Trust boundaries and what is stored where. |
| [Development](docs/development.md) | Tests, local preview, and project layout. |
| [Releasing](docs/releasing.md) | Versions, signing keys, and the release checklist. |

## Status

Early, personal-scale software. No app-store listing or plugin-directory listing exists, and
nothing here has been reviewed for regulatory compliance. Builds you make yourself are debug
or self-signed builds.

[MIT License](LICENSE). Third-party components keep their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
