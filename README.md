# deeps kaskad (fork)

Android client of the deeps kaskad panel, built on
[AmneziaWG for Android](https://github.com/amnezia-vpn/amneziawg-android).
Application id `ru.deffhost.kaskad`; the namespace and the upstream Java/Kotlin code stay
Apache-2.0.

What this fork adds, where the licenses are and how the source is published — see
[NOTICE](NOTICE). Source of every published APK: this repository.

## Building a pre-configured client

The build supports one optional setting: an address the sign-in form starts with. The
mechanism is public, the address is not part of this repository.

1. In `local.properties` (gitignored) add the line
   `BRANDED_SERVER=https://panel.example.com`.
2. Build as usual: `./gradlew :ui:assembleRelease`.
3. Without the key the field starts with `https://` only, as in the default build.

The address is only a default: the user can edit it before signing in, and switching to
another panel does not require a new APK. Whoever builds such a client is responsible for
their own address, signing key and distribution.


---

# Android GUI for [AmneziaWG](https://amnezia.org/learn-more/31_amneziawg)

**[Download from the Play Store](https://play.google.com/store/apps/details?id=org.amnezia.awg)**

This is an Android GUI for [AmneziaWG](https://amnezia.org/learn-more/31_amneziawg).

## Building

```
$ git clone --recurse-submodules https://github.com/amnezia-vpn/amneziawg-android
$ cd amneziawg-android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).
