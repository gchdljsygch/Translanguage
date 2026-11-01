package com.pingsu.transpiler;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen outputs and a separately packaged old implementation both check the production backend. */
class ReferenceCompatibilityTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @Test void referenceClassesCannotShadowProduction() {
        var production = TypeScriptTranspiler.class.getProtectionDomain().getCodeSource().getLocation();
        var reference = com.pingsu.reference.transpiler.TypeScriptTranspiler.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotEquals(production, reference);
        assertNotEquals(TypeScriptTranspiler.class.getPackageName(),
                com.pingsu.reference.transpiler.TypeScriptTranspiler.class.getPackageName());
    }
    @TestFactory List<DynamicTest> frozenBaselineAndIndependentReferenceAgree() throws Exception {
        Map<String, Map<String, String>> fixtures = new LinkedHashMap<>();
        try (var resource = getClass().getResourceAsStream("/baseline/compilation-evidence.zip")) {
            assertNotNull(resource, "Frozen P0 baseline must be present");
            try (var archive = new ZipInputStream(resource)) {
                java.util.zip.ZipEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || !name.contains("/compile-")) continue;
                    int split = name.lastIndexOf('/');
                    fixtures.computeIfAbsent(name.substring(0, split), key -> new LinkedHashMap<>())
                            .put(name.substring(split + 1), new String(archive.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        assertFalse(fixtures.isEmpty());
        List<DynamicTest> tests = new ArrayList<>();
        fixtures.forEach((id, files) -> {
            // These cases deliberately inject custom passes/emitters; their original JUnit assertions
            // remain authoritative. A default backend cannot replay a caller-supplied Java lambda.
            if (id.contains("defaultsAndExtensionsRemainUsable")) return;
            assertTrue(files.keySet().containsAll(List.of("input.ts", "output.mjs", "result.txt")), id);
            String resultText = files.get("result.txt");
            String[] lines = resultText.split("\n", 4);
            String targetName = lines[0].substring("target=".length());
            String sourceName = lines[1].substring("sourceName=".length());
            boolean successful = Boolean.parseBoolean(lines[2].substring("successful=".length()));
            tests.add(DynamicTest.dynamicTest("BASELINE/" + targetName + "/" + id, () -> {
                String source = files.get("input.ts");
                var expected = new com.pingsu.reference.transpiler.TypeScriptTranspiler(
                        com.pingsu.reference.transpiler.JavaScriptTarget.valueOf(targetName)).transpile(sourceName, source);
                var actual = new TypeScriptTranspiler(JavaScriptTarget.valueOf(targetName)).transpile(sourceName, source);
                assertEquals(successful, expected.successful(), "Reference drift from frozen baseline");
                assertEquals(files.get("output.mjs"), expected.javaScript(), "Reference output drift");
                assertEquals(lines[3], expected.diagnostics().toString(), "Reference diagnostic drift");
                assertEquals(expected.successful(), actual.successful());
                if (targetName.equals("PRESERVE")) assertEquals(expected.javaScript(), actual.javaScript());
                else if (actual.successful()) {
                    var runtime = new JavaScriptRuntime(temporary);
                    runtime.syntax(JavaScriptTarget.valueOf(targetName), actual.javaScript());
                    // These frozen fixtures deliberately name uninstalled business modules.
                    // They remain syntax/module-preservation cases; runnable fixtures compare
                    // observable output against the frozen independent implementation.
                    if (!source.contains("uninstalled-package")) assertEquals(runtime.run(expected.javaScript()), runtime.run(actual.javaScript()));
                    else for (String module : List.of("uninstalled-package", "./missing.js", "another-package")) assertTrue(actual.javaScript().contains(module));
                }
                // The 2.x frontend owns diagnostic wording. Preserve code, source and exact position.
                if (expected.diagnostics().stream().noneMatch(d -> d.code().equals("JS_TARGET"))) {
                    assertEquals(expected.diagnostics().stream().map(d -> List.of(d.sourceName(), d.line(), d.column(), d.code())).toList(),
                            actual.diagnostics().stream().map(d -> List.of(d.sourceName(), d.line(), d.column(), d.code())).toList());
                } else {
                    assertEquals(expected.diagnostics().stream().map(d -> List.of(d.sourceName(),d.code())).toList(),actual.diagnostics().stream().map(d -> List.of(d.sourceName(),d.code())).toList());
                    assertTrue(actual.diagnostics().stream().allMatch(d -> d.line()>0 && d.column()>0));
                }
                assertTrue(actual.diagnostics().stream().allMatch(d -> !d.message().isBlank()));
            }));
        });
        return tests;
    }
}
