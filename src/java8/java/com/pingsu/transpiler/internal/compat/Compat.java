package com.pingsu.transpiler.internal.compat;

import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Internal Java 8 implementations of the collection and string operations used by the library. */
public final class Compat {
    private Compat() { }
    @SafeVarargs public static <T> List<T> list(T... values) { return copyList(Arrays.asList(values)); }
    public static <T> List<T> copyList(Collection<? extends T> values) {
        final ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(new AbstractList<T>() {
            @Override public T get(int index) { return copy.get(index); }
            @Override public int size() { return copy.size(); }
            @Override public int indexOf(Object value) { return copy.indexOf(Objects.requireNonNull(value)); }
            @Override public int lastIndexOf(Object value) { return copy.lastIndexOf(Objects.requireNonNull(value)); }
            @Override public boolean contains(Object value) { return copy.contains(Objects.requireNonNull(value)); }
        });
    }
    @SafeVarargs public static <T> Set<T> set(T... values) {
        final Set<T> copy = new LinkedHashSet<>();
        for (T value : values) if (!copy.add(Objects.requireNonNull(value))) throw new IllegalArgumentException("Duplicate element: " + value);
        return Collections.unmodifiableSet(new AbstractSet<T>() {
            @Override public Iterator<T> iterator() { return copy.iterator(); }
            @Override public int size() { return copy.size(); }
            @Override public boolean contains(Object value) { return copy.contains(Objects.requireNonNull(value)); }
        });
    }
    public static <K,V> Map<K,V> map() { return copyMap(Collections.<K,V>emptyMap()); }
    public static <K,V> Map<K,V> copyMap(Map<? extends K,? extends V> values) {
        final Map<K,V> copy = new LinkedHashMap<>();
        values.forEach((key,value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        final Map<K,V> snapshot = Collections.unmodifiableMap(copy);
        return Collections.unmodifiableMap(new AbstractMap<K,V>() {
            @Override public Set<Entry<K,V>> entrySet() { return snapshot.entrySet(); }
            @Override public V get(Object key) { return copy.get(Objects.requireNonNull(key)); }
            @Override public boolean containsKey(Object key) { return copy.containsKey(Objects.requireNonNull(key)); }
            @Override public boolean containsValue(Object value) { return copy.containsValue(Objects.requireNonNull(value)); }
        });
    }
    /** Stream.toList permits null elements and returns an unmodifiable snapshot. */
    public static <T> Collector<T,?,List<T>> toList() {
        return Collectors.collectingAndThen(Collectors.toCollection(ArrayList<T>::new), Collections::unmodifiableList);
    }
    public static boolean isBlank(String value) {
        for (int offset=0; offset<value.length();) {
            int cp=value.codePointAt(offset);
            if (!Unicode17.isWhitespace(cp)) return false;
            offset+=Character.charCount(cp);
        }
        return true;
    }
    public static String stripLeading(String value) {
        int offset=0;
        while (offset<value.length()) {
            int cp=value.codePointAt(offset);
            if (!Unicode17.isWhitespace(cp)) break;
            offset+=Character.charCount(cp);
        }
        return value.substring(offset);
    }
    public static String repeat(String value,int count) {
        if (count<0) throw new IllegalArgumentException("Negative repeat count: " + count);
        if (count==0 || value.isEmpty()) return "";
        if (value.length()>Integer.MAX_VALUE/count) throw new OutOfMemoryError("Required length exceeds implementation limit");
        StringBuilder result=new StringBuilder(value.length()*count);
        for (int i=0;i<count;i++) result.append(value);
        return result.toString();
    }
}
