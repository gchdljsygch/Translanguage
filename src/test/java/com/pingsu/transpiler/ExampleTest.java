package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** The bundled example is only sample input for library consumers, never something the library runs itself. */
@org.junit.jupiter.api.extension.ExtendWith(CompilationEvidence.class)
class ExampleTest {
    @org.junit.jupiter.api.DisplayName("EX-001 transpilesBundledExampleThroughThePublicApi")
    @Test void transpilesBundledExampleThroughThePublicApi() throws Exception {
        String source;
        try (InputStream input = getClass().getResourceAsStream("/hello.ts")) {
            assertNotNull(input, "examples/hello.ts must be on the test resource path");
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        TranspileResult result = CompilationEvidence.compiler().transpile("examples/hello.ts", source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String javaScript = result.javaScript();
        assertFalse(javaScript.contains("interface Named"), javaScript);
        assertTrue(javaScript.contains("class Greeter"), javaScript);
        assertTrue(javaScript.contains("var Status"), javaScript);
    }
}
