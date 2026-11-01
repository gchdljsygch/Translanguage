# Third-party notices

## TypeScript ANTLR grammar and Java base classes

Source: https://github.com/antlr/grammars-v4/tree/b96e04db9d54bb313e542cc3e6ee913900a21520/javascript/typescript

Upstream revision: `b96e04db9d54bb313e542cc3e6ee913900a21520`.
Retrieved on 2026-11-01. All four downloaded originals were compared with this pinned revision.

Reference-only files (excluded from production classes and published source/Javadoc artifacts):
- `src/reference/antlr/TypeScriptLexer.g4`
- `src/reference/antlr/TypeScriptParser.g4`
- `src/reference/java/com/pingsu/reference/antlr/TypeScriptLexerBase.java`
- `src/reference/java/com/pingsu/reference/antlr/TypeScriptParserBase.java`

Local changes: Java packaging; type-only module syntax; contextual modifiers;
generic calls, arrows and defaults; type assertions and satisfies; readonly types;
ambient declarations; unambiguous export parsing; variable/expression statement
boundaries; property and method parsing; automatic semicolon insertion through
hidden trivia; nested template interpolation brace tracking; hashbang trivia; logical assignment operators; optional calls; dynamic import and import.meta; class accessor alternative precedence.

The original grammar copyright/license headers are retained. Their MIT terms follow:

```text
The MIT License (MIT)

Copyright (c) 2014 by Bart Kiers (original author) and Alexandre Vitorelli (contributor -> ported to CSharp)
Copyright (c) 2017 by Ivan Kochurkin (Positive Technologies):
    added ECMAScript 6 support, cleared and transformed to the universal grammar.
Copyright (c) 2018 by Juan Alvarez (contributor -> ported to Go)
Copyright (c) 2019 by Andrii Artiushok (contributor -> added TypeScript support)
Copyright (c) 2024 by Andrew Leppard (www.wegrok.review)

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

## ANTLR 4 runtime and build generator, version 4.13.2

Source: https://github.com/antlr/antlr4/tree/4.13.2
The runtime is confined to the reference/test source set; the generator is a build dependency for that reference parser only. Neither is a production dependency.

```text
Copyright (c) 2012-2022 The ANTLR Project. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

3. Neither name of copyright holders nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

JUnit is used only for tests and is not part of the published library artifact.

## Closure Compiler, version v20240317

Source: https://github.com/google/closure-compiler/tree/v20240317
License: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
Copyright The Closure Compiler Authors.

The compiler is confined to `referenceImplementation` and the test classpath. It is
resolved from Maven Central for frozen baseline comparisons only. Its runtime helpers
occur only in historical test evidence and reference output, never in production code.
The dependency jar carries its own LICENSE, THIRD_PARTY_NOTICES and META-INF notices.
The original local AST adaptations are retained only in the independent reference package.

Acorn 8.15.0 (https://github.com/acornjs/acorn, MIT license) is loaded from its WebJar only
in tests to independently validate ECMAScript 2015 and 2021 output. It is not a runtime
dependency of library consumers.

## Zero-dependency migration reference source set (2.0.0-SNAPSHOT)

P2–P7 are complete: the production lexer, parser, TypeScript transforms, JavaScript tree,
scopes, printers, validators and all ES6/ES2021 lowering are project-authored Java code.
`internal/lowering/AsyncLowering` and `AsyncHelpers` are independently authored;
no Closure implementation or generated helper body was copied into them. ANTLR and
Closure are absent from production compile/runtime configurations and publication metadata.
P8 release and offline-environment acceptance remain outstanding.

The four grammar/base-class files are retained only under `src/reference/antlr/` and `src/reference/java/com/pingsu/reference/antlr/` for test-only comparison. Their original copyright/license headers remain intact. The Java base-class package is changed to `com.pingsu.reference.antlr`. The original project transpiler implementation is preserved in `src/reference/java/com/pingsu/reference/transpiler/` with package references renamed to `com.pingsu.reference.*`; it is not included in the main, sources or javadoc JAR. Reference dependencies are declared on `referenceImplementation`, inherited only by the test configuration.

`docs/baseline/2026-11-01/compilation-evidence.zip` contains historical generated JavaScript, including Closure-generated helper code, strictly as test evidence. It is copied only into test resources and never into production artifacts. The new `internal/parser/SourceLexer` and `syntax` model are project-authored code; the lexer and recursive-descent parser now serve production compilation.

## Javadoc documentation assets

The Javadoc artifact is generated with the JDK 17 standard doclet. Its search indices,
script.js, search.js, and script-dir/jquery-3.7.1.min.js / jquery-ui.min.js are documentation
UI resources, not compiler implementations or runtime dependencies. The JDK-generated
legal notices and script headers remain in the Javadoc artifact. The consumer audit uses
an explicit path allowlist only for that artifact, compares bytes with the doclet output,
and records each allowed script SHA-256. Main and source JARs permit no standalone JS assets.

## Release acceptance infrastructure (not distributed)

P8 additionally runs a RAM-only Linux guest under QEMU to verify the built library
with no network device, build tools or dependency caches. The developer downloads
QEMU Windows binaries, an Alpine Linux kernel/initramfs and system libraries, and
Azul Zulu JDK 17/25 archives separately. Their versions, origins and SHA-256 hashes
are recorded in `docs/verification/2026-11-02-p8/`; none of these binaries is copied
into the main, sources or Javadoc JAR, or declared in consumer metadata. The guest
contains standard operating-system support libraries for its JDK, not additional
Java dependencies of Translanguage. QEMU/Alpine/BusyBox/OpenJDK retain their own
upstream licenses; this repository does not redistribute their downloaded archives.
