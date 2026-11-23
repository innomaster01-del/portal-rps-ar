package io.github.innomaster01del.portalrps;

/**
 * One-Euro filter (Casiez et al. 2012) for a vector of values: heavy smoothing when the hand is
 * still (no jitter), light smoothing when it moves fast (no lag).
 */
final class OneEuro {
    private final double minCutoff, beta, dCutoff;
    private double[] x, dx;
    private long lastNs;

    OneEuro(double minCutoff, double beta, double dCutoff) {
        this.minCutoff = minCutoff;
        this.beta = beta;
        this.dCutoff = dCutoff;
    }

    void reset() { x = null; }

    private static double alpha(double cutoff, double dt) {
        double tau = 1.0 / (2 * Math.PI * cutoff);
        return 1.0 / (1.0 + tau / dt);
    }

    /** Filters v in place. timeNs: monotonic timestamp of this sample. */
    void filter(float[] v, long timeNs) {
        if (x == null || x.length != v.length || timeNs - lastNs > 400_000_000L) {
            x = new double[v.length];
            dx = new double[v.length];
            for (int i = 0; i < v.length; i++) x[i] = v[i];
            lastNs = timeNs;
            return;
        }
        double dt = Math.max((timeNs - lastNs) / 1e9, 1e-3);
        lastNs = timeNs;
        double ad = alpha(dCutoff, dt);
        // one shared speed for the whole hand keeps the fingers moving together
        double speed = 0;
        for (int i = 0; i < v.length; i++) {
            double d = (v[i] - x[i]) / dt;
            dx[i] = dx[i] + ad * (d - dx[i]);
            speed = Math.max(speed, Math.abs(dx[i]));
        }
        double a = alpha(minCutoff + beta * speed, dt);
        for (int i = 0; i < v.length; i++) {
            x[i] = x[i] + a * (v[i] - x[i]);
            v[i] = (float) x[i];
        }
    }
}
