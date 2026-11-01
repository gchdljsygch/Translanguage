package com.pingsu.reference.transpiler;

import com.pingsu.reference.antlr.TypeScriptParser;
import com.pingsu.reference.antlr.TypeScriptParser.*;
import com.pingsu.reference.antlr.TypeScriptParserBaseVisitor;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** All erasure decisions are made from parser contexts, never from source-text patterns. */
public final class TypeErasurePass implements TransformationPass {
    /** {@inheritDoc} */
    @Override public void transform(TransformationContext context) {
        Visitor visitor = new Visitor(context);
        visitor.visit(context.program());
        // Erasing the last type-only import/export must not change a module into a script.
        if (visitor.erasedModuleSyntax) context.append("\nexport {};\n");
    }

    private static final class Visitor extends TypeScriptParserBaseVisitor<Void> {
        private final TransformationContext context;
        private boolean erasedModuleSyntax;
        Visitor(TransformationContext context) { this.context = context; }

        private void removeDeclaration(ParserRuleContext node) {
            ParserRuleContext whole = node;
            while (whole.getParent() instanceof StatementContext
                    || whole.getParent() instanceof SourceElementContext
                    || whole.getParent() instanceof DeclarationContext
                    || whole.getParent() instanceof ExportDeclarationContext) {
                whole = whole.getParent();
            }
            if (whole.start.getType() == TypeScriptParser.Export || whole.start.getType() == TypeScriptParser.Import)
                erasedModuleSyntax = true;
            context.remove(whole);
        }

        private void removeToken(ParserRuleContext node, int tokenType) {
            for (TerminalNode token : node.getTokens(tokenType)) context.remove(token.getSymbol());
        }

        @Override public Void visitInterfaceDeclaration(InterfaceDeclarationContext node) { removeDeclaration(node); return null; }
        @Override public Void visitTypeAliasDeclaration(TypeAliasDeclarationContext node) { removeDeclaration(node); return null; }
        @Override public Void visitAmbientDeclaration(AmbientDeclarationContext node) { removeDeclaration(node); return null; }
        @Override public Void visitTypeAnnotation(TypeAnnotationContext node) { context.remove(node); return null; }
        @Override public Void visitTypeParameters(TypeParametersContext node) { context.remove(node); return null; }
        @Override public Void visitTypeArguments(TypeArgumentsContext node) { context.remove(node); return null; }
        @Override public Void visitTypeGeneric(TypeGenericContext node) { context.remove(node); return null; }
        @Override public Void visitImplementsClause(ImplementsClauseContext node) { context.remove(node); return null; }
        @Override public Void visitAccessibilityModifier(AccessibilityModifierContext node) { context.remove(node); return null; }
        @Override public Void visitIndexMemberDeclaration(IndexMemberDeclarationContext node) { context.remove(node); return null; }
        @Override public Void visitEnumDeclaration(EnumDeclarationContext node) { return null; }

        @Override public Void visitNamespaceDeclaration(NamespaceDeclarationContext node) {
            if (node.Declare() != null) removeDeclaration(node);
            else context.unsupported(node, "Runtime namespaces are not supported; use ES modules.");
            return null;
        }

        @Override public Void visitDecoratorList(DecoratorListContext node) {
            context.unsupported(node, "Decorator lowering is not supported."); return null;
        }
        @Override public Void visitDecorator(DecoratorContext node) {
            context.unsupported(node, "Decorator lowering is not supported."); return null;
        }

