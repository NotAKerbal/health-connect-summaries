# Third-party notices

The [MIT License](LICENSE) covers this project's own work. The components below keep their own
licenses.

## Included in this repository

| Files | Origin | License |
| --- | --- | --- |
| `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar` | Gradle wrapper 8.14.3. The JAR's SHA-256 matches Gradle's published checksum for that version. | [Apache License 2.0](licenses/Apache-2.0.txt), also stated in the scripts' headers |

## Not included: Sites Vinext starter

The Site's build integration (Vite and Worker configuration, ChatGPT sign-in helpers, D1 access,
UI components, install and run scripts) comes from the Vinext starter in the ChatGPT Sites
plugin. Those files are **not** redistributed here; `cloud/sites-starter-files.txt` lists them,
and the Sites plugin's `project-setup.mjs` supplies them under the plugin's own terms.

These files in `cloud/` began as starter files and were changed for this app:
`app/layout.tsx`, `app/globals.css`, `cloudflare-env.d.ts`, `db/schema.ts`,
`drizzle/meta/_journal.json`, `package.json`, `package-lock.json`, `tsconfig.json`, and
`README.md`. Any unchanged starter-derived portions remain under the starter's terms; the MIT
License covers this project's changes.

## Downloaded at build time (not included)

- **Android:** the Android Gradle Plugin, Kotlin, AndroidX libraries (Health Connect client,
  Activity, Core, Lifecycle, WorkManager), kotlinx-coroutines, JUnit 4, and org.json, as listed
  in `android/build.gradle.kts` and `android/app/build.gradle.kts`.
- **Site:** the npm packages in `cloud/package.json` and `cloud/package-lock.json`.
- **Toolchain:** `android/scripts/setup-toolchain.sh` downloads a JDK, Gradle, and the Android
  SDK; their licenses are presented by their providers (the SDK script accepts the Android SDK
  license on your behalf, so review it first).

Each dependency is licensed under the terms recorded in its own package or POM metadata.
