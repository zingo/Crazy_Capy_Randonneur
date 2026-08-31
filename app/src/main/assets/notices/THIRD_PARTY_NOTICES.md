# Third-Party Notices

This file lists the third-party libraries, tools, and data the **Crazy Capy
Randonneur** project uses, together with the licenses that apply to them.

The project itself is licensed under the Apache License 2.0 – see
[LICENSE](LICENSE). The notices below apply to code, components, and data
that are **not** authored by this project.

> Note: license texts are reproduced at the end of this file. For the ODbL
> database license and the Eclipse Public License, the license text is very
> long; a link to the authoritative text is given instead.

---

## Map tiles & map data

| Component | Source | License |
| --- | --- | --- |
| Map tiles, dark & light styles | [OpenFreeMap](https://openfreemap.org/) (public instance, `tiles.openfreemap.org`) | Styles: MIT. Tile + map data: see below. |
| Map data | [OpenStreetMap](https://www.openstreetmap.org/) contributors | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/) |
| Map schema | [OpenMapTiles](https://openmaptiles.org/) | ODbL 1.0 (derive from OSM) |

Per OpenFreeMap's terms, attribution is required. The MapLibre SDK adds
OpenFreeMap's attribution control to the map automatically, which satisfies
this requirement (`OpenFreeMap © OpenMapTiles, Data from OpenStreetMap`).
You must keep the MapLibre attribution control enabled.

## Cross-app radar contract (dual-licensed)

The rear-radar integration defines a small AIDL interface in
`app/src/main/aidl/.../radar/` — the wire contract shared with the optional
[android-bike-radar-overlay](https://github.com/partymola/android-bike-radar-overlay)
app. These interface files are deliberately dual-licensed **`Apache-2.0 OR
0BSD`** so they are easy to use and share across projects:

- Crazy Capy Randonneur uses them under its own **Apache License 2.0**.
- A GPL-licensed project can take the **0BSD** option, which imposes no
  obligations.

Only the factual wire-format contract (interface and parcelable layout) is
shared; no GPL code is imported into this app.

## Optional overlay app (android-bike-radar-overlay)

When installed, the separate
[android-bike-radar-overlay](https://github.com/partymola/android-bike-radar-overlay)
app (package `es.jjrh.bikeradar`, **GPL-3.0-or-later**) can provide a live
rear-radar stream. The two apps are separate APKs that communicate over the
Android binder with no shared code; this app does not bundle or copy any of the
overlay app's code.

## Runtime libraries (direct dependencies)

| Library | Version | License | Copyright |
| --- | --- | --- | --- |
| MapLibre Android SDK | 12.3.1 | BSD-2-Clause | © MapLibre contributors, © Mapbox |
| MapLibre Android GeoJSON (org.maplibre.gl:android-sdk-geojson) | 6.0.1 | Apache-2.0 | MapLibre contributors |
| kXML 2 (XML pull parser) | 2.3.0 | BSD-style (kXML classes); Public Domain (org.xmlpull.v1 API) | Stefan Haustein |
| AndroidX Core KTX | 1.16.0 | Apache-2.0 | The Android Open Source Project |
| AndroidX Activity Compose | 1.10.1 | Apache-2.0 | The Android Open Source Project |
| AndroidX Lifecycle (runtime, compose, viewmodel) | 2.9.0 | Apache-2.0 | The Android Open Source Project |
| Jetpack Compose BOM (UI, Foundation, Material3, Material icons, tooling) | 2025.06.01 (Compose 1.8.x, Material3 1.3.x) | Apache-2.0 | The Android Open Source Project |
| Gson (transitive, via MapLibre GeoJSON) | 2.10.1 | Apache-2.0 | Google / The Android Open Source Project |
| OkHttp (transitive, via MapLibre) | 4.x | Apache-2.0 | Square, Inc. |

## Test-only dependencies

| Library | License | Copyright |
| --- | --- | --- |
| JUnit 4 | EPL-1.0 (junit.org) | JUnit contributors |
| AndroidX Test (junit, runner, core) | Apache-2.0 | The Android Open Source Project |

## Build tooling (not shipped)

| Tool | License | Copyright |
| --- | --- | --- |
| Gradle + Gradle Wrapper | Apache-2.0 | Gradle Inc. |
| Android Gradle Plugin 8.13.0 | Apache-2.0 | The Android Open Source Project |
| Kotlin 2.1.21 (compiler, stdlib, compose plugin) | Apache-2.0 | JetBrains / Kotlin Foundation |
| Android platform SDK | Apache-2.0 | The Android Open Source Project |

---

## MapLibre Native components

MapLibre Android bundles parts of **MapLibre Native**. MapLibre Native and
its platform wrappers are licensed under the **2-Clause BSD License**
(`mapbox-gl-native` cleans including forks). MapLibre Native itself also
incorporates third-party code; the aggregate set of licenses used by
MapLibre Native includes the BSD-2-Clause, ISC, and MIT licenses. See
`LICENSE.md` inside the `maplibre-native` repository for the full text.

The MapLibre Android SDK also pulls in `android-sdk-turf` (6.0.1,
BSD-2-Clause) and `maplibre-android-gestures` (0.0.4, MIT), both published
by the MapLibre project under the same organization.

---

# License texts

## Apache License 2.0

See [LICENSE](LICENSE) in this repository (included in-app as part of the
notice viewer). The Apache License 2.0 text is also published at
<http://www.apache.org/licenses/LICENSE-2.0>.

## BSD 2-Clause License

Applies to: **MapLibre Android SDK** and (in part) MapLibre Native.

```
Copyright (c) <year>, <copyright holder>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

## BSD-style license (kXML 2)

Applies to the kXML2 classes (all classes below the `org.kxml2` package):

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice,
this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

The **Public Domain** applies to the `XmlPull` API (all classes directly in
the `org.xmlpull.v1` package), as published on the kXML project (public
domain declaration at <https://creativecommons.org/licenses/publicdomain>).

## 0BSD (Zero-Clause BSD)

Applies to: the cross-app AIDL contract files (offered alongside Apache-2.0;
either license may be chosen).

```
Copyright (C) 2026 Crazy Capy Randonneur contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION
OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## MIT License

Applies to: **OpenFreeMap styles/servers** (MIT), certain MapLibre Native
third-party components.

```
MIT License

Copyright (c) <year> <copyright holder>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Eclipse Public License 1.0 (test-only)

Applies to: **JUnit 4**. The full text is available at
<https://www.eclipse.org/legal/epl-v10.html>. Key terms: this is a
strong-copyleft-but-Apache-compatible license for the JUnit test library;
JUnit is used here only for the test sources.

## ODbL 1.0 (OpenStreetMap data)

Applies to the map data rendered in the app. The full text is available at
<https://opendatacommons.org/licenses/odbl/1.0/>. In short, you are free to
share and adapt the data, provided you attribute
**(c) OpenStreetMap contributors** and share your adapted data under the
same or a similar license if you distribute a derivative database.

## Public Domain (CC0 waiver — OpenStreetMap exports)

Not used directly; noted for completeness for kXML's pull API above.

---

*This file is mirrored inside the app so users can always read it
(see the Settings > Licenses screen).*