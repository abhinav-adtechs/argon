package com.argon.launcher;

// From Free My TV (MIT, github.com/simbasjungles/free-my-tv); see launcher/NOTICE.

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping, epsilon;

    Spring(float response, float damping, float initial) {
        this(response, damping, initial, 0.002f);
    }

    /** `epsilon`: how close counts as there (a spring in pixels stops once it's a fraction of one away). */
    Spring(float response, float damping, float initial, float epsilon) {
        this.response = response;
        this.damping = damping;
        this.epsilon = epsilon;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < epsilon && Math.abs(velocity) < epsilon * 5;
    }
}
