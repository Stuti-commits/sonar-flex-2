package org.sonar.flex.checks;

import java.io.File;
import org.junit.jupiter.api.Test;

public class CStringManipulationCheckTest {

    private final CStringManipulationCheck check = new CStringManipulationCheck();

    @Test
    public void test() {
        FlexVerifier.verify(
                new File("src/test/resources/checks/CStringManipulation.c"), check);
    }
}