        @Override public Void visitVariableStatement(VariableStatementContext node) {
            if (node.Declare() != null) { removeDeclaration(node); return null; }
            return visitChildren(node);
        }
        @Override public Void visitVariableDeclaration(VariableDeclarationContext node) {
            removeToken(node, TypeScriptParser.Not);
            return visitChildren(node);
        }
        @Override public Void visitFunctionDeclaration(FunctionDeclarationContext node) {
            if (node.functionBody() == null) { removeDeclaration(node); return null; }
            return visitChildren(node);
        }
        @Override public Void visitClassDeclaration(ClassDeclarationContext node) {
            removeToken(node, TypeScriptParser.Abstract);
            return visitChildren(node);
        }
        @Override public Void visitAbstractDeclaration(AbstractDeclarationContext node) { context.remove(node); return null; }
        @Override public Void visitPropertyMemberBase(PropertyMemberBaseContext node) {
            removeToken(node, TypeScriptParser.ReadOnly);
            removeToken(node, TypeScriptParser.Override);
            return visitChildren(node);
        }
        @Override public Void visitPropertyDeclarationExpression(PropertyDeclarationExpressionContext node) {
            if (!node.propertyMemberBase().Abstract().isEmpty() || !node.propertyMemberBase().Declare().isEmpty()) {
                context.remove(node); return null;
            }
            removeToken(node, TypeScriptParser.QuestionMark);
            removeToken(node, TypeScriptParser.Not);
            return visitChildren(node);
        }
        @Override public Void visitMethodDeclarationExpression(MethodDeclarationExpressionContext node) {
            if (node.functionBody() == null) { context.remove(node); return null; }
            if (!node.propertyMemberBase().Abstract().isEmpty() || !node.propertyMemberBase().Declare().isEmpty()) {
                context.unsupported(node, "An abstract/declare method cannot have an implementation."); return null;
            }
            return visitChildren(node);
        }
        @Override public Void visitGetterSetterDeclarationExpression(GetterSetterDeclarationExpressionContext node) {
            if (!node.propertyMemberBase().Abstract().isEmpty() || !node.propertyMemberBase().Declare().isEmpty()) {
                context.unsupported(node, "Abstract/declare accessors with bodies are not supported."); return null;
            }
            return visitChildren(node);
        }

        @Override public Void visitOptionalParameter(OptionalParameterContext node) {
            removeToken(node, TypeScriptParser.QuestionMark);
            return parameter(node, node.accessibilityModifier() != null || node.ReadOnly() != null,
                    node.identifierOrPattern().getText());
        }
        @Override public Void visitRequiredParameter(RequiredParameterContext node) {
            return parameter(node, node.accessibilityModifier() != null || node.ReadOnly() != null,
                    node.identifierOrPattern().getText());
        }
        @Override public Void visitFormalParameterArg(FormalParameterArgContext node) {
            if (node.assignable().getText().equals("this")) { removeThisParameter(node); return null; }
            removeToken(node, TypeScriptParser.QuestionMark);
            removeToken(node, TypeScriptParser.ReadOnly);
            if (node.accessibilityModifier() != null || node.ReadOnly() != null) {
                if (!(node.getParent().getParent() instanceof ConstructorDeclarationContext)) {
                    context.unsupported(node, "Parameter properties are only valid in constructors.");
                }
            }
            return visitChildren(node);
        }
        private Void parameter(ParserRuleContext node, boolean property, String name) {
            if (name.equals("this")) { removeThisParameter(node); return null; }
            if (property) context.unsupported(node, "Parameter properties are only valid in constructors.");
            return visitChildren(node);
        }
        private void removeThisParameter(ParserRuleContext node) {
            int next = node.stop.getTokenIndex() + 1;
            while (context.tokens().get(next).getChannel() != Token.DEFAULT_CHANNEL) next++;
            Token token = context.tokens().get(next);
            if (node.getParent().start.getTokenIndex() != node.start.getTokenIndex()) {
                context.unsupported(node, "The this parameter must be the first parameter."); return;
            }
            context.replace(node.start, token.getType() == TypeScriptParser.Comma ? token : node.stop, "");
        }

        @Override public Void visitConstructorDeclaration(ConstructorDeclarationContext node) {
            if (node.functionBody() == null) { context.remove(node); return null; }
            List<String> assignments = new ArrayList<>();
            if (node.formalParameterList() != null) {
                for (FormalParameterArgContext parameter : node.formalParameterList().formalParameterArg()) {
                    if (parameter.accessibilityModifier() == null && parameter.ReadOnly() == null) continue;
                    if (parameter.assignable().identifier() == null) {
                        context.unsupported(parameter, "A parameter property must have a simple identifier."); continue;
                    }
                    String name = parameter.assignable().getText();
                    assignments.add("this." + name + " = " + name + ";");
                }
            }
            if (!assignments.isEmpty()) {
                ParserRuleContext owner = node.getParent();
                while (owner != null && !(owner instanceof ClassDeclarationContext) && !(owner instanceof ClassExpressionContext))
                    owner = owner.getParent();
                ClassHeritageContext heritage = owner instanceof ClassDeclarationContext declaration ? declaration.classHeritage()
                        : owner instanceof ClassExpressionContext expression ? expression.classHeritage() : null;
                if (heritage != null && heritage.classExtendsClause() != null) {
                    SourceElementContext superCall = directSuperCall(node.functionBody());
                    if (superCall == null) {
                        context.unsupported(node, "Derived parameter properties require a direct super() statement in the constructor.");
                    } else {
                        context.insertAfter(superCall.stop, ";\n" + String.join("\n", assignments) + "\n");
                    }
                } else {
                    context.insertAfter(node.OpenBrace().getSymbol(), "\n" + String.join("\n", assignments) + "\n");
                }
            }
            return visitChildren(node);
        }

