/*
 * SonarQube Flex Plugin
 * Copyright (C) 2010-2025 SonarSource Sàrl
 */
package org.sonar.flex.checks;

import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AstNodeType;
import java.util.Collections;
import java.util.List;
import org.sonar.check.Rule;
import org.sonar.flex.FlexCheck;
import org.sonar.flex.FlexGrammar;
import org.sonar.flex.FlexPunctuator;

/**
 * Unisys C check: division or modulo by zero literal is undefined behaviour.
 * Manual §5 (Arithmetic Operators): divisor must not be zero.
 *
 * Catches: a / 0 a / 0.0 a % 0 a / (0) a / 00 (octal zero)
 */
@Rule(key = "DivisionByZero")
public class CDivisionByZeroCheck extends FlexCheck {

    @Override
    public List<AstNodeType> subscribedTo() {
        // Visit every multiplicative expression: a * b, a / b, a % b
        return Collections.singletonList(FlexGrammar.MULTIPLICATIVE_EXPR);
    }

    @Override
    public void visitNode(AstNode expr) {
        // C_MULTIPLICATIVE_EXPR structure in the AST:
        // child[0] = left operand (C_CAST_EXPR)
        // child[1] = operator (DIV or MOD punctuator token)
        // child[2] = right operand (C_CAST_EXPR)
        // child[3] = next operator ... (for chained: a / b / c)

        List<AstNode> children = expr.getChildren();

        for (int i = 1; i < children.size() - 1; i += 2) {
            AstNode operator = children.get(i);
            AstNode rightOperand = children.get(i + 1);

            boolean isDivOrMod = operator.is(FlexPunctuator.DIV) || operator.is(FlexPunctuator.MOD);

            if (isDivOrMod && isZeroLiteral(rightOperand)) {
                String op = operator.is(FlexPunctuator.DIV) ? "/" : "%";
                addIssue("Division by zero: the right-hand side of '" + op + "' is zero.", operator);
            }
        }
    }

    /**
     * Returns true if the node is a zero literal, including:
     * 0 0.0 0. .0 0x0 00 (octal)
     * Also handles (0) — a parenthesized zero.
     */
    private static boolean isZeroLiteral(AstNode node) {
        // Unwrap: C_CAST_EXPR → C_UNARY_EXPR → C_POSTFIX_EXPR → C_PRIMARY_EXPR →
        // constant/paren
        AstNode current = node;

        // Peel single-child wrapper nodes until we reach something meaningful
        while (current.getNumberOfChildren() == 1) {
            current = current.getFirstChild();
        }

        // Case 1: C_PRIMARY_EXPR → ( C_EXPRESSION ) i.e. (0)
        if (current.is(FlexGrammar.PRIMARY_EXPR)) {
            List<AstNode> ch = current.getChildren();
            if (ch.size() == 3) {
                // ( expr ) — recurse into the inner expression
                return isZeroLiteral(ch.get(1));
            }
        }

        // Case 2: C_CONSTANT → C_INTEGER_CONSTANT | C_FLOAT_CONSTANT
        if (current.is(FlexGrammar.CONSTANT)) {
            AstNode constantNode = current.getFirstChild();
            String tokenValue = constantNode.getTokenValue();
            return isZeroTokenValue(tokenValue);
        }

        // Case 3: raw token (e.g. directly matched integer/float)
        if (current.getNumberOfChildren() == 0) {
            return isZeroTokenValue(current.getTokenValue());
        }

        return false;
    }

    /**
     * Check whether a token string represents numeric zero.
     * Handles: 0, 0L, 0U, 0x0, 0X0, 00, 0.0, 0.0f, .0, 0e0, etc.
     */
    private static boolean isZeroTokenValue(String value) {
        if (value == null)
            return false;
        String v = value.trim().toLowerCase();

        // Strip trailing type suffixes: u, l, f, ul, lu, etc.
        v = v.replaceAll("[uUlLfF]+$", "");

        // Hex zero: 0x0, 0X00, etc.
        if (v.startsWith("0x")) {
            return v.substring(2).matches("0+");
        }

        // Try parsing as double — covers 0, 0.0, .0, 0e0, 0.0e5 (= 0.0), 00 (octal 0)
        try {
            return Double.parseDouble(v) == 0.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}