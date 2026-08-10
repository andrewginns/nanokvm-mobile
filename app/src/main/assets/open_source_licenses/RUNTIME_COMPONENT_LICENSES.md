# Resolved runtime component licences

This index covers every external component in NanoKVM Mobile's resolved
`releaseRuntimeClasspath` for version 0.3.7. Some BOM/platform entries govern
versions rather than contributing APK bytes. The build fails when the
checked-in coordinates drift from the resolved graph. The canonical CycloneDX
SBOM published with a release remains the machine-readable dependency
inventory.

The app's own `org.nanokvm` modules are GPL-3.0-or-later; see the bundled
`GPL-3.0-or-later.txt`. The exact Maven coordinates below identify resolved
artifacts, not their corresponding source. A separately reviewed source manifest
must accompany each public release as described in `docs/DISTRIBUTION.md`.

## Apache License 2.0

The following components are distributed under Apache-2.0. The complete terms
are bundled as `APACHE-2.0.txt`.

- `androidx.activity:activity:1.13.0`
- `androidx.activity:activity-compose:1.13.0`
- `androidx.activity:activity-ktx:1.13.0`
- `androidx.annotation:annotation:1.10.0`
- `androidx.annotation:annotation-experimental:1.5.1`
- `androidx.annotation:annotation-jvm:1.10.0`
- `androidx.appcompat:appcompat:1.2.0`
- `androidx.appcompat:appcompat-resources:1.2.0`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`
- `androidx.autofill:autofill:1.0.0`
- `androidx.biometric:biometric:1.1.0`
- `androidx.collection:collection:1.5.0`
- `androidx.collection:collection-jvm:1.5.0`
- `androidx.collection:collection-ktx:1.5.0`
- `androidx.compose.animation:animation:1.11.4`
- `androidx.compose.animation:animation-android:1.11.4`
- `androidx.compose.animation:animation-core:1.11.4`
- `androidx.compose.animation:animation-core-android:1.11.4`
- `androidx.compose.foundation:foundation:1.11.4`
- `androidx.compose.foundation:foundation-android:1.11.4`
- `androidx.compose.foundation:foundation-layout:1.11.4`
- `androidx.compose.foundation:foundation-layout-android:1.11.4`
- `androidx.compose.material:material-icons-core:1.7.8`
- `androidx.compose.material:material-icons-core-android:1.7.8`
- `androidx.compose.material:material-icons-extended:1.7.8`
- `androidx.compose.material:material-icons-extended-android:1.7.8`
- `androidx.compose.material:material-ripple:1.11.4`
- `androidx.compose.material:material-ripple-android:1.11.4`
- `androidx.compose.material3.adaptive:adaptive:1.2.0`
- `androidx.compose.material3.adaptive:adaptive-android:1.2.0`
- `androidx.compose.material3.adaptive:adaptive-layout:1.2.0`
- `androidx.compose.material3.adaptive:adaptive-layout-android:1.2.0`
- `androidx.compose.material3:material3:1.4.0`
- `androidx.compose.material3:material3-android:1.4.0`
- `androidx.compose.runtime:runtime:1.11.4`
- `androidx.compose.runtime:runtime-android:1.11.4`
- `androidx.compose.runtime:runtime-annotation:1.11.4`
- `androidx.compose.runtime:runtime-annotation-android:1.11.4`
- `androidx.compose.runtime:runtime-retain:1.11.4`
- `androidx.compose.runtime:runtime-retain-android:1.11.4`
- `androidx.compose.runtime:runtime-saveable:1.11.4`
- `androidx.compose.runtime:runtime-saveable-android:1.11.4`
- `androidx.compose.ui:ui:1.11.4`
- `androidx.compose.ui:ui-android:1.11.4`
- `androidx.compose.ui:ui-geometry:1.11.4`
- `androidx.compose.ui:ui-geometry-android:1.11.4`
- `androidx.compose.ui:ui-graphics:1.11.4`
- `androidx.compose.ui:ui-graphics-android:1.11.4`
- `androidx.compose.ui:ui-text:1.11.4`
- `androidx.compose.ui:ui-text-android:1.11.4`
- `androidx.compose.ui:ui-unit:1.11.4`
- `androidx.compose.ui:ui-unit-android:1.11.4`
- `androidx.compose.ui:ui-util:1.11.4`
- `androidx.compose.ui:ui-util-android:1.11.4`
- `androidx.compose:compose-bom:2026.06.01`
- `androidx.concurrent:concurrent-futures:1.1.0`
- `androidx.core:core:1.19.0`
- `androidx.core:core-ktx:1.19.0`
- `androidx.core:core-viewtree:1.0.0`
- `androidx.cursoradapter:cursoradapter:1.0.0`
- `androidx.customview:customview:1.0.0`
- `androidx.customview:customview-poolingcontainer:1.0.0`
- `androidx.datastore:datastore:1.2.1`
- `androidx.datastore:datastore-android:1.2.1`
- `androidx.datastore:datastore-core:1.2.1`
- `androidx.datastore:datastore-core-android:1.2.1`
- `androidx.datastore:datastore-core-okio:1.2.1`
- `androidx.datastore:datastore-core-okio-jvm:1.2.1`
- `androidx.datastore:datastore-preferences:1.2.1`
- `androidx.datastore:datastore-preferences-android:1.2.1`
- `androidx.datastore:datastore-preferences-core:1.2.1`
- `androidx.datastore:datastore-preferences-core-android:1.2.1`
- `androidx.datastore:datastore-preferences-proto:1.2.1`
- `androidx.documentfile:documentfile:1.0.0`
- `androidx.drawerlayout:drawerlayout:1.0.0`
- `androidx.dynamicanimation:dynamicanimation:1.0.0`
- `androidx.emoji2:emoji2:1.4.0`
- `androidx.fragment:fragment:1.8.9`
- `androidx.graphics:graphics-path:1.0.1`
- `androidx.interpolator:interpolator:1.0.0`
- `androidx.legacy:legacy-support-core-utils:1.0.0`
- `androidx.lifecycle:lifecycle-common:2.11.0`
- `androidx.lifecycle:lifecycle-common-java8:2.11.0`
- `androidx.lifecycle:lifecycle-common-jvm:2.11.0`
- `androidx.lifecycle:lifecycle-livedata:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-process:2.11.0`
- `androidx.lifecycle:lifecycle-runtime:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-android:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-compose:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-android:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0`
- `androidx.loader:loader:1.0.0`
- `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0`
- `androidx.navigationevent:navigationevent:1.0.0`
- `androidx.navigationevent:navigationevent-android:1.0.0`
- `androidx.navigationevent:navigationevent-compose:1.0.0`
- `androidx.navigationevent:navigationevent-compose-android:1.0.0`
- `androidx.print:print:1.0.0`
- `androidx.profileinstaller:profileinstaller:1.4.1`
- `androidx.savedstate:savedstate:1.4.0`
- `androidx.savedstate:savedstate-android:1.4.0`
- `androidx.savedstate:savedstate-compose:1.4.0`
- `androidx.savedstate:savedstate-compose-android:1.4.0`
- `androidx.savedstate:savedstate-ktx:1.4.0`
- `androidx.startup:startup-runtime:1.2.0`
- `androidx.tracing:tracing:1.2.0`
- `androidx.transition:transition:1.6.0`
- `androidx.vectordrawable:vectordrawable:1.1.0`
- `androidx.vectordrawable:vectordrawable-animated:1.1.0`
- `androidx.versionedparcelable:versionedparcelable:1.1.1`
- `androidx.viewpager:viewpager:1.0.0`
- `androidx.window:window:1.5.0`
- `androidx.window:window-core:1.5.0`
- `androidx.window:window-core-android:1.5.0`
- `com.google.guava:listenablefuture:1.0`
- `com.squareup.okhttp3:okhttp:5.4.0`
- `com.squareup.okhttp3:okhttp-android:5.4.0`
- `com.squareup.okio:okio:3.17.0`
- `com.squareup.okio:okio-jvm:3.17.0`
- `org.jetbrains.kotlin:kotlin-stdlib:2.4.10`
- `org.jetbrains.kotlin:kotlin-stdlib-common:2.4.10`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0`
- `org.jetbrains:annotations:23.0.0`
- `org.jspecify:jspecify:1.0.0`

## DataStore external protobuf

- `androidx.datastore:datastore-preferences-external-protobuf:1.2.1`

This artifact repackages the protobuf 4.28.2 runtime. Its unmodified upstream
BSD-3-Clause licence, including the Google copyright notice, is bundled as
`PROTOBUF-4.28.2-LICENSE.txt`; the exact upstream source tag is
`https://github.com/protocolbuffers/protobuf/tree/v28.2`.

## WebRTC SDK Android and WebRTC

- `io.github.webrtc-sdk:android-prefixed-stripped:144.7559.09`

The wrapper's MIT text is bundled as `WEBRTC_SDK_ANDROID_LICENSE.txt`. WebRTC's
BSD-3-Clause terms and the complete notice set for its incorporated components
are bundled as `WEBRTC.md`. The `README.ijg` file referenced by that generated
notice bundle is retained separately, byte-for-byte, from the exact libjpeg-
turbo revision used by the pinned WebRTC source. See the project-level
`THIRD_PARTY_NOTICES.md` for provenance and reviewed SHA-256 values.
