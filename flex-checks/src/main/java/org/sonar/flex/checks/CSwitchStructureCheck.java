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
 * Unisys C check — validates switch statement structure:
 *
 * Rule 1: No case/default labels after the default label
 * (code after default is unreachable via normal fall-through)
 *
 * Rule 2: switch body must not be completely empty
 * (empty switch is meaningless and likely a mistake)
 *
 * Rule 3: Every non-last case block must end with break/return/goto
 * (implicit fall-through is almost always a bug in C)
 *
 * Rule 4: Duplicate case values are not allowed
 * (only catches simple integer/char literal duplicates)
 */
@Rule(key = "C_SwitchStructure")
public class CSwitchStructureCheck extends FlexCheck {

    @Override
    public List<AstNodeType> subscribedTo() {
        return Collections.singletonList(FlexGrammar.SWITCH_STATEMENT);
    }

    @Override
    public void visitNode(AstNode switchNode) {
        // C_SWITCH_STATEMENT children (flat list inside { }):
        // switch ( C_EXPRESSION ) { [C_CASE_LABEL | C_STATEMENT]* }
        // We only care about the children INSIDE the braces.
        List<AstNode> body = switchNode.getChildren(
                FlexGrammar.CASE_LABEL,
                FlexGrammar.STATEMENT,
                FlexGrammar.EXPRESSION_STATEMENT,
                FlexGrammar.BREAK_STATEMENT,
                FlexGrammar.RETURN_STATEMENT,
                FlexGrammar.GOTO_STATEMENT,
                FlexGrammar.COMPOUND_ASSIGNMENT,
                FlexGrammar.IF_STATEMENT,
                FlexGrammar.WHILE_STATEMENT,
                FlexGrammar.FOR_STATEMENT);

        // ── Rule 2: empty switch body ────────────────────────────────────────
        List<AstNode> allLabels = switchNode.getChildren(FlexGrammar.CASE_LABEL);
        if (allLabels.isEmpty()) {
            addIssue("Switch statement has no case or default labels.", switchNode);
            return;
        }

        // ── Rule 1: no case/default after default ────────────────────────────
        AstNode defaultLabel = null;
        for (AstNode child : switchNode.getChildren(FlexGrammar.CASE_LABEL)) {
            if (isDefault(child)) {
                defaultLabel = child;
            } else if (defaultLabel != null) {
                // This is a case: label appearing AFTER default:
                addIssue(
                        "Case label after 'default' is unreachable — move 'default' to the end.",
                        child);
            }
        }

        // ── Rule 3: fall-through detection ──────────────────────────────────
        // Walk the flat child list; for each case-block, check if it ends
        // with a jump statement before the next label (or end of switch).
        checkFallThrough(switchNode);

        // ── Rule 4: duplicate case values ───────────────────────────────────
        checkDuplicateCaseValues(switchNode);
    }

    // ── Rule 3 helper ──────────────────────────────────────────────────────
    private void checkFallThrough(AstNode switchNode) {
        List<AstNode> children = switchNode.getChildren();

        // Collect indices of all C_CASE_LABEL children
        // For each label, find the statements that follow until the next label
        // (or end of switch body). Check that last statement is a jump.

        boolean inCaseBlock = false;
        AstNode currentLabel = null;
        AstNode lastStatement = null;
        boolean hasJump = false;

        for (AstNode child : children) {
            if (child.is(FlexGrammar.CASE_LABEL)) {
                // Entering a new label — evaluate the previous block
                if (inCaseBlock && currentLabel != null && lastStatement != null && !hasJump) {
                    addIssue(
                            "Case block for '" + getLabelText(currentLabel)
                                    + "' has no break, return, or goto — possible unintended fall-through.",
                            currentLabel);
                }
                // Reset for new block
                currentLabel = child;
                lastStatement = null;
                hasJump = false;
                inCaseBlock = true;

            } else if (inCaseBlock) {
                // It's a statement inside a case block
                lastStatement = child;
                if (isJumpStatement(child)) {
                    hasJump = true;
                }
            }
        }

        // Check the very last case block (before closing `}`)
        // The last block is allowed to omit break — it's not a fall-through.
        // So we don't flag the last case block.
    }

    // ── Rule 4 helper ──────────────────────────────────────────────────────
    private void checkDuplicateCaseValues(AstNode switchNode) {
        java.util.Map<String, AstNode> seen = new java.util.LinkedHashMap<>();

        for (AstNode label : switchNode.getChildren(FlexGrammar.CASE_LABEL)) {
            if (isDefault(label))
                continue;

            // case expr: — get the expression token text
            // C_CASE_LABEL → CASE C_EXPRESSION COLON
            AstNode exprNode = label.getFirstChild(FlexGrammar.EXPRESSION);
            if (exprNode == null)
                continue;

            String value = exprNode.getTokenValue();
            if (value == null)
                continue;

            if (seen.containsKey(value)) {
                addIssue(
                        "Duplicate case value '" + value + "' — already defined on line "
                                + seen.get(value).getTokenLine() + ".",
                        label);
            } else {
                seen.put(value, label);
            }
        }
    }

    // ── Utility helpers ────────────────────────────────────────────────────

    private static boolean isDefault(AstNode caseLabel) {
        // C_CASE_LABEL → ( CASE C_EXPRESSION COLON ) | ( DEFAULT COLON )
        return caseLabel.getFirstChild().is(FlexKeyword.DEFAULT);
    }

    private static String getLabelText(AstNode caseLabel) {
        if (isDefault(caseLabel))
            return "default";
        AstNode expr = caseLabel.getFirstChild(FlexGrammar.EXPRESSION);
        return expr != null ? "case " + expr.getTokenValue() : "case";
    }

    private static boolean isJumpStatement(AstNode node) {
        return node.is(
                FlexGrammar.BREAK_STATEMENT,
                FlexGrammar.RETURN_STATEMENT,
                FlexGrammar.GOTO_STATEMENT,
                FlexGrammar.CONTINUE_STATEMENT)
                || (node.is(FlexGrammar.STATEMENT)
                        && node.getFirstChild() != null
                        && node.getFirstChild().is(
                                FlexGrammar.BREAK_STATEMENT,
                                FlexGrammar.RETURN_STATEMENT,
                                FlexGrammar.GOTO_STATEMENT,
                                FlexGrammar.CONTINUE_STATEMENT));
    }
}