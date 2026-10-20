// Copyright (c)  2023  Xiaomi Corporation (sherpa-onnx, Apache-2.0)
// Java port of the sherpa-onnx v1.13.8 TTS config classes (kotlin-api/Tts.kt): names, types
// and defaults exactly as the native library expects them.
package com.k2fsa.sherpa.onnx;

public class OfflineTtsConfig {
    public OfflineTtsModelConfig model = new OfflineTtsModelConfig();
    public String ruleFsts = "";
    public String ruleFars = "";
    public int maxNumSentences = 1;
    public float silenceScale = 0.2f;
}

class OfflineTtsVitsModelConfig {
    public String model = "";
    public String lexicon = "";
    public String tokens = "";
    public String dataDir = "";
    public String dictDir = "";
    public float noiseScale = 0.667f;
    public float noiseScaleW = 0.8f;
    public float lengthScale = 1.0f;
}

class OfflineTtsMatchaModelConfig {
    public String acousticModel = "";
    public String vocoder = "";
    public String lexicon = "";
    public String tokens = "";
    public String dataDir = "";
    public String dictDir = "";
    public float noiseScale = 1.0f;
    public float lengthScale = 1.0f;
}

class OfflineTtsKokoroModelConfig {
    public String model = "";
    public String voices = "";
    public String tokens = "";
    public String dataDir = "";
    public String lexicon = "";
    public String lang = "";
    public String dictDir = "";
    public float lengthScale = 1.0f;
}

class OfflineTtsZipVoiceModelConfig {
    public String tokens = "";
    public String encoder = "";
    public String decoder = "";
    public String vocoder = "";
    public String dataDir = "";
    public String lexicon = "";
    public float featScale = 0.1f;
    public float tShift = 0.5f;
    public float targetRms = 0.1f;
    public float guidanceScale = 1.0f;
}

class OfflineTtsKittenModelConfig {
    public String model = "";
    public String voices = "";
    public String tokens = "";
    public String dataDir = "";
    public float lengthScale = 1.0f;
}

class OfflineTtsPocketModelConfig {
    public String lmFlow = "";
    public String lmMain = "";
    public String encoder = "";
    public String decoder = "";
    public String textConditioner = "";
    public String vocabJson = "";
    public String tokenScoresJson = "";
    public int voiceEmbeddingCacheCapacity = 50;
}

class OfflineTtsSupertonicModelConfig {
    public String durationPredictor = "";
    public String textEncoder = "";
    public String vectorEstimator = "";
    public String vocoder = "";
    public String ttsJson = "";
    public String unicodeIndexer = "";
    public String voiceStyle = "";
}
