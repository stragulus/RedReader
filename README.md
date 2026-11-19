RedReader
=========

An unofficial, open source Android client for Reddit.

![CI](https://github.com/QuantumBadger/RedReader/workflows/CI/badge.svg?branch=master)
[![Translation status](https://hosted.weblate.org/widgets/redreader/-/svg-badge.svg)](https://hosted.weblate.org/engage/redreader/?utm_source=widget)

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png"
      alt="Get it on Google Play"
      height="80">](https://play.google.com/store/apps/details?id=org.quantumbadger.redreader)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/org.quantumbadger.redreader/)

Features
--------

* Free and open source, with no ads or tracking
* Lightweight and fast
* Swipe posts left and right to perform customizable actions, such as
    upvote/downvote, or save/hide
* Advanced cache management - automatically stores past versions of posts and
    comments
* Support for multiple accounts
* Two-column tablet mode (can be used on your phone, if it’s big enough)
* Image and comment precaching (optional: always, never, or Wi-Fi only)
* Built-in image viewer, and GIF/video player
* Multiple themes, including night mode, and ultra black for AMOLED displays
* Support for Android 5.0+
* Translations for multiple languages
* Accessibility features and optimization for screen reader use


Downloading
-----------

RedReader is available for free on the Google Play store:

https://play.google.com/store/apps/details?id=org.quantumbadger.redreader

RedReader can also be found for free on F-Droid:

https://f-droid.org/app/org.quantumbadger.redreader

The Google Play APKs are also available here:

https://github.com/QuantumBadger/RedReader/releases


Translating
-----------

Please help us translate RedReader into new languages, or improve the
translations for existing languages!

https://hosted.weblate.org/projects/redreader/strings/

[![Translation status](https://hosted.weblate.org/widgets/redreader/-/287x66-grey.png)](https://hosted.weblate.org/engage/redreader/?utm_source=widget)


Pull Requests
-------------

Please see the contribution guidelines in
 [CONTRIBUTING.md](CONTRIBUTING.md). 


Building
--------

The on-device translation engine (`libs/redreader-translate`) is built from
source from git submodules, so after cloning, fetch them first:

    git submodule update --init --recursive

(or clone with `git clone --recursive`). Building it needs the Android NDK and
CMake versions listed in [gradle/libs.versions.toml](gradle/libs.versions.toml),
which can be installed with the Android SDK manager.

RedReader is built using Gradle. On Linux, simply run:

    ./gradlew installDebug

A full native build for all ABIs takes a while. During development, you can
restrict it to the ABI of your device or emulator, e.g.
`./gradlew installDebug -PtranslateAbis=x86_64`.


License
-------

RedReader is licensed under the GPL, version 3. A copy of the license is
included in [LICENSE.txt](LICENSE.txt).


Thanks
------

A full list of contributors is included in the [changelog](src/main/assets/changelog.txt).
