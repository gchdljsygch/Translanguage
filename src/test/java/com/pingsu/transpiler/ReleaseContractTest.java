package com.pingsu.transpiler;

import com.pingsu.reference.antlr.TypeScriptLexer;
import com.pingsu.reference.antlr.TypeScriptParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed release contract; expectations are reviewed fixtures, never derived from production output. */
class ReleaseContractTest {
    @TempDir Path temporary;
    private Path fixtures() { return Path.of("src/test/resources/p8"); }
    private List<String[]> cases() throws Exception {
        return Files.readAllLines(fixtures().resolve("cases.tsv"), StandardCharsets.UTF_8).stream()
                .skip(1).filter(s -> !s.isBlank()).map(s -> s.split("\t", -1)).toList();
    }
    @TestFactory List<DynamicTest> threeTargetContract() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        for (String[] row : cases()) {
            assertEquals(6, row.length); assertTrue(identifiers.add(row[0]), "duplicate case ID");
            for (int i=0;i<3;i++) {
                JavaScriptTarget target = JavaScriptTarget.valueOf(new String[]{"PRESERVE","ES6","ES2021"}[i]);
                String expectation=row[i+1];
                tests.add(DynamicTest.dynamicTest("P8/"+row[0]+"/"+target, () -> {
                    String input=Files.readString(fixtures().resolve(row[0]+".ts"));
                    var result=new TypeScriptTranspiler(target).transpile(row[0]+".ts",input);
                    if (!expectation.equals("OK")) {
                        assertFalse(result.successful()); assertEquals("",result.javaScript());
                        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals(expectation)), result.diagnostics().toString());
                        assertTrue(result.diagnostics().stream().allMatch(d -> d.line()>0&&d.column()>0));
                        return;
                    }
                    assertTrue(result.successful(),result.diagnostics().toString());
                    var runtime=new JavaScriptRuntime(temporary);
                    runtime.syntax(target == JavaScriptTarget.PRESERVE ? 2022 : target == JavaScriptTarget.ES6 ? 2015 : 2021,
                            row[4],result.javaScript());
                    if (row[5].equals("run")) {
                        Path expectedFile=fixtures().resolve(row[0]+"-"+target+".stdout");
                        if(!Files.exists(expectedFile))expectedFile=fixtures().resolve(row[0]+".stdout");
                        String expected=Files.readString(expectedFile).replace("\r\n","\n").strip();
                        assertEquals(expected,runtime.run(result.javaScript(),row[4]));
                    }
                }));
            }
        }
        return tests;
    }
    @Test void namespaceReexportPreservesLiveBindingsAndAvoidsEscapedNames() throws Exception {
        Files.writeString(temporary.resolve("dep.mjs"), "export let value=1;export function change(){value=2}");
        String source="const $tl$namespace$0=0;const \\u0024tl$namespace$1=1;export * as ns from './dep.mjs';";
        var output=new TypeScriptTranspiler(JavaScriptTarget.ES6).transpile("bridge.ts",source);
        assertTrue(output.successful(),output.diagnostics().toString());
        var runtime=new JavaScriptRuntime(temporary);runtime.syntax(JavaScriptTarget.ES6,output.javaScript());
        Files.writeString(temporary.resolve("bridge.mjs"),output.javaScript());
        assertEquals("1\n2 true",runtime.run("import {ns} from './bridge.mjs';import * as original from './dep.mjs';console.log(ns.value);ns.change();console.log(ns.value,ns===original);"));
        var program=com.pingsu.transpiler.internal.js.JsProgram.parse("raw.js","export * as ns from './dep.mjs';");
        assertThrows(com.pingsu.transpiler.internal.js.TargetFailure.class,()->new com.pingsu.transpiler.internal.emit.SyntaxTargetValidator().validate(program,JavaScriptTarget.ES6));
        var dynamic=new TypeScriptTranspiler(JavaScriptTarget.ES6).transpile("eval.ts","eval('0');export * as ns from './dep.mjs';");
        assertFalse(dynamic.successful());assertEquals("",dynamic.javaScript());
    }
    @Test void referenceRuleInventory() throws Exception {
        Map<String,Set<String>> reached=new TreeMap<>();
        for (String[] row:cases()) {
            var lexer=new TypeScriptLexer(CharStreams.fromString(Files.readString(fixtures().resolve(row[0]+".ts"))));
            lexer.removeErrorListeners();
            var parser=new TypeScriptParser(new CommonTokenStream(lexer)); parser.removeErrorListeners();
            ParseTree tree=parser.program();
            if(parser.getNumberOfSyntaxErrors()!=0) continue;
            ParseTreeWalker.DEFAULT.walk(new ParseTreeListener() {
                public void visitTerminal(TerminalNode node) {}
                public void visitErrorNode(ErrorNode node) {}
                public void enterEveryRule(ParserRuleContext node) {
                    reached.computeIfAbsent(parser.getRuleNames()[node.getRuleIndex()],k->new TreeSet<>()).add(row[0]);
                }
                public void exitEveryRule(ParserRuleContext node) {}
            },tree);
        }
        Path report=Path.of("build/reports/release/reference-rule-visits.tsv");Files.createDirectories(report.getParent());
        List<String> lines=new ArrayList<>(List.of("rule\tfixtures"));
        for(String rule:TypeScriptParser.ruleNames) lines.add(rule+"\t"+String.join(",",reached.getOrDefault(rule,Set.of())));
        Files.write(report,lines,StandardCharsets.UTF_8);
        Path ledger=fixtures().resolve("rules.tsv");
        assertTrue(Files.exists(ledger),"The reviewed 156-rule ledger is required; observed visits written to "+report);
        Set<String> rules=new HashSet<>();Set<String> ids=new HashSet<>();for(String[] row:cases())ids.add(row[0]);
        for(String line:Files.readAllLines(ledger).subList(1,Files.readAllLines(ledger).size())) {
            String[] row=line.split("\t",-1);assertEquals(4,row.length);assertTrue(rules.add(row[0]));assertTrue(ids.contains(row[1]));
            if(row[2].equals("visited"))assertTrue(reached.getOrDefault(row[0],Set.of()).contains(row[1]),line);
            else {assertTrue(Set.of("alias","rejected").contains(row[2]));assertFalse(row[3].isBlank());}
        }
        assertEquals(Set.of(TypeScriptParser.ruleNames),rules);
        String baseline=Files.readString(Path.of("docs/baseline/2026-11-01/parser-rules.json"));
        var matcher=java.util.regex.Pattern.compile("(?m)^    \"([A-Za-z_]+)\"").matcher(baseline);
        Set<String> original=new HashSet<>();while(matcher.find())original.add(matcher.group(1));
        assertEquals(156,original.size());assertTrue(rules.containsAll(original));
    }
}
