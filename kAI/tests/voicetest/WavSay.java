/*
 * Copyright (C) 2026 KiroOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kiroos.kai;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.DataOutputStream;
import java.io.FileOutputStream;

/**
 * Says a text with one of kAI's voices into a 16-bit mono WAV (for videos and listening
 * tests): WavSay <paola|kristin> <out.wav> <text>.
 */
public final class WavSay {
    public static void main(String[] args) throws Exception {
        final String dir = KaiSpeech.DIR;
        final OfflineTtsConfig c = new OfflineTtsConfig();
        c.model = OfflineTtsModelConfig.vits(dir + "/" + args[0] + "/model.onnx",
                dir + "/" + args[0] + "/tokens.txt", dir + "/espeak-ng-data", 4);
        final OfflineTts tts = new OfflineTts(c);
        final GeneratedAudio a = tts.generate(KaiSpeech.speakable(args[2]), 0, 1.0f);
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(args[1]))) {
            final int n = a.samples.length;
            o.writeBytes("RIFF");
            o.writeInt(Integer.reverseBytes(36 + n * 2));
            o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(a.sampleRate));
            o.writeInt(Integer.reverseBytes(a.sampleRate * 2));
            o.writeShort(Short.reverseBytes((short) 2));
            o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data");
            o.writeInt(Integer.reverseBytes(n * 2));
            for (float s : a.samples) {
                o.writeShort(Short.reverseBytes((short) Math.round(Math.max(-1f, Math.min(1f, s)) * 32767)));
            }
        }
        System.out.println(args[1] + ": " + a.samples.length + " samples at " + a.sampleRate + " Hz");
        tts.release();
        System.exit(0);
    }
}
