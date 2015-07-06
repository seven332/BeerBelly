package com.hippo.beerbelly;

import java.util.concurrent.locks.ReentrantLock;

public class CounterLock extends ReentrantLock {

    private int count;


    public boolean isFree() {
        return count == 0;
    }

    public void release() {
        count--;
        if (count < 0) {
            throw new IllegalStateException("Release time is more than occupy time");
        }
    }

    public void occupy() {
        count++;
    }
}
