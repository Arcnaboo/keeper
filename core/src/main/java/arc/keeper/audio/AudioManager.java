package arc.keeper.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;

import arc.keeper.data.SaveData;

/**
 * Procedural audio. We synthesize every sound in real time on a single background
 * thread that owns an {@link AudioDevice}, so the game ships with zero audio assets.
 *
 * <p>The thread mixes a soft ambient pad (whose intensity tracks gameplay danger)
 * with up to {@value #MAX_VOICES} one-shot SFX voices. Each SFX is a tiny envelope
 * over a simple oscillator — cheap and good enough for arcade-style juice.
 */
public class AudioManager {

    /** Sample rate. 22050Hz keeps CPU low and is plenty for blips. */
    private static final int SAMPLE_RATE = 22050;
    /** Block size pushed to the device per loop iteration. ~23ms — low enough latency. */
    private static final int BLOCK_SIZE = 512;
    private static final int MAX_VOICES = 12;

    private final SaveData save;

    private volatile boolean running = true;
    private volatile boolean muted;
    private volatile float musicIntensity; // 0..1, ramps with danger

    private Thread audioThread;
    private AudioDevice device;

    private final Voice[] voices = new Voice[MAX_VOICES];
    /** Phase accumulators for the ambient pad's two detuned sines. */
    private double padPhase1, padPhase2, padPhase3;

    public AudioManager(SaveData save) {
        this.save = save;
        this.muted = !save.isAudioOn();
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
        startThread();
    }

