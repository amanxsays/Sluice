package dev.sluice.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BackoffCalculatorTest {
    
    @Test 
    void nextDelaySecondsNeverExceedsMaxDelay(){
        BackoffCalculator calculator = new BackoffCalculator(1,10);

        for(int i=0;i<20;i++){
            int result = calculator.nextDelaySeconds(20);
            
            assertTrue(result >= 0);
            assertTrue(result <= 10);
        }
    }

    @Test 
    void nextDelaySecondsIsReproducibleWithSeededRandom(){
        BackoffCalculator calculator = new BackoffCalculator(1, 100, new Random(42));
        int result = calculator.nextDelaySeconds(2);

        assertEquals(0, result);
        assertTrue(result >= 0 && result <= 4);
    }
}
