# deeps kaskad Android client

This repository is the Android client based on
[AmneziaWG for Android](https://github.com/amnezia-vpn/amneziawg-android).
The panel/server is a separate repository. Keep this file free of server
addresses, credentials, keys and local machine details.

## Repository boundaries

- Preserve `amneziawgApplicationId=ru.deffhost.kaskad` and
  `amneziawgPackageName=org.amnezia.awg` in `gradle.properties`. Do not change
  the signing key: either change would prevent existing installations from
  updating without losing app data.
- Never commit `local.properties`, keystores, signing passwords, generated APKs
  or provider-specific server addresses. The optional `BRANDED_SERVER` property
  belongs in gitignored `local.properties`; the sign-in address remains editable.
  An ordinary build without it begins with `https://`.
- Do not modify the server repository, panel deployment or production host as
  part of client work. Copying a requested APK to a panel is a separate,
  explicitly authorized operation; it does not require a panel code deploy.
- Make ordinary client changes in `develop`, including code, resources, build
  configuration and documentation. Before editing, check the active branch;
  if it is `master`, switch to `develop` first. Bring `develop` up to date
  with `master` when appropriate, without discarding uncommitted work.
  Treat `master` as the reviewed/released branch: move changes there through
  a PR after verification, not by editing it directly. Do not push, merge,
  release or deploy merely because a local build succeeds.

## Versions and release notes

- Edit `amneziawgVersionName` in `gradle.properties`; `ui/build.gradle.kts`
  derives `versionCode = (major * 10000 + minor * 100 + patch) * 100 + sub`.
  For example, `0.4.11` is `41100` and `0.4.11.1` is `41101`. Minor, patch
  and optional subversion each range from 0 to 99.
- Android only updates to a *higher* `versionCode` with the same package and
  signing key. Some historical libra test builds were numbered `0.4.12.x`
  and cannot update in place to `0.4.11`; do not assume the display name
  determines upgrade compatibility.
- Keep `CHANGELOG.md` current: put pending user-visible changes under
  `Unreleased` on `develop`, then move them to a versioned entry when that
  version is published. Distinguish test APKs from releases, and link to the
  corresponding source commit where possible.
- Libra and omega may receive different APK bytes with the same version:
  their editable default panel addresses differ. Verify and record each
  artifact's own SHA-256 rather than treating them as interchangeable.

## Building and checking

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
PATH="$HOME/.local/go/bin:$PATH" ./gradlew :ui:compileReleaseKotlin
PATH="$HOME/.local/go/bin:$PATH" ./gradlew :ui:assembleRelease
```

The output is `ui/build/outputs/apk/release/ui-release.apk`. A release build
without a configured, existing `KEY_STORE_PATH` in `local.properties` may be
unsigned: verify the package, `versionCode`, certificate and SHA-256 before
distribution. For two provider-specific builds, use isolated build
directories/configuration so changing `BRANDED_SERVER` for one does not
silently change the other. Do not publish build logs containing local secrets.

The app has no dedicated update-flow unit tests. Compile checks do not prove
the user flow: exercise check, download, cancellation, app restart, notification
tap, installation permission and system installer on a test device before
claiming a release works. Do not use production data or services as test
fixtures.

## Update-flow invariants

- `GET /api/android` is public. Check no more than once a day after success,
  retry transient failures later, and allow an explicit manual check.
- Download only after a user tap, in a foreground service. Keep progress and
  cancellation available on the account screen, and progress and a completion
  action in notifications; do not launch the installer automatically after
  download.
- Keep update state scoped to the selected panel. Validate HTTPS origin
  (including port), size, SHA-256, package, APK version, signing certificate
  and the AWG client-family marker before installation. Recheck the cached
  file when the user chooses to install; reuse it after permission is granted.
- A panel `404` withdraws the offer; a temporary network error or `503`
  must not be presented as "latest version". Preserve a previously verified
  offline download on transient failure, but reject a withdrawn one.
- Keep user-facing strings in both `ui/src/main/res/values/strings.xml` and
  `ui/src/main/res/values-ru/strings.xml`. Source code and documentation stay
  in English.

See `README.md` for the public build instructions, `NOTICE` for license and
source attribution, and `CHANGELOG.md` for client release history.
