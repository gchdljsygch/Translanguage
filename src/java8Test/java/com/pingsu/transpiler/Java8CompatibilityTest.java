package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.*;
import com.pingsu.transpiler.syntax.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class Java8CompatibilityTest {
    @Test void immutableSnapshotsKeepNullAndMutationContracts() {
        var original=new ArrayList<String>(List.of("a","b"));var copy=Compat.copyList(original);original.clear();assertEquals(List.of("a","b"),copy);
        assertThrows(UnsupportedOperationException.class,()->copy.add("c"));assertThrows(UnsupportedOperationException.class,()->copy.set(0,"c"));
        assertThrows(NullPointerException.class,()->Compat.list("a",null));assertThrows(NullPointerException.class,()->copy.contains(null));
        assertThrows(IllegalArgumentException.class,()->Compat.set("a","a"));assertThrows(NullPointerException.class,()->Compat.set("a",null));
        var map=new LinkedHashMap<String,String>();map.put("a","b");var frozen=Compat.copyMap(map);map.put("a","changed");assertEquals("b",frozen.get("a"));
        assertThrows(UnsupportedOperationException.class,()->frozen.entrySet().iterator().next().setValue("c"));
        map.put("null",null);assertThrows(NullPointerException.class,()->Compat.copyMap(map));
        var stream=Stream.of("a",null,"b").collect(Compat.toList());assertEquals(Arrays.asList("a",null,"b"),stream);assertThrows(UnsupportedOperationException.class,()->stream.clear());
        var diagnostics=new ArrayList<>(List.of(new Diagnostic("x",1,2,"E","bad")));var failure=new TranspileResult("partial",diagnostics);diagnostics.clear();assertFalse(failure.successful());assertEquals("",failure.javaScript());
    }
    @Test void stringsMatchModernCodePointSemantics() {
        for(String value:List.of(""," ","\t\n\r","\u2003","\u00a0","\u2003x","\ud83d\ude00","\ud800")) {
            assertEquals(value.isBlank(),Compat.isBlank(value));assertEquals(value.stripLeading(),Compat.stripLeading(value));
            for(int count:new int[]{0,1,2,50})assertEquals(value.repeat(count),Compat.repeat(value,count));
        }
        assertThrows(IllegalArgumentException.class,()->Compat.repeat("",-1));assertThrows(OutOfMemoryError.class,()->Compat.repeat("ab",Integer.MAX_VALUE));
    }
    @Test void unicodeTableMatchesAllCodePointsOnPinnedJdk17() {
        if(Runtime.version().feature()!=17)return; // JDK 25 has newer Unicode data; not the fixed baseline.
        for(int cp=0;cp<=Character.MAX_CODE_POINT;cp++) {
            assertEquals(Character.isUnicodeIdentifierStart(cp),Unicode17.isUnicodeIdentifierStart(cp),"start "+cp);
            assertEquals(Character.isUnicodeIdentifierPart(cp),Unicode17.isUnicodeIdentifierPart(cp),"part "+cp);
            assertEquals(Character.isIdentifierIgnorable(cp),Unicode17.isIdentifierIgnorable(cp),"ignorable "+cp);
            assertEquals(Character.isWhitespace(cp),Unicode17.isWhitespace(cp),"space "+cp);
            assertEquals(Character.getType(cp)==Character.SPACE_SEPARATOR,Unicode17.isSpaceSeparator(cp),"separator "+cp);
            assertEquals(Character.isDigit(cp),Unicode17.isDigit(cp),"digit "+cp);
        }
        assertFalse(Unicode17.isUnicodeIdentifierStart(-1));assertFalse(Unicode17.isUnicodeIdentifierPart(0x110000));
    }
    private URLClassLoader mainLoader() throws Exception {
        return new URLClassLoader(new URL[]{Path.of(System.getProperty("main.jar")).toUri().toURL()},ClassLoader.getPlatformClassLoader());
    }
    @Test void publicRecordsRetainComponentApiAndValueSemantics() throws Exception {
        try(var main=mainLoader()) {
            for(Class<?> type:List.of(Diagnostic.class,TranspileResult.class,SourceRange.class,Token.class,BranchNode.class,Program.class)) {
                Class<?> record=main.loadClass(type.getName());assertTrue(record.isRecord());assertFalse(type.isRecord());assertTrue(Modifier.isFinal(type.getModifiers()));
                assertEquals(signatures(record),signatures(type),type.getName());
                for(Field field:type.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers()))assertTrue(Modifier.isFinal(field.getModifiers())&&Modifier.isPrivate(field.getModifiers()));
            }
            Object record=main.loadClass(SourceRange.class.getName()).getConstructor(int.class,int.class).newInstance(2,9);
            SourceRange range=new SourceRange(2,9);assertEquals(record.toString(),range.toString());assertEquals(record.hashCode(),range.hashCode());assertEquals(range,new SourceRange(2,9));assertNotEquals(range,new SourceRange(2,8));
            Object diagnostic=main.loadClass(Diagnostic.class.getName()).getConstructor(String.class,int.class,int.class,String.class,String.class).newInstance("x",2,3,"E","bad");
            Diagnostic value=new Diagnostic("x",2,3,"E","bad");assertEquals(diagnostic.toString(),value.toString());assertEquals(diagnostic.hashCode(),value.hashCode());
            assertEquals("TranspileResult[javaScript=ok, diagnostics=[]]",new TranspileResult("ok",List.of()).toString());
        }
    }
    private Set<String> signatures(Class<?> type) {
        Set<String> result=new TreeSet<>();
        for(Constructor<?> ctor:type.getConstructors())result.add("new"+Arrays.toString(Arrays.stream(ctor.getParameterTypes()).map(Class::getName).toArray()));
        for(Method m:type.getDeclaredMethods())if(Modifier.isPublic(m.getModifiers())&&!m.isSynthetic())result.add(m.getName()+Arrays.toString(Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray())+m.getReturnType().getName());
        return result;
    }
    @Test void helperStringsAndEveryFixedOutputMatchMainArtifact() throws Exception {
        try(var main=mainLoader()) {
            Class<?> own=Class.forName("com.pingsu.transpiler.internal.lowering.AsyncHelpers");Class<?> baseline=main.loadClass(own.getName());
            for(Field field:own.getDeclaredFields())if(field.getType()==String.class&&Modifier.isStatic(field.getModifiers())) {field.setAccessible(true);Field original=baseline.getDeclaredField(field.getName());original.setAccessible(true);assertEquals(original.get(null),field.get(null),field.getName());}
            Class<?> targetType=main.loadClass(JavaScriptTarget.class.getName());Class<?> compilerType=main.loadClass(TypeScriptTranspiler.class.getName());
            try(var paths=Files.walk(Path.of("src/test/resources/p8"))) {
                for(Path input:paths.filter(p->p.toString().endsWith(".ts")).toList())for(JavaScriptTarget target:JavaScriptTarget.values()) {
                    Object compiler=compilerType.getConstructor(targetType).newInstance(Enum.valueOf((Class)targetType,target.name()));
                    Object expected=compilerType.getMethod("transpile",String.class,String.class).invoke(compiler,input.getFileName().toString(),Files.readString(input));
                    var actual=new TypeScriptTranspiler(target).transpile(input.getFileName().toString(),Files.readString(input));
                    assertEquals(expected.toString(),actual.toString(),input+"/"+target);
                }
            }
        }
    }
}
