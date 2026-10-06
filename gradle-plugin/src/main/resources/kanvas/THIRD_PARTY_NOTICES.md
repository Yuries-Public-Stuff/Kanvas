# Third-Party Notices

Kanvas is licensed under the Apache License 2.0. Some distributed artifacts include third-party code under other compatible licenses.

## OW2 ASM

Kanvas currently bundles OW2 ASM modules into the Java instrumentation agent.

Components:

~~~text
org.ow2.asm:asm
org.ow2.asm:asm-commons
~~~

License: BSD 3-Clause

Copyright (c) 2000-2011 INRIA, France Telecom  
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Vulkan-Headers

The native build may fetch Khronos Vulkan-Headers v1.4.309 when Vulkan headers are not supplied by the local SDK.

The Vulkan generated headers used by the project carry SPDX identifiers including:

~~~text
Apache-2.0 OR MIT
~~~

See the KhronosGroup/Vulkan-Headers project for the complete license set.

## Other build/test dependencies

Kotlin, Compose Multiplatform, Skiko, JUnit, and publication tooling are documented in [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).

Target applications may bring additional dependencies when packaged. Those remain the application's responsibility and should be audited as part of distribution.
