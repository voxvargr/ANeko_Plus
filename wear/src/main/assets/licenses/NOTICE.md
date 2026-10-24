# WearNeko prototype notices

WearNeko 0.1.1 is a local, standalone Wear OS overlay prototype. New application
code is provided under GNU LGPL 2.1; see LICENSE. The animation and wandering
implementation was written for this prototype and is not the full ANeko app.

The 23 `neko_*.png` sprite frames are copied without modification from
`app/src/main/assets/aneko/` in ANeko Reborn:

https://github.com/pass-with-high-score/ANeko

Inspected upstream commit: ad96e2dec66feed1e021fe0b92adfad8742639dd.
Upstream identifies its license as LGPL-2.1. Its skin metadata identifies
`nqmgaming` as the author. Its README credits original ANeko creator Tamanegi,
fork maintainer choiman1559, and Nguyen Quang Minh for the updated app.

The Gradle wrapper is from the Gradle project and is licensed under Apache 2.0:
https://github.com/gradle/gradle/blob/master/LICENSE

Source, Gradle build files, and the original sprite frames are included in this
project. This prototype has no runtime third-party library dependencies, network
permission, analytics, accessibility service, or model backend.
