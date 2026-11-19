# deeps kaskad (fork)

Android client of the deeps kaskad panel, built on
[AmneziaWG for Android](https://github.com/amnezia-vpn/amneziawg-android).
Application id `ru.deffhost.kaskad`; the namespace and the upstream Java/Kotlin code stay
Apache-2.0.

What this fork adds, where the licenses are and how the source is published — see
[NOTICE](NOTICE). Source of every published APK: this repository.

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
