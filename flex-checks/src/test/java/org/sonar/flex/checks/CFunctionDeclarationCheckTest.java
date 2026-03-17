package org.sonar.flex.checks;

import java.io.File;
import org.junit.jupiter.api.Test;

public class CFunctionDeclarationCheckTest {

    private final CFunctionDeclarationCheck check = new CFunctionDeclarationCheck();

    @Test
    public void test() {
        FlexVerifier.verify(
                new File("src/test/resources/checks/CFunctionDeclaration.c"),
                check);
    }
}