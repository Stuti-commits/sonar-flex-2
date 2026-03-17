/*
 * SonarQube Flex Plugin
 * Copyright (C) 2010-2025 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.flex.checks;

import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AstNodeType;
import java.util.Collections;
import java.util.List;
import org.sonar.check.Rule;
import org.sonar.flex.FlexCheck;
import org.sonar.flex.FlexGrammar;
import org.sonar.flex.FlexKeyword;

/**
 * Unisys C — function definition structural checks.
 * Based on Section 7 of the C Programming Reference Manual (Vol 1).
 *
 * Rule 1 — void function must not return a value:
 * void foo() { return 5; } ← illegal per §7 "Return Values"
 *
 * Rule 2 — non-void function returning nothing gives undefined value:
 * int foo() { return; } ← undefined per §7
 *
 * Rule 3 — invalid storage class on function definition:
 * Only extern/static are valid. auto/register/typedef are not.
 * typedef int foo() {} ← illegal
 * auto int foo() {} ← illegal
 *
 * Rule 4 — function body must not be empty for non-void functions:
 * int foo() {} ← no return path, undefined value
 *
 * Rule 5 — function name must not shadow a type keyword:
 * int int() {} ← int is a reserved type name
 */
@Rule(key = "C_FunctionDeclaration")
public class CFunctionDeclarationCheck extends FlexCheck {

    // Storage classes NOT allowed on function definitions per §7
    private static final FlexKeyword[] INVALID_FUNC_STORAGE = {
            FlexKeyword.AUTO,
            FlexKeyword.REGISTER,
            FlexKeyword.TYPEDEF
    };

    // Type keywords that cannot be used as function names
    private static final FlexKeyword[] TYPE_KEYWORDS = {
            FlexKeyword.INT, FlexKeyword.CHAR, FlexKeyword.FLOAT,
            FlexKeyword.DOUBLE, FlexKeyword.VOID, FlexKeyword.LONG,
            FlexKeyword.SHORT, FlexKeyword.STRUCT, FlexKeyword.UNION
    };

    @Override
    public List<AstNodeType> subscribedTo() {
        return Collections.singletonList(FlexGrammar.FUNCTION_DEFINITION);
    }

    @Override
    public void visitNode(AstNode funcDef) {
        /*
         * C_FUNCTION_DEFINITION structure (from cFunctionDefinitions()):
         *
         * C_DECLARATION_SPECIFIERS? ← return type + storage class
         * C_DECLARATOR ← name + parameter list e.g. main()
         * C_FUNCTION_BODY ← { statements }
         */

        AstNode specifiers = funcDef.getFirstChild(FlexGrammar.DECLARATION_SPECIFIERS);
        AstNode declarator = funcDef.getFirstChild(FlexGrammar.DECLARATOR);
        AstNode body = funcDef.getFirstChild(FlexGrammar.FUNCTION_BODY);

        boolean isVoidReturn = isVoidReturnType(specifiers);
        boolean hasSpecifiers = specifiers != null;

        // ── Rule 3: invalid storage class ────────────────────────────────────
        if (hasSpecifiers) {
            checkInvalidStorageClass(specifiers);
        }

        // ── Rule 5: function name must not be a type keyword ─────────────────
        if (declarator != null) {
            checkFunctionName(declarator);
        }

        // ── Rule 1 & 2: return statement consistency ──────────────────────────
        if (body != null) {
            List<AstNode> returnStatements = body.getDescendants(FlexGrammar.RETURN_STATEMENT);
            for (AstNode ret : returnStatements) {
                checkReturnStatement(ret, isVoidReturn, hasSpecifiers);
            }

            // ── Rule 4: non-void function with completely empty body ──────────
            if (!isVoidReturn && hasSpecifiers) {
                checkEmptyBody(body, funcDef);
            }
        }
    }

