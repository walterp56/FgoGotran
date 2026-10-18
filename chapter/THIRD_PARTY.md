# Chapter dependency and format references

The Chapter module does not register a security provider or share the OCR app's code.

## Bouncy Castle

`org.bouncycastle:bcprov-jdk18on:1.86` supplies the lightweight Rijndael-256/CBC/PKCS7
primitives. Pinned from the official release listed on 2026-10-16:
https://www.bouncycastle.org/download/bouncy-castle-java/

Copyright (c) 2000 - 2026 The Legion of the Bouncy Castle Inc.
(https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify,
merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be included in all copies
or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Shizuku API and provider

`dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5` supply the
permission/binder/UserService bridge. Official API documentation and MIT licence:
https://github.com/RikkaApps/Shizuku-API
https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE

Copyright (c) 2021 RikkaW

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

The official Shizuku manager is a separate app, not embedded in FgoGotran. Its
installation/start was explicitly approved for the disposable test emulator only.
The APK uses the standard protected provider; binding/permission/scanning is
feature-local. The reader deliberately supports non-root shell mode only.

## Other format dependencies and research

`org.tukaani:xz:1.12` supplies bounded raw LZMA decoding; BSD Zero Clause licence.
Official release/licence: https://tukaani.org/xz/java.html

`org.apache.commons:commons-compress:1.28.0` supplies bzip2 streams; Apache License 2.0.
Official release: https://commons.apache.org/proper/commons-compress/
Licence text: https://www.apache.org/licenses/LICENSE-2.0
Transitive Commons libraries retain their dependency-provided licences/notices.

Unity format cross-reference and independent **desktop-only** verification:
https://github.com/K0lb3/UnityPy (MIT). UnityPy 1.25.4 is not an Android dependency;
its optional local installation and authentic inputs are Git-ignored. No source
files from UnityPy were vendored into this module.

https://github.com/hexstr/FGOAssetsModifyTool

This reference identifies candidate wrappers, installed-vs-server manifest formats
and physical-name transforms. Its licence was not established. No source files or
compiled binaries from that project are redistributed here. Independent bounded
parsing/encoding is tested against owner-authorised local fixtures; unsupported
formats must fail closed. No account/session data or private service is used.
