package com.vvvvdddd.bind.plugin;

public final class SessionGate {
    private double tokens;
    private long updated;
    private int inFlight;

    public SessionGate(int burst, long now) {
        tokens = burst;
        updated = now;
    }

    public synchronized boolean acquire(int burst, double perSecond, int maximumInFlight, long now) {
        double elapsed = Math.max(0L, now - updated) / 1_000_000_000.0;
        tokens = Math.min(burst, tokens + elapsed * perSecond);
        updated = now;
        if (tokens < 1 || inFlight >= maximumInFlight) return false;
        tokens--;
        inFlight++;
        return true;
    }

    public synchronized void release() { if (inFlight > 0) inFlight--; }
}
