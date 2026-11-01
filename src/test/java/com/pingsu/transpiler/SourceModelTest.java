package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.source.SourceText;
import com.pingsu.transpiler.syntax.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SourceModelTest {
    @Test void unicodeLocationsCountCodePointsAndAllLineTerminators() {
        SourceText source = new SourceText("😀x\r\ny\rz\nu\u2028v\u2029end");
        assertEquals(new SourceText.Location(1, 2), source.location(2));
        assertEquals(new SourceText.Location(2, 1), source.location(5));
        assertEquals(new SourceText.Location(3, 1), source.location(7));
        assertEquals(new SourceText.Location(4, 1), source.location(9));
        assertEquals(new SourceText.Location(5, 1), source.location(11));
        assertEquals(new SourceText.Location(6, 1), source.location(13));
        assertEquals(new SourceText.Location(6, 4), source.location(source.length()));
        assertThrows(IllegalArgumentException.class, () -> source.location(1));
        assertThrows(IllegalArgumentException.class, () -> source.slice(new SourceRange(0, 99)));
        assertThrows(IllegalArgumentException.class, () -> new SourceRange(3, 2));
        assertFalse(SourceRange.at(2).overlaps(new SourceRange(1, 3)));
        assertTrue(new SourceRange(1, 3).overlaps(new SourceRange(2, 4)));
    }
    @Test void boundariesAndStableInsertionsHaveDefinedOrder() {
        var edits = new SourceEdits("A😀BC");
        edits.replace(new SourceRange(1, 3), "X");
        edits.replace(SourceRange.at(1), "before-1");
        edits.replace(SourceRange.at(1), "before-2");
        edits.replace(SourceRange.at(3), "after");
        edits.append("end-1"); edits.append("end-2");
        assertEquals("Abefore-1before-2XafterBCend-1end-2", edits.render());
        assertEquals(edits.render(), edits.render());
    }
    @Test void conflictsAndContainmentFailBeforeMutatingTheEditSet() {
        for (SourceRange conflict : List.of(new SourceRange(1, 3), new SourceRange(0, 4),
                new SourceRange(2, 4), new SourceRange(2, 3), SourceRange.at(2))) {
            var edits = new SourceEdits("abcd");
            edits.replace(new SourceRange(1, 3), "X");
            assertThrows(IllegalStateException.class, () -> edits.replace(conflict, "bad"));
            assertEquals("aXd", edits.render());
        }
        var edits = new SourceEdits("abcd");
        edits.replace(SourceRange.at(2), "I");
        assertThrows(IllegalStateException.class, () -> edits.replace(new SourceRange(1, 3), "bad"));
        assertEquals("abIcd", edits.render());
    }
    @Test void publicSyntaxViewSupportsTraversalAndIndependentEdits() {
        String original = "\uFEFF// 😀\r\nconst value: number = 1;";
        var visits = new ArrayList<String>();
        Transpiler compiler = new TypeScriptTranspiler(List.of(context -> {
            assertEquals("// 😀\r\nconst value: number = 1;", context.source());
            assertEquals("extension.ts", context.sourceName());
            assertEquals(context.source(), context.tokens().stream().map(Token::text).reduce("", String::concat));
            assertEquals(TokenKind.EOF, context.tokens().get(context.tokens().size() - 1).kind());
            assertThrows(UnsupportedOperationException.class, () -> context.tokens().clear());
            assertThrows(UnsupportedOperationException.class, () -> context.program().children().clear());
            context.program().walk(node -> {
                visits.add(node.kind());
                if (node.kind().equals("typeAnnotation")) {
                    assertEquals(": number", context.slice(node));
                    context.remove(node);
                    return false;
                }
                return true;
            });
            Token one = context.tokens().stream().filter(token -> token.text().equals("1")).findFirst().orElseThrow();
            context.replace(one, "2"); context.insertAfter(one, " + 3");
        }));
        assertEquals("// 😀\r\nconst value = 2 + 3;", compiler.transpile("extension.ts", original).javaScript());
        assertTrue(visits.contains("variableDeclaration"));
        assertTrue(visits.contains("typeAnnotation"));
    }
    @Test void extensionDiagnosticsUseBomNormalizedCodePointCoordinatesAndStopPasses() {
        String source = "\uFEFF// 😀\r\nconst text = '😀';\u2028const value = 2;";
        var compiler = new TypeScriptTranspiler(List.of(context -> {
            Token two = context.tokens().stream().filter(t -> t.text().equals("2")).findFirst().orElseThrow();
            context.error(two, "CUSTOM", "blocked");
            assertEquals(3, two.line()); assertEquals(15, two.column());
            assertThrows(UnsupportedOperationException.class, () -> context.diagnostics().clear());
        }, context -> fail("A failing pass must stop the pipeline")));
        var result = compiler.transpile("位置.ts", source);
        assertEquals("", result.javaScript()); assertFalse(result.successful());
        assertEquals(new Diagnostic("位置.ts", 3, 15, "CUSTOM", "blocked"), result.diagnostics().get(0));
    }
    @Test void extensionConflictsDoNotPoisonReusableCompiler() {
        var compiler = new TypeScriptTranspiler(List.of(context -> {
            if (context.source().isEmpty()) return;
            context.replace(new SourceRange(0, 5), "x");
            context.replace(new SourceRange(1, 3), "y");
        }));
        assertThrows(IllegalStateException.class, () -> compiler.transpile("a.ts", "const x = 1;"));
        assertTrue(compiler.transpile("empty.ts", "").successful());
    }
    @Test void seededIndependentEditsPreserveUntouchedTrivia() {
        Random random = new Random(0x5EED);
        for (int trial = 0; trial < 100; trial++) {
            String source = "//😀\r\n" + "abcdefghij".repeat(8);
            var edits = new SourceEdits(source); StringBuilder expected = new StringBuilder(source);
            for (int offset = source.length() - 1; offset >= 7; offset -= 3) {
                if (random.nextBoolean()) { edits.replace(new SourceRange(offset, offset + 1), "XY"); expected.replace(offset, offset + 1, "XY"); }
            }
            assertEquals(expected.toString(), edits.render(), "seed=0x5EED trial=" + trial);
        }
    }
    @Test void traversalDoesNotConsumeTheJavaCallStack() {
        SyntaxNode node = new BranchNode("leaf", SourceRange.at(0), List.of());
        for (int i = 0; i < 20000; i++) node = new BranchNode("parent", SourceRange.at(0), List.of(node));
        int[] count = {0}; node.walk(n -> { count[0]++; return true; });
        assertEquals(20001, count[0]);
    }
}
