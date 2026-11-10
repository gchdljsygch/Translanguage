package com.pingsu.transpiler;

/** Self-contained decorator runtime templates; names are allocated per compilation. */
final class DecoratorHelpers {
    private DecoratorHelpers() { }
    static final String KEY =
            "function KEY(value) {\n" +
            "  var object = {}; Object.defineProperty(object, value, {value: 0});\n" +
            "  var names = Object.getOwnPropertyNames(object);\n" +
            "  return names.length ? names[0] : Object.getOwnPropertySymbols(object)[0];\n" +
            "}\n";
    static final String LEGACY =
            "function LEGACY(decorators, target, key, kind) {\n" +
            "  var result = kind === 0 ? target : kind === 1 ? void 0 : Object.getOwnPropertyDescriptor(target, key);\n" +
            "  for (var i = decorators.length - 1; i >= 0; i--) {\n" +
            "    var decorator = decorators[i];\n    var next = kind === 0 ? decorator(result) : decorator(target, key, result);\n" +
            "    if (next) result = next;\n" +
            "  }\n" +
            "  if (kind === 0) return result;\n" +
            "  if (kind === 2 && result) Object.defineProperty(target, key, result);\n" +
            "}\n";
    static final String PARAM =
            "function PARAM(index, decorator) { return function(target, key) { decorator(target, key, index); }; }\n";
    static final String RUN =
            "function RUN(receiver, initializers, value) {\n" +
            "  var supplied = arguments.length > 2;\n" +
            "  for (var i = 0; i < initializers.length; i++) {\n" +
            "    if (supplied) value = initializers[i].call(receiver, value);\n" +
            "    else initializers[i].call(receiver);\n" +
            "  }\n" +
            "  return supplied ? value : receiver;\n" +
            "}\n";
    static final String STANDARD =
            "function STANDARD(decorators, target, key, kind, isStatic, initializers, extras, metadata) {\n" +
            "  var descriptor = kind === 'class' || kind === 'field' ? null : Object.getOwnPropertyDescriptor(target, key);\n" +
            "  var value = kind === 'class' ? target : kind === 'field' ? void 0 : descriptor[kind === 'method' ? 'value' : kind === 'getter' ? 'get' : 'set'];\n" +
            "  var active = true;\n" +
            "  function apply(decorator, current) {\n" +
            "    var context = {kind: kind, name: key, metadata: metadata, addInitializer: function(initializer) {\n" +
            "      if (!active) throw new TypeError('Decorator initialization has finished');\n" +
            "      if (typeof initializer !== 'function') throw new TypeError('Initializer must be a function');\n" +
            "      extras.push(initializer);\n" +
            "    }};\n" +
            "    if (kind !== 'class') {\n" +
            "      context.static = isStatic; context.private = false;\n" +
            "      context.access = {has: function(object) { return key in object; }};\n" +
            "      if (kind !== 'setter') context.access.get = function(object) { return object[key]; };\n" +
            "      if (kind === 'field' || kind === 'setter') context.access.set = function(object, value) { object[key] = value; };\n" +
            "    }\n" +
            "    var result;\n" +
            "    result = decorator(current, context);\n" +
            "    if (result !== void 0 && typeof result !== 'function') throw new TypeError('Decorator must return a function or undefined');\n" +
            "    return result;\n" +
            "  }\n" +
            "  for (var i = decorators.length - 1; i >= 0; i--) {\n" +
            "    var result = apply(decorators[i], value);\n" +
            "    if (result !== void 0) {\n" +
            "      if (kind === 'field') initializers.unshift(result); else value = result;\n" +
            "    }\n" +
            "  }\n" +
            "  active = false;\n" +
            "  if (descriptor) {\n" +
            "    descriptor[kind === 'method' ? 'value' : kind === 'getter' ? 'get' : 'set'] = value;\n" +
            "    Object.defineProperty(target, key, descriptor);\n" +
            "  }\n" +
            "  return value;\n" +
            "}\n" +
            "\n";
}
