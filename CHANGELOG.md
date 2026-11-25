# Changelog

Changes to the deeps kaskad Android client are recorded here. This is not the
upstream AmneziaWG changelog. `Unreleased` describes work on `develop`; a
versioned section describes a build published for users. Test APKs are not
automatically new releases.

## 0.4.12

- Retry failed automatic update checks after 15 minutes instead of waiting a
  full day. Successful checks remain limited to once per day; manual checks
  still bypass the interval.
- Treat a panel's `404` response as withdrawal of its APK: clear the saved
  offer and block installation from an older notification. Temporary errors
  do not revoke a downloaded APK.
- Resume a user-requested installation after returning to the app if its
  verification finished while the screen was in the background.
- The download notification now opens the app on tap and offers a cancel action.
- A withdrawn update is reported as withdrawn instead of as a signature problem,
  and old downloads and interrupted transfers are cleaned up automatically.
- Show every configuration: the list is no longer clipped by the surrounding
  scroll container, so the rows always match the "configurations" counter.
- `versionCode` is derived only by the build; the client compares the panel's
  claim with the downloaded APK instead of recomputing the number.

## 0.4.11

- Set `versionCode` to `41100`. Built separate clients with editable,
  pre-filled panel addresses for libra and omega; no panel address or signing
  material is stored in this repository.
- No other source changes from 0.4.10.4.

Source: [`672f45b1`](https://github.com/lange232/deeps-kaskad-awg/commit/672f45b1).

## 0.4.10.4

- Added manual and daily automatic update checks, background APK download,
  progress and cancellation, and explicit installation through Android's
  system installer.
- Verify the downloaded APK's size, SHA-256, package identity, version,
  signing certificate and client-family marker. Keep a verified download for
  installation without fetching it again.
- Added an optional, editable default panel address for locally configured
  builds and derived `versionCode` from `versionName`.
- Improved account exit status, per-configuration routes, and traffic-history
  chart controls and totals.

Source: [`a39a437f`](https://github.com/lange232/deeps-kaskad-awg/commit/a39a437f).

## Earlier client work

- **0.4.10:** Account exit overview, pinning and clearer labels
  ([`c6909c99`](https://github.com/lange232/deeps-kaskad-awg/commit/c6909c99)).
- **0.4.9:** Restored tunnel bindings and displayed exit status
  ([`61b43565`](https://github.com/lange232/deeps-kaskad-awg/commit/61b43565)).
- **0.4.7 and 0.4.6:** Refined account, configuration and routing screens
  ([`da0a65f9`](https://github.com/lange232/deeps-kaskad-awg/commit/da0a65f9),
  [`7976f796`](https://github.com/lange232/deeps-kaskad-awg/commit/7976f796)).

Every build up to 0.4.13, including the 0.4.12.x libra builds, was for testing
only and was never distributed to users. Individual test builds are not
recorded here as source releases; 0.4.13 is the first user-facing version.
