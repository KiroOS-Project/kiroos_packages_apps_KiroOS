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

import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Reads an answer aloud with KaiSpeech, sentence by sentence as kAI Live does, and prints
 * when each sentence was heard: VoiceTest <it|en> <text>.
 */
public final class VoiceTest {
    public static void main(String[] args) throws Exception {
        final Locale lang = new Locale(args[0]);
        final String[] sentences = args[1].split("(?<=[.!?])\\s+");
        final CountDownLatch all = new CountDownLatch(sentences.length);
        final long t0 = System.currentTimeMillis();
        final KaiSpeech s = new KaiSpeech(new KaiSpeech.Listener() {
            @Override
            public void onDone(String id) {
                System.out.println("heard " + id + " at " + (System.currentTimeMillis() - t0)
                        + " ms");
                all.countDown();
            }
        });
        System.out.println("voice: " + KaiSpeech.voiceFor(lang));
        for (int i = 0; i < sentences.length; i++) {
            s.speak(sentences[i], lang, "s" + (i + 1));
        }
        all.await();
        System.out.println("all " + sentences.length + " sentences in "
                + (System.currentTimeMillis() - t0) + " ms");
        s.shutdown();
        KaiSpeech.release();
        System.exit(0);
    }
}