        private SourceElementContext directSuperCall(FunctionBodyContext body) {
            if (body.sourceElements() == null) return null;
            for (SourceElementContext element : body.sourceElements().sourceElement()) {
                ExpressionStatementContext statement = element.statement().expressionStatement();
                if (statement == null || statement.expressionSequence().singleExpression().size() != 1) continue;
                SingleExpressionContext expression = statement.expressionSequence().singleExpression(0);
                if (expression instanceof ArgumentsExpressionContext call && call.singleExpression() instanceof SuperExpressionContext)
                    return element;
            }
            return null;
        }

        @Override public Void visitCastAsExpression(CastAsExpressionContext node) {
            context.replace(node.As().getSymbol(), node.type_().stop, "");
            return visit(node.singleExpression());
        }
        @Override public Void visitSatisfiesExpression(SatisfiesExpressionContext node) {
            context.replace(node.Satisfies().getSymbol(), node.type_().stop, "");
            return visit(node.singleExpression());
        }
        @Override public Void visitTypeAssertionExpression(TypeAssertionExpressionContext node) {
            context.replace(node.start, node.MoreThan().getSymbol(), "");
            return visit(node.singleExpression());
        }
        @Override public Void visitNonNullAssertionExpression(NonNullAssertionExpressionContext node) {
            removeToken(node, TypeScriptParser.Not);
            return visitChildren(node);
        }
        @Override public Void visitMemberDotExpression(MemberDotExpressionContext node) {
            removeToken(node, TypeScriptParser.Not);
            if (node.QuestionMark() != null) {
                context.unsupported(node, "Optional chaining must use the contiguous ?. operator.");
            }
            return visitChildren(node);
        }

        @Override public Void visitImportStatement(ImportStatementContext node) {
            ImportModuleItemsContext items = node.importFromBlock().importModuleItems();
            if (node.TypeAlias() != null || (items != null && !items.importAliasName().isEmpty()
                    && items.importAliasName().stream().allMatch(item -> item.TypeAlias() != null)
                    && node.importFromBlock().importDefault() == null)) {
                removeDeclaration(node); return null;
            }
            return visitChildren(node);
        }
        @Override public Void visitImportModuleItems(ImportModuleItemsContext node) {
            if (node.importAliasName().stream().anyMatch(item -> item.TypeAlias() != null)) {
                context.replace(node, "{ " + node.importAliasName().stream().filter(item -> item.TypeAlias() == null)
                        .map(item -> context.tokens().getText(item)).collect(Collectors.joining(", ")) + " }");
            }
            return null;
        }
        @Override public Void visitExportTypeDeclaration(ExportTypeDeclarationContext node) { removeDeclaration(node); return null; }
        @Override public Void visitExportDeclaration(ExportDeclarationContext node) {
            ExportModuleItemsContext items = node.exportFromBlock() == null ? null : node.exportFromBlock().exportModuleItems();
            if (items != null && !items.exportAliasName().isEmpty()
                    && items.exportAliasName().stream().allMatch(item -> item.TypeAlias() != null)) {
                removeDeclaration(node); return null;
            }
            return visitChildren(node);
        }
        @Override public Void visitExportModuleItems(ExportModuleItemsContext node) {
            if (node.exportAliasName().stream().anyMatch(item -> item.TypeAlias() != null)) {
                context.replace(node, "{ " + node.exportAliasName().stream().filter(item -> item.TypeAlias() == null)
                        .map(item -> context.tokens().getText(item)).collect(Collectors.joining(", ")) + " }");
            }
            return null;
        }
    }
}