    // ── Rule 3 ──────────────────────────────────────────────────────────────
    private void checkInvalidStorageClass(AstNode specifiers) {
        for (AstNode child : specifiers.getDescendants(FlexGrammar.STORAGE_CLASS_SPECIFIER)) {
            for (FlexKeyword invalid : INVALID_FUNC_STORAGE) {
                if (child.getFirstChild() != null && child.getFirstChild().is(invalid)) {
                    addIssue(
                            "'" + invalid.getValue() + "' is not a valid storage class for a function definition"
                                    + " — use 'extern' or 'static' only (Unisys C §7).",
                            child);
                }
            }
        }
    }

    // ── Rule 5 ──────────────────────────────────────────────────────────────
    private void checkFunctionName(AstNode declarator) {
        // Walk down to the C_DIRECT_DECLARATOR → first child = C_IDENTIFIER token
        AstNode directDecl = declarator.getFirstChild(FlexGrammar.DIRECT_DECLARATOR);
        if (directDecl == null)
            return;

        AstNode nameNode = directDecl.getFirstChild(FlexGrammar.IDENTIFIER);
        if (nameNode == null)
            return;

        String name = nameNode.getTokenValue();
        if (name == null)
            return;

        // Check if the name matches any type keyword value
        for (FlexKeyword typeKw : TYPE_KEYWORDS) {
            if (typeKw.getValue().equals(name)) {
                addIssue(
                        "Function name '" + name + "' conflicts with a reserved type keyword (Unisys C §7).",
                        nameNode);
                return;
            }
        }
    }

    // ── Rule 1 & 2 ──────────────────────────────────────────────────────────
    private void checkReturnStatement(AstNode ret, boolean isVoidReturn, boolean hasSpecifiers) {
        // RETURN_STATEMENT children:
        // RETURN expression? EOS
        // If there are more than 2 children → has expression (RETURN + expr + EOS)
        // If exactly 2 children → bare return (RETURN + EOS)

        boolean hasExpression = ret.getNumberOfChildren() > 2;

        if (isVoidReturn && hasExpression) {
            // Rule 1: void function returns a value
            addIssue(
                    "A 'void' function must not return a value (Unisys C §7, Return Values).",
                    ret);
        }

        if (!isVoidReturn && hasSpecifiers && !hasExpression) {
            // Rule 2: non-void function returns nothing → undefined value
            addIssue(
                    "Non-void function has a bare 'return;' — returned value is undefined (Unisys C §7).",
                    ret);
        }
    }

    // ── Rule 4 ──────────────────────────────────────────────────────────────
    private void checkEmptyBody(AstNode body, AstNode funcDef) {
        // C_FUNCTION_BODY = { C_BLOCK_ITEM_LIST? }
        // If there is no C_BLOCK_ITEM_LIST child, the body is empty
        AstNode blockItemList = body.getFirstChild(FlexGrammar.BLOCK_ITEM_LIST);
        if (blockItemList == null) {
            // Get function name for a clearer message
            String name = getFunctionName(funcDef);
            addIssue(
                    "Non-void function '" + name + "' has an empty body — no value is returned (Unisys C §7).",
                    funcDef);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns true if the declaration specifiers indicate a void return type.
     * e.g. void foo() or static void foo()
     */
    private static boolean isVoidReturnType(AstNode specifiers) {
        if (specifiers == null)
            return false;
        // Look for VOID keyword anywhere in the specifiers
        for (AstNode child : specifiers.getDescendants(FlexGrammar.TYPE_SPECIFIER)) {
            if (child.getFirstChild() != null && child.getFirstChild().is(FlexKeyword.VOID)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the function name string from a C_FUNCTION_DEFINITION node.
     */
    private static String getFunctionName(AstNode funcDef) {
        AstNode declarator = funcDef.getFirstChild(FlexGrammar.DECLARATOR);
        if (declarator == null)
            return "<unknown>";
        AstNode directDecl = declarator.getFirstChild(FlexGrammar.DIRECT_DECLARATOR);
        if (directDecl == null)
            return "<unknown>";
        AstNode nameNode = directDecl.getFirstChild(FlexGrammar.IDENTIFIER);
        if (nameNode == null)
            return "<unknown>";
        String val = nameNode.getTokenValue();
        return val != null ? val : "<unknown>";
    }
}