# Privacy

This describes the software. Whoever runs a copy decides how it is used; each copy is operated
by its own owner, not by the authors of this repository.

## Separate from ChatGPT Health

This project does not connect to ChatGPT Health, and ChatGPT Health's specific protections do not
apply. It is not a medical device and gives no medical advice.

## What is read, sent, and stored

| Where | What |
| --- | --- |
| **Phone** | Reads only the categories you allow in Health Connect, as daily totals or averages. Credentials are encrypted with an Android Keystore key. Optional export files stay in app storage. Backups and device transfer are off. No analytics; health values are not logged. |
| **Network** | HTTPS to your one Site only: the built-in one, or the one you confirmed when pairing. |
| **Your Site** | Up to 30 days per sync of daily aggregates (no raw samples or source apps), import history, and the paired phone's name and sync times, in the Site's database. |
| **ChatGPT** | Nothing until you turn on ChatGPT access on the dashboard. After that, tool results enter your conversations and follow your ChatGPT data settings. |

## Who can access it

You, signed in to your Site, and through your plugin once you allow it. Also anyone who can
administer your Sites/hosting account or its database: there is no application-level encryption
at rest. The authors of this repository have no access to your copy.

## Your controls

- In the app: turn off automatic sync, disconnect (revokes and erases the phone's credentials),
  delete exports, and revoke categories or background access in Health Connect.
- On the dashboard: turn ChatGPT access off, disconnect the phone, delete individual imports, or
  **Delete everything** (data, devices, and pending codes).
- Health Connect normally lets a newly allowed app read up to 30 days before the first grant; the
  app never asks for older history.

The app and Site each include a privacy page with the same information.
