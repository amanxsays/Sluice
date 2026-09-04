package dev.sluice.core;

import java.util.Random;

public class BackoffCalculator {

    final int baseDelaySeconds;
    final int maxDelaySeconds;
    final Random random;

    public BackoffCalculator(int baseDelaySeconds,int maxDelaySeconds){
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        random = new Random();
    }

    BackoffCalculator(int baseDelaySeconds,int maxDelaySeconds,Random random){
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.random = random;
    }

    public int nextDelaySeconds(int attempts){
        int cappedAttempts = Math.min(attempts, 30);
        long exponentialDelay = (long) baseDelaySeconds*(1L << cappedAttempts);
        long cappedDelay = Math.min(exponentialDelay,maxDelaySeconds);

        return random.nextInt((int) cappedDelay+1);
    }
}
