package org.sonar.flex.checks;

import java.io.File;
import org.junit.jupiter.api.Test;

public class CSwitchStructureCheckTest {

    private final CSwitchStructureCheck check = new CSwitchStructureCheck();

    @Test
    public void test() {
        FlexVerifier.verify(
                new File("src/test/resources/checks/CSwitchStructure.c"),
                check);
    }
}