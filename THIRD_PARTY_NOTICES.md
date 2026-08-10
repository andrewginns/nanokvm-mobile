# Third-party notices

## Android and Kotlin runtime components

The exact external components resolved for `releaseRuntimeClasspath` are
listed in
`app/src/main/assets/open_source_licenses/RUNTIME_COMPONENT_LICENSES.md`. That
index is verified against the resolved graph during every app build and is
available offline from the app's About screen.

AndroidX, Kotlin, KotlinX, OkHttp, Okio, JSpecify, and related runtime
components in that index are distributed under Apache License 2.0. The complete
terms are preserved at
`app/src/main/assets/open_source_licenses/APACHE-2.0.txt` and are bundled in the
APK. The external protobuf component's BSD-3-Clause terms are reproduced in the
unmodified `PROTOBUF-4.28.2-LICENSE.txt` bundled in the APK.

The canonical CycloneDX SBOM published with each release is the complete
machine-readable resolved release-runtime graph. It complements, but does not
replace, these licence texts and notices.

## WebRTC SDK Android wrapper

Artifact: `io.github.webrtc-sdk:android-prefixed-stripped:144.7559.09`

Source: <https://github.com/webrtc-sdk/android/tree/v144.7559.09>

The exact upstream wrapper licence is bundled at
`app/src/main/assets/open_source_licenses/WEBRTC_SDK_ANDROID_LICENSE.txt`;
its SHA-256 is
`e6b282fe6c0fb353928923470457f31b44cbab203effd60c0cde4a5bb96c8aec`.

Copyright (c) 2023 WebRTC SDKs

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## WebRTC

Exact source: <https://github.com/webrtc-sdk/webrtc/tree/b1800a61db8320af5c14456c13622d8b85b1ed39>

Copyright (c) 2011, The WebRTC project authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of Google nor the names of its contributors may be used to
   endorse or promote products derived from this software without specific
   prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

WebRTC incorporates further third-party components. The generated notice bundle
for the exact pinned wrapper release is preserved byte-for-byte at
`app/src/main/assets/open_source_licenses/WEBRTC.md` and is included in the
application for offline review. Its provenance is the upstream
[`v144.7559.09` notice file](https://github.com/webrtc-sdk/android/blob/v144.7559.09/Licenses/WEBRTC.md),
and its SHA-256 is
`d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e`.

That generated file references, but does not itself contain, libjpeg-turbo's
`README.ijg`. The exact referenced file is bundled separately at
`app/src/main/assets/open_source_licenses/README.ijg` from libjpeg-turbo commit
[`6383cf609c1f63c18af0f59b2738caa0c6c7e379`](https://chromium.googlesource.com/chromium/deps/libjpeg_turbo/+/6383cf609c1f63c18af0f59b2738caa0c6c7e379/README.ijg);
its SHA-256 is
`75815e3bf6484201a3c3d17a1bbf10f2e8e3237f84df10a2357ea896db2a81d6`.
As required there: this software is based in part on the work of the Independent
JPEG Group.

Review and replace the pinned dependency, both notice files, and their source
revisions together when updating WebRTC.
