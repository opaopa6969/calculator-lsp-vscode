package org.unlaxer.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class CalculatorParsersTest {

    @Test
    public void getFunctionCompletionsReturnsExpectedFunctions() {
        List<CalculatorParsers.FunctionCompletion> completions = CalculatorParsers.getFunctionCompletions();

        Set<String> names = new HashSet<>();
        for (CalculatorParsers.FunctionCompletion completion : completions) {
            names.add(completion.name());
        }

        assertEquals(5, completions.size());
        assertEquals(5, names.size());
        assertTrue(names.contains("sin"));
        assertTrue(names.contains("sqrt"));
        assertTrue(names.contains("cos"));
        assertTrue(names.contains("tan"));
        assertTrue(names.contains("log"));
    }
}
