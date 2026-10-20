// Copyright (c)  2023  Xiaomi Corporation (sherpa-onnx, Apache-2.0)
// Java port of sherpa-onnx v1.13.8 OfflineTtsModelConfig (kotlin-api/Tts.kt).
package com.k2fsa.sherpa.onnx;

public class OfflineTtsModelConfig {
    public OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
    public OfflineTtsMatchaModelConfig matcha = new OfflineTtsMatchaModelConfig();
    public OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
    public OfflineTtsZipVoiceModelConfig zipvoice = new OfflineTtsZipVoiceModelConfig();
    public OfflineTtsKittenModelConfig kitten = new OfflineTtsKittenModelConfig();
    public OfflineTtsPocketModelConfig pocket = new OfflineTtsPocketModelConfig();
    public OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
    public int numThreads = 1;
    public boolean debug = false;
    public String provider = "cpu";

    /** a Piper (VITS) voice: model, tokens and the espeak-ng data for its phonemes */
    public static OfflineTtsModelConfig vits(String model, String tokens, String dataDir,
            int threads) {
        final OfflineTtsModelConfig c = new OfflineTtsModelConfig();
        c.vits.model = model;
        c.vits.tokens = tokens;
        c.vits.dataDir = dataDir;
        c.numThreads = threads;
        return c;
    }
}