    private void startThread() {
        try {
            device = Gdx.audio.newAudioDevice(SAMPLE_RATE, true);
        } catch (Throwable t) {
            // Headless / no audio: silently skip — game still runs.
            device = null;
            return;
        }
        audioThread = new Thread(this::audioLoop, "thumbkeeper-audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public boolean isMuted() { return muted; }

    public void setMuted(boolean m) {
        muted = m;
        save.setAudioOn(!m);
    }

    public void toggleMuted() { setMuted(!muted); }

    /** 0 = calm; 1 = on the edge. Smoothly tracked inside the audio loop. */
    public void setMusicIntensity(float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        this.musicIntensity = t;
    }

    // ---------------------------------------------------------------------
    // Public SFX API — kept short so screens can call without thinking.
    // ---------------------------------------------------------------------

    /** Quick jump release. Pitch & duration scale with charge level [0..1]. */
    public void playJump(float charge) {
        if (charge < 0f) charge = 0f;
        if (charge > 1f) charge = 1f;
        float freq = 260f + 320f * charge;       // higher pitch when fully charged
        float dur  = 0.10f + 0.12f * charge;
        triggerVoice(freq, dur, 0.30f, Wave.TRIANGLE, 0.5f);
    }

    /** Charge build-up tick (call ~every 0.08s while charging). */
    public void playChargeTick(float charge) {
        float freq = 180f + 380f * charge;
        triggerVoice(freq, 0.04f, 0.10f, Wave.SQUARE, 0f);
    }

    /** Landing thump. Velocity is the vertical speed at impact (>=0). */
    public void playLand(float velocity) {
        float t = velocity / 900f;
        if (t > 1f) t = 1f;
        float dur = 0.06f + 0.10f * t;
        triggerVoice(90f + 60f * (1f - t), dur, 0.18f + 0.18f * t, Wave.SINE, 0.6f);
    }

    /** Bounce pad ascending blip. */
    public void playBounce() {
        triggerVoice(620f, 0.10f, 0.30f, Wave.TRIANGLE, 0.0f);
        triggerVoice(880f, 0.10f, 0.18f, Wave.TRIANGLE, 0.0f);
    }

    public void playWallSlide() {
        triggerVoice(140f, 0.05f, 0.05f, Wave.NOISE, 0.2f);
    }

    public void playNearMiss() {
        triggerVoice(1240f, 0.14f, 0.22f, Wave.SINE, 0.0f);
    }

    public void playTeleport() {
        triggerVoice(740f, 0.12f, 0.28f, Wave.SQUARE, 0.0f);
        triggerVoice(440f, 0.18f, 0.22f, Wave.SQUARE, 0.05f);
    }

    public void playPickup() {
        triggerVoice(880f, 0.08f, 0.28f, Wave.TRIANGLE, 0f);
        triggerVoice(1320f, 0.12f, 0.18f, Wave.TRIANGLE, 0.04f);
    }

    /** Death sting — descending sweep. */
    public void playDeath() {
        triggerVoice(420f, 0.45f, 0.45f, Wave.SAW, 0.0f, /*sweepHz/sec*/ -650f);
        triggerVoice(110f, 0.55f, 0.40f, Wave.SINE, 0.0f, -180f);
    }

    public void playMenuClick() {
        triggerVoice(660f, 0.06f, 0.20f, Wave.TRIANGLE, 0f);
    }

    public void dispose() {
        running = false;
        if (audioThread != null) {
            try { audioThread.join(200); } catch (InterruptedException ignored) {}
        }
        if (device != null) device.dispose();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private enum Wave { SINE, TRIANGLE, SQUARE, SAW, NOISE }

    private static final class Voice {
        volatile boolean active;
        double phase;
        float  freq;
        float  freqRatePerSample; // for pitch sweeps (used by death sting)
        float  amp;
        float  attackSamples;
        float  decaySamples;
        float  ageSamples;
        float  totalSamples;
        Wave   wave = Wave.SINE;
        /** noise state for NOISE wave */
        int    noiseSeed = 0xDEADBEEF;
    }

    private void triggerVoice(float freq, float duration, float amp, Wave wave, float attackFrac) {
        triggerVoice(freq, duration, amp, wave, attackFrac, 0f);
    }

    private void triggerVoice(float freq, float duration, float amp, Wave wave, float attackFrac, float sweepHzPerSec) {
        if (device == null) return;
        // Find an inactive voice slot. If all busy, steal the quietest one.
        int idx = -1;
        float quietest = Float.MAX_VALUE;
        int quietestIdx = 0;
        for (int i = 0; i < MAX_VOICES; i++) {
            if (!voices[i].active) { idx = i; break; }
            if (voices[i].amp < quietest) { quietest = voices[i].amp; quietestIdx = i; }
        }
        if (idx == -1) idx = quietestIdx;

        Voice v = voices[idx];
        v.phase = 0;
        v.freq = freq;
        v.freqRatePerSample = sweepHzPerSec / SAMPLE_RATE;
        v.amp  = amp;
        v.wave = wave;
        v.ageSamples = 0f;
        v.totalSamples = duration * SAMPLE_RATE;
        v.attackSamples = attackFrac * v.totalSamples;
        v.decaySamples  = v.totalSamples - v.attackSamples;
        v.noiseSeed = (int) (System.nanoTime() | 1);
        v.active = true;
    }

    private void audioLoop() {
        short[] block = new short[BLOCK_SIZE];
        float currentMusic = 0f;
        while (running) {
            // Smooth-track the target intensity so volume swells don't pop.
            currentMusic += (musicIntensity - currentMusic) * 0.02f;
            float padGain = muted ? 0f : (0.04f + 0.16f * currentMusic);
            float sfxGain = muted ? 0f : 1f;

            for (int i = 0; i < BLOCK_SIZE; i++) {
                float sample = 0f;

                // Ambient pad: three slightly-detuned sines giving a soft synthwave drone.
                sample += (float) Math.sin(padPhase1) * 0.5f;
                sample += (float) Math.sin(padPhase2) * 0.35f;
                sample += (float) Math.sin(padPhase3) * 0.25f;
                sample *= padGain;
                padPhase1 += 2.0 * Math.PI * 110.0 / SAMPLE_RATE;
                padPhase2 += 2.0 * Math.PI * 165.0 / SAMPLE_RATE;
                padPhase3 += 2.0 * Math.PI * (220.0 + 8.0 * Math.sin(padPhase1 * 0.005)) / SAMPLE_RATE;

                // SFX voices.
                for (int vi = 0; vi < MAX_VOICES; vi++) {
                    Voice v = voices[vi];
                    if (!v.active) continue;
                    float env;
                    if (v.ageSamples < v.attackSamples && v.attackSamples > 0f) {
                        env = v.ageSamples / v.attackSamples;
                    } else {
                        float t = (v.ageSamples - v.attackSamples) / Math.max(1f, v.decaySamples);
                        env = 1f - t;
                    }
                    if (env < 0f) env = 0f;

                    float osc;
                    switch (v.wave) {
                        case TRIANGLE: {
                            double x = (v.phase / (2 * Math.PI)) % 1.0;
                            osc = (float) (4 * Math.abs(x - 0.5) - 1);
                            break;
                        }
                        case SQUARE: osc = Math.sin(v.phase) >= 0 ? 1f : -1f; break;
                        case SAW: {
                            double x = (v.phase / (2 * Math.PI)) % 1.0;
                            osc = (float) (2 * x - 1);
                            break;
                        }
                        case NOISE: {
                            v.noiseSeed = v.noiseSeed * 1664525 + 1013904223;
                            osc = (v.noiseSeed >>> 8) / (float)(1 << 23) - 1f;
                            break;
                        }
                        case SINE:
                        default: osc = (float) Math.sin(v.phase); break;
                    }
                    sample += osc * env * v.amp * sfxGain;

                    v.phase += 2.0 * Math.PI * v.freq / SAMPLE_RATE;
                    v.freq  += v.freqRatePerSample;
                    if (v.freq < 20f) v.freq = 20f;
                    v.ageSamples += 1f;
                    if (v.ageSamples >= v.totalSamples) v.active = false;
                }

                // Soft clipper to keep things from harshly distorting at peaks.
                sample = (float) Math.tanh(sample * 0.85);
                int s = (int) (sample * 12000f);
                if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
                block[i] = (short) s;
            }
            try {
                device.writeSamples(block, 0, BLOCK_SIZE);
            } catch (Throwable t) {
                // Device died (e.g. app paused) — wait a bit and try again.
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }
}
