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

/*
 * KiroOS Equalizer (KiroOS Beta 4): the equalizer of the platform, in place of the NXP bundle's.
 *
 * The NXP equalizer had five wide bands (60, 230, 910, 3600, 14000 Hz) that overlap so much a
 * band moved one way also moved its neighbours, and a boost simply clipped. This one has ten
 * octave bands (31 Hz to 16 kHz), each an RBJ peaking filter one octave wide, set in millibels
 * (0.01 dB) between -12 and +12 dB; Kiro Sound draws the exact response of the same filters.
 * A boost takes half of its gain back as headroom and a peak limiter catches the rest, so the
 * music never cracks.
 *
 * It answers the standard Equalizer parameters (android.media.audiofx.Equalizer), so music apps
 * that open their own equalizer get the ten bands too. Stereo 16-bit, as the effect chains of
 * Android 7 run.
 *
 * Bass (Kiro Sound's Bass slider, KIRO_PARAM_BASS / KIRO_PARAM_BASS_MODE): the NXP bass boost
 * raised frequencies the phone's speaker cannot move, then lowered the volume to make room, so
 * all one heard was a quieter phone. Here the bass follows the output:
 *  - speaker: a broad boost around 150 Hz, where the speaker still plays, plus virtual bass:
 *    the notes below it (filtered out of the mono sum) are rectified into their harmonics,
 *    which the speaker can play and from which the ear rebuilds the missing fundamental;
 *  - headphones / Bluetooth: a low shelf below 90 Hz, the real deep bass they can play.
 */

#define LOG_TAG "KiroEqualizer"

#include <errno.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include <cutils/log.h>
#include <hardware/audio_effect.h>
#include <audio_effects/effect_equalizer.h>

#define NUM_BANDS 10
#define LEVEL_MIN (-1200)   /* millibels */
#define LEVEL_MAX 1200
#define PRESET_CUSTOM (-1)
#define BAND_Q 1.41421356f  /* one octave */
#define LIMIT 0.966f        /* -0.3 dBFS */

/* KiroOS parameters (int32 param, int16 or int32 value), next to the standard ones */
#define KIRO_PARAM_BASS 0x4B01          /* 0-1000 */
#define KIRO_PARAM_BASS_MODE 0x4B02     /* 0 speaker, 1 headphones / Bluetooth */
#define BASS_SPEAKER_HZ 150.0f
#define BASS_SPEAKER_DB 8.0f
#define BASS_SPEAKER_Q 0.8f
#define BASS_VIRTUAL_HZ 140.0f          /* below this the speaker gets harmonics instead */
#define BASS_VIRTUAL_GAIN 1.6f
#define BASS_SHELF_HZ 90.0f
#define BASS_SHELF_DB 10.0f
/*
 * Dynamic bass: the most the boosted bass band may reach (amplitude, full scale = 1). Loud
 * music already has strong bass, and +8 dB more pushed the speaker past its travel (it
 * cracked at full volume); the boost backs off as the bass gets louder so the band stays
 * under this, and comes back when it is quieter. Headphones take more.
 */
#define BASS_SPEAKER_LIMIT 0.30f
#define BASS_HEADPHONE_LIMIT 0.55f

static const int kCenterHz[NUM_BANDS] = {
    31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
};

/* the NXP equalizer's presets (dB at 60, 230, 910, 3600, 14000 Hz), spread over the ten bands,
 * so apps that list presets find the same ones */
#define NUM_PRESETS 10
static const char *kPresetNames[NUM_PRESETS] = {
    "Normal", "Classical", "Dance", "Flat", "Folk", "Heavy Metal", "Hip Hop", "Jazz", "Pop",
    "Rock",
};
static const float kPreset5[NUM_PRESETS][5] = {
    { 3, 0, 0, 0, 3 },
    { 5, 3, -2, 4, 4 },
    { 6, 0, 2, 4, 1 },
    { 0, 0, 0, 0, 0 },
    { 3, 0, 0, 2, -1 },
    { 4, 1, 9, 3, 0 },
    { 5, 3, 0, 1, 3 },
    { 4, 2, -2, 2, 5 },
    { -1, 2, 5, 1, -2 },
    { 5, 3, -1, 3, 5 },
};
static const float kPreset5Hz[5] = { 60, 230, 910, 3600, 14000 };

/* KiroOS Equalizer: type Equalizer, implementation 6b69726f-4571-11ef-8b4f-4b69726f4f53 */
static const effect_descriptor_t kDescriptor = {
    { 0x0bed4300, 0xddd6, 0x11db, 0x8f34, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b } },
    { 0x6b69726f, 0x4571, 0x11ef, 0x8b4f, { 0x4b, 0x69, 0x72, 0x6f, 0x4f, 0x53 } },
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_ANY,
    20,     /* cpu load, 0.1 MIPS */
    1,      /* memory, KB */
    "KiroOS Equalizer",
    "KiroOS",
};

typedef struct {
    float b0, b1, b2, a1, a2;
} biquad_t;

typedef struct {
    const struct effect_interface_s *itfe;
    effect_config_t config;
    int enabled;
    int16_t levels[NUM_BANDS];      /* millibels */
    int16_t preset;
    float rate;
    biquad_t bq[NUM_BANDS];
    int active[NUM_BANDS];          /* band not at 0 dB */
    float z[NUM_BANDS][2][2];       /* [band][channel][state] */
    float pregain;
    float gain;                     /* limiter */
    float release;
    /* bass */
    int bass;                       /* 0-1000 */
    int bassMode;
    int bassActive;
    biquad_t bassBq;                /* the bass band: band pass (speaker) or low pass */
    float bassZ[2][2];
    float bassExtra;                /* full boost: out = in + extra * dyn * band */
    float bassLimit;
    float bassEnv;                  /* level of the bass band */
    float bassDyn;                  /* 0-1, how much of the boost is applied now */
    float envAtk, envRel, dynAtk, dynRel, limAtk;
    float vEnv;                     /* level of the missing bass (virtual bass) */
    int virtualActive;
    float virtualGain;
    biquad_t vLow[2];               /* 4th-order low pass of the mono sum: the missing bass */
    biquad_t vBand[2];              /* high pass + low pass around the harmonics */
    float vLowZ[2][2];
    float vBandZ[2][2];
} eq_context_t;

static const struct effect_interface_s gInterface;

static float presetLevel(int preset, int band)
{
    /* linear in log frequency between the five NXP points, flat beyond the ends */
    const float f = (float) kCenterHz[band];
    const float *p = kPreset5[preset];
    if (f <= kPreset5Hz[0]) return p[0];
    if (f >= kPreset5Hz[4]) return p[4];
    for (int i = 0; i < 4; i++) {
        if (f <= kPreset5Hz[i + 1]) {
            const float t = logf(f / kPreset5Hz[i]) / logf(kPreset5Hz[i + 1] / kPreset5Hz[i]);
            return p[i] + t * (p[i + 1] - p[i]);
        }
    }
    return 0;
}

/* RBJ cookbook filters */
static void peaking(biquad_t *q, float rate, float hz, float db, float Q)
{
    const float A = powf(10.0f, db / 40.0f);
    const float w0 = 2.0f * (float) M_PI * hz / rate;
    const float alpha = sinf(w0) / (2.0f * Q);
    const float cw = cosf(w0);
    const float a0 = 1.0f + alpha / A;
    q->b0 = (1.0f + alpha * A) / a0;
    q->b1 = (-2.0f * cw) / a0;
    q->b2 = (1.0f - alpha * A) / a0;
    q->a1 = (-2.0f * cw) / a0;
    q->a2 = (1.0f - alpha / A) / a0;
}

static void passFilter(biquad_t *q, float rate, float hz, int high)
{
    const float w0 = 2.0f * (float) M_PI * hz / rate;
    const float cw = cosf(w0);
    const float alpha = sinf(w0) / (2.0f * 0.70710678f);
    const float a0 = 1.0f + alpha;
    if (high) {
        q->b0 = (1.0f + cw) / 2.0f / a0;
        q->b1 = -(1.0f + cw) / a0;
    } else {
        q->b0 = (1.0f - cw) / 2.0f / a0;
        q->b1 = (1.0f - cw) / a0;
    }
    q->b2 = q->b0;
    q->a1 = -2.0f * cw / a0;
    q->a2 = (1.0f - alpha) / a0;
}

/* band pass, 0 dB at the centre */
static void bandPass(biquad_t *q, float rate, float hz, float Q)
{
    const float w0 = 2.0f * (float) M_PI * hz / rate;
    const float alpha = sinf(w0) / (2.0f * Q);
    const float a0 = 1.0f + alpha;
    q->b0 = alpha / a0;
    q->b1 = 0;
    q->b2 = -alpha / a0;
    q->a1 = -2.0f * cosf(w0) / a0;
    q->a2 = (1.0f - alpha) / a0;
}

static inline float runBiquad(const biquad_t *q, float *z, float v)
{
    /* transposed direct form II */
    const float y = q->b0 * v + z[0];
    z[0] = q->b1 * v - q->a1 * y + z[1];
    z[1] = q->b2 * v - q->a2 * y;
    return y;
}

static void design(eq_context_t *c)
{
    float maxBoost = 0;
    const float s = c->bass / 1000.0f;
    float bassDb = 0;
    c->bassActive = c->bass > 0;
    c->virtualActive = c->bass > 0 && c->bassMode == 0;
    if (c->bassActive) {
        /* in parallel: in + extra * band is the peak (band pass) or the shelf (low pass),
         * and the dynamics can scale the extra part alone */
        if (c->bassMode == 0) {
            bassDb = BASS_SPEAKER_DB * s;
            bandPass(&c->bassBq, c->rate, BASS_SPEAKER_HZ, BASS_SPEAKER_Q);
            c->bassLimit = BASS_SPEAKER_LIMIT;
        } else {
            bassDb = BASS_SHELF_DB * s;
            passFilter(&c->bassBq, c->rate, BASS_SHELF_HZ, 0);
            c->bassLimit = BASS_HEADPHONE_LIMIT;
        }
        c->bassExtra = powf(10.0f, bassDb / 20.0f) - 1.0f;
    }
    if (c->virtualActive) {
        c->virtualGain = BASS_VIRTUAL_GAIN * s;
        passFilter(&c->vLow[0], c->rate, BASS_VIRTUAL_HZ, 0);
        passFilter(&c->vLow[1], c->rate, BASS_VIRTUAL_HZ, 0);
        passFilter(&c->vBand[0], c->rate, BASS_VIRTUAL_HZ, 1);  /* drops the DC of |x| */
        passFilter(&c->vBand[1], c->rate, 600.0f, 0);
    }
    for (int b = 0; b < NUM_BANDS; b++) {
        const float db = c->levels[b] / 100.0f;
        c->active[b] = c->levels[b] != 0 && kCenterHz[b] < c->rate * 0.45f;
        if (!c->active[b]) {
            continue;
        }
        if (db > maxBoost) maxBoost = db;
        peaking(&c->bq[b], c->rate, kCenterHz[b], db, BAND_Q);
    }
    /* a quarter of the biggest band boost back as headroom, none for the bass: on the output
     * mix the volume is already applied, so below full volume there is room anyway, and a
     * bass that lowers everything else only sounds quieter (what the NXP bass boost did).
     * At full volume the limiter catches the peaks. */
    (void) bassDb;
    c->pregain = powf(10.0f, -maxBoost * 0.25f / 20.0f);
}

static void resetState(eq_context_t *c)
{
    memset(c->z, 0, sizeof(c->z));
    memset(c->bassZ, 0, sizeof(c->bassZ));
    memset(c->vLowZ, 0, sizeof(c->vLowZ));
    memset(c->vBandZ, 0, sizeof(c->vBandZ));
    c->gain = 1.0f;
    c->bassEnv = 0;
    c->bassDyn = 0;     /* the boost fades in */
    c->vEnv = 0;
}

static float coef(float seconds, float rate)
{
    return 1.0f - expf(-1.0f / (seconds * rate));
}

static void setRate(eq_context_t *c, uint32_t rate)
{
    c->rate = rate > 0 ? (float) rate : 48000.0f;
    /* limiter: 0.5 ms attack, back to full gain in about 120 ms */
    c->release = coef(0.120f, c->rate);
    c->limAtk = coef(0.0005f, c->rate);
    /* bass dynamics: the level follows in 5 ms / 200 ms, the boost backs off in 10 ms and
     * comes back over 400 ms (slow enough not to be heard moving) */
    c->envAtk = coef(0.005f, c->rate);
    c->envRel = coef(0.200f, c->rate);
    c->dynAtk = coef(0.010f, c->rate);
    c->dynRel = coef(0.400f, c->rate);
    design(c);
}

static void setPreset(eq_context_t *c, int preset)
{
    for (int b = 0; b < NUM_BANDS; b++) {
        c->levels[b] = (int16_t) lrintf(presetLevel(preset, b) * 100.0f);
    }
    c->preset = (int16_t) preset;
    design(c);
}

static int16_t clampLevel(int v)
{
    return (int16_t) (v < LEVEL_MIN ? LEVEL_MIN : v > LEVEL_MAX ? LEVEL_MAX : v);
}

/* the band whose octave holds this frequency (mHz) */
static int bandForFreq(int32_t mhz)
{
    const float hz = mhz / 1000.0f;
    int best = 0;
    float bestDist = 1e9f;
    for (int b = 0; b < NUM_BANDS; b++) {
        const float d = fabsf(logf((hz > 1 ? hz : 1) / kCenterHz[b]));
        if (d < bestDist) {
            bestDist = d;
            best = b;
        }
    }
    return best;
}

/* ---- processing ------------------------------------------------------------------------ */

static int32_t eq_process(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out)
{
    eq_context_t *c = (eq_context_t *) self;
    if (c == NULL || in == NULL || out == NULL || in->raw == NULL || out->raw == NULL
            || in->frameCount != out->frameCount) {
        return -EINVAL;
    }
    if (!c->enabled) {
        return -ENODATA;
    }
    const size_t frames = in->frameCount;
    const int16_t *src = in->s16;
    int16_t *dst = out->s16;
    const int accumulate = c->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;

    for (size_t i = 0; i < frames; i++) {
        float x[2] = { src[2 * i] * (1.0f / 32768.0f), src[2 * i + 1] * (1.0f / 32768.0f) };
        /* virtual bass: the harmonics of what lies below the speaker's range, from the mono
         * sum (|x| doubles the frequency: 60 Hz becomes 120, 180, 240 Hz...) */
        float harm = 0;
        float band[2] = { 0, 0 };
        if (c->bassActive) {
            band[0] = runBiquad(&c->bassBq, c->bassZ[0], x[0] + 1e-20f);
            band[1] = runBiquad(&c->bassBq, c->bassZ[1], x[1] + 1e-20f);
            const float lvl = fmaxf(fabsf(band[0]), fabsf(band[1]));
            c->bassEnv += (lvl - c->bassEnv) * (lvl > c->bassEnv ? c->envAtk : c->envRel);
            /* the share of the boost that keeps the boosted band under the limit */
            float want = 1.0f;
            if (c->bassEnv * (1.0f + c->bassExtra) > c->bassLimit) {
                want = (c->bassLimit / fmaxf(c->bassEnv, 1e-6f) - 1.0f) / c->bassExtra;
                if (want < 0) want = 0;
            }
            c->bassDyn += (want - c->bassDyn) * (want < c->bassDyn ? c->dynAtk : c->dynRel);
        }
        if (c->virtualActive) {
            /* x^2 / level: a clean second harmonic at the level of the missing bass (the
             * rectifier used before had sharp corners, harsh upper harmonics) */
            float low = (x[0] + x[1]) * 0.5f + 1e-20f;
            low = runBiquad(&c->vLow[0], c->vLowZ[0], low);
            low = runBiquad(&c->vLow[1], c->vLowZ[1], low);
            const float a = fabsf(low);
            c->vEnv += (a - c->vEnv) * (a > c->vEnv ? c->envAtk : c->envRel);
            harm = low * low / (c->vEnv + 1e-3f);
            harm = runBiquad(&c->vBand[0], c->vBandZ[0], harm);
            harm = runBiquad(&c->vBand[1], c->vBandZ[1], harm) * c->virtualGain * c->bassDyn;
        }
        const float extra = c->bassExtra * c->bassDyn;
        for (int ch = 0; ch < 2; ch++) {
            /* the tiny offset keeps the filter states out of denormals in silence (slow on
             * ARM VFP); it is far below what 16 bits can show */
            float v = (x[ch] + extra * band[ch] + harm) * c->pregain + 1e-20f;
            for (int b = 0; b < NUM_BANDS; b++) {
                if (!c->active[b]) continue;
                v = runBiquad(&c->bq[b], c->z[b][ch], v);
            }
            x[ch] = v;
        }
        /* peak limiter on both channels together (keeps the stereo image) */
        const float peak = fmaxf(fabsf(x[0]), fabsf(x[1]));
        const float want = peak > LIMIT ? LIMIT / peak : 1.0f;
        /* 0.5 ms attack (an instant one modulated the waveform, a crackle when it worked
         * hard), smooth release; what the attack lets through is rounded off below */
        c->gain += (want - c->gain) * (want < c->gain ? c->limAtk : c->release);
        for (int ch = 0; ch < 2; ch++) {
            float y = x[ch] * c->gain;
            const float ay = fabsf(y);
            if (ay > LIMIT) {
                /* soft knee above -0.3 dBFS: never past full scale, no hard edge */
                const float k = 1.0f - LIMIT;
                y = copysignf(LIMIT + k * tanhf((ay - LIMIT) / k), y);
            }
            float s = y * 32767.0f;
            if (accumulate) s += dst[2 * i + ch];
            const long r = lrintf(s);
            dst[2 * i + ch] = (int16_t) (r > 32767 ? 32767 : r < -32768 ? -32768 : r);
        }
    }
    return 0;
}

/* ---- parameters ------------------------------------------------------------------------ */

static int getParameter(eq_context_t *c, const int32_t *param, uint32_t psize, void *value,
        uint32_t *vsize)
{
    if (psize < sizeof(int32_t)) return -EINVAL;
    const int32_t cmd = param[0];
    const int32_t arg = psize >= 2 * sizeof(int32_t) ? param[1] : 0;

    if (cmd == KIRO_PARAM_BASS || cmd == KIRO_PARAM_BASS_MODE) {
        const int32_t v = cmd == KIRO_PARAM_BASS ? c->bass : c->bassMode;
        if (*vsize >= sizeof(int32_t)) {
            *(int32_t *) value = v;
            *vsize = sizeof(int32_t);
        } else if (*vsize >= sizeof(int16_t)) {
            *(int16_t *) value = (int16_t) v;
            *vsize = sizeof(int16_t);
        } else {
            return -EINVAL;
        }
        return 0;
    }

    switch (cmd) {
    case EQ_PARAM_NUM_BANDS:
    case EQ_PARAM_GET_NUM_OF_PRESETS:
    case EQ_PARAM_CUR_PRESET:
    case EQ_PARAM_GET_BAND:
    case EQ_PARAM_BAND_LEVEL:
        if (*vsize < sizeof(int16_t)) return -EINVAL;
        *vsize = sizeof(int16_t);
        break;
    case EQ_PARAM_LEVEL_RANGE:
        if (*vsize < 2 * sizeof(int16_t)) return -EINVAL;
        *vsize = 2 * sizeof(int16_t);
        break;
    case EQ_PARAM_CENTER_FREQ:
        if (*vsize < sizeof(int32_t)) return -EINVAL;
        *vsize = sizeof(int32_t);
        break;
    case EQ_PARAM_BAND_FREQ_RANGE:
        if (*vsize < 2 * sizeof(int32_t)) return -EINVAL;
        *vsize = 2 * sizeof(int32_t);
        break;
    case EQ_PARAM_GET_PRESET_NAME:
        if (*vsize < 1) return -EINVAL;
        break;
    case EQ_PARAM_PROPERTIES:
        if (*vsize < (2 + NUM_BANDS) * sizeof(int16_t)) return -EINVAL;
        *vsize = (2 + NUM_BANDS) * sizeof(int16_t);
        break;
    default:
        return -EINVAL;
    }

    switch (cmd) {
    case EQ_PARAM_NUM_BANDS:
        *(uint16_t *) value = NUM_BANDS;
        break;
    case EQ_PARAM_LEVEL_RANGE:
        ((int16_t *) value)[0] = LEVEL_MIN;
        ((int16_t *) value)[1] = LEVEL_MAX;
        break;
    case EQ_PARAM_BAND_LEVEL:
        if (arg < 0 || arg >= NUM_BANDS) return -EINVAL;
        *(int16_t *) value = c->levels[arg];
        break;
    case EQ_PARAM_CENTER_FREQ:
        if (arg < 0 || arg >= NUM_BANDS) return -EINVAL;
        *(int32_t *) value = kCenterHz[arg] * 1000;
        break;
    case EQ_PARAM_BAND_FREQ_RANGE:
        if (arg < 0 || arg >= NUM_BANDS) return -EINVAL;
        ((int32_t *) value)[0] = (int32_t) (kCenterHz[arg] * 1000 / 1.41421356f);
        ((int32_t *) value)[1] = (int32_t) (kCenterHz[arg] * 1000 * 1.41421356f);
        break;
    case EQ_PARAM_GET_BAND:
        *(uint16_t *) value = (uint16_t) bandForFreq(arg);
        break;
    case EQ_PARAM_CUR_PRESET:
        *(int16_t *) value = c->preset;
        break;
    case EQ_PARAM_GET_NUM_OF_PRESETS:
        *(uint16_t *) value = NUM_PRESETS;
        break;
    case EQ_PARAM_GET_PRESET_NAME: {
        if (arg < 0 || arg >= NUM_PRESETS) return -EINVAL;
        char *name = (char *) value;
        strncpy(name, kPresetNames[arg], *vsize - 1);
        name[*vsize - 1] = '\0';
        *vsize = strlen(name) + 1;
        break;
    }
    case EQ_PARAM_PROPERTIES: {
        int16_t *p = (int16_t *) value;
        p[0] = c->preset;
        p[1] = NUM_BANDS;
        for (int b = 0; b < NUM_BANDS; b++) p[2 + b] = c->levels[b];
        break;
    }
    }
    return 0;
}

static int setParameter(eq_context_t *c, const int32_t *param, uint32_t psize,
        const void *value, uint32_t vsize)
{
    if (psize < sizeof(int32_t)) return -EINVAL;
    const int32_t cmd = param[0];

    if (cmd == KIRO_PARAM_BASS || cmd == KIRO_PARAM_BASS_MODE) {
        int32_t v;
        if (vsize >= sizeof(int32_t)) v = *(const int32_t *) value;
        else if (vsize >= sizeof(int16_t)) v = *(const int16_t *) value;
        else return -EINVAL;
        if (cmd == KIRO_PARAM_BASS) {
            c->bass = v < 0 ? 0 : v > 1000 ? 1000 : v;
        } else {
            c->bassMode = v ? 1 : 0;
        }
        design(c);
        return 0;
    }

    switch (cmd) {
    case EQ_PARAM_CUR_PRESET: {
        if (vsize < sizeof(int16_t)) return -EINVAL;
        const int preset = *(const int16_t *) value;
        if (preset < 0 || preset >= NUM_PRESETS) return -EINVAL;
        setPreset(c, preset);
        return 0;
    }
    case EQ_PARAM_BAND_LEVEL: {
        if (psize < 2 * sizeof(int32_t) || vsize < sizeof(int16_t)) return -EINVAL;
        const int32_t band = param[1];
        if (band < 0 || band >= NUM_BANDS) return -EINVAL;
        c->levels[band] = clampLevel(*(const int16_t *) value);
        c->preset = PRESET_CUSTOM;
        design(c);
        return 0;
    }
    case EQ_PARAM_PROPERTIES: {
        if (vsize < 2 * sizeof(int16_t)) return -EINVAL;
        const int16_t *p = (const int16_t *) value;
        if (p[0] >= 0 && p[0] < NUM_PRESETS) {
            setPreset(c, p[0]);
            return 0;
        }
        if (p[1] != NUM_BANDS || vsize < (2 + NUM_BANDS) * sizeof(int16_t)) return -EINVAL;
        for (int b = 0; b < NUM_BANDS; b++) c->levels[b] = clampLevel(p[2 + b]);
        c->preset = PRESET_CUSTOM;
        design(c);
        return 0;
    }
    default:
        return -EINVAL;
    }
}

/* ---- control interface ----------------------------------------------------------------- */

static int setConfig(eq_context_t *c, const effect_config_t *cfg)
{
    if (cfg->inputCfg.samplingRate != cfg->outputCfg.samplingRate
            || cfg->inputCfg.channels != AUDIO_CHANNEL_OUT_STEREO
            || cfg->outputCfg.channels != AUDIO_CHANNEL_OUT_STEREO
            || cfg->inputCfg.format != AUDIO_FORMAT_PCM_16_BIT
            || cfg->outputCfg.format != AUDIO_FORMAT_PCM_16_BIT) {
        ALOGW("unsupported config: rate %u/%u channels %#x/%#x format %#x/%#x",
                cfg->inputCfg.samplingRate, cfg->outputCfg.samplingRate,
                cfg->inputCfg.channels, cfg->outputCfg.channels,
                cfg->inputCfg.format, cfg->outputCfg.format);
        return -EINVAL;
    }
    c->config = *cfg;
    setRate(c, cfg->inputCfg.samplingRate);
    resetState(c);
    return 0;
}

static int32_t eq_command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
        void *pCmdData, uint32_t *replySize, void *pReplyData)
{
    eq_context_t *c = (eq_context_t *) self;
    if (c == NULL) return -EINVAL;

    switch (cmdCode) {
    case EFFECT_CMD_INIT:
        if (pReplyData == NULL || replySize == NULL || *replySize != sizeof(int)) return -EINVAL;
        resetState(c);
        *(int *) pReplyData = 0;
        break;
    case EFFECT_CMD_SET_CONFIG:
        if (pCmdData == NULL || cmdSize != sizeof(effect_config_t) || pReplyData == NULL
                || replySize == NULL || *replySize != sizeof(int)) return -EINVAL;
        *(int *) pReplyData = setConfig(c, (const effect_config_t *) pCmdData);
        break;
    case EFFECT_CMD_GET_CONFIG:
        if (pReplyData == NULL || replySize == NULL || *replySize != sizeof(effect_config_t))
            return -EINVAL;
        *(effect_config_t *) pReplyData = c->config;
        break;
    case EFFECT_CMD_RESET:
        resetState(c);
        break;
    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
        if (pReplyData == NULL || replySize == NULL || *replySize != sizeof(int)) return -EINVAL;
        c->enabled = cmdCode == EFFECT_CMD_ENABLE;
        if (c->enabled) resetState(c);
        *(int *) pReplyData = 0;
        break;
    case EFFECT_CMD_GET_PARAM: {
        if (pCmdData == NULL || cmdSize < sizeof(effect_param_t) + sizeof(int32_t)
                || pReplyData == NULL || replySize == NULL
                || *replySize < sizeof(effect_param_t) + sizeof(int32_t)) return -EINVAL;
        const effect_param_t *q = (const effect_param_t *) pCmdData;
        if (q->psize > cmdSize - sizeof(effect_param_t)) return -EINVAL;
        const uint32_t voffset = ((q->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);
        if (*replySize < sizeof(effect_param_t) + voffset) return -EINVAL;
        memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + q->psize);
        effect_param_t *p = (effect_param_t *) pReplyData;
        uint32_t room = *replySize - sizeof(effect_param_t) - voffset;
        if (p->vsize > room) p->vsize = room;
        p->status = getParameter(c, (const int32_t *) p->data, p->psize, p->data + voffset,
                &p->vsize);
        if (p->status != 0) p->vsize = 0;
        *replySize = sizeof(effect_param_t) + voffset + p->vsize;
        break;
    }
    case EFFECT_CMD_SET_PARAM: {
        if (pCmdData == NULL || cmdSize < sizeof(effect_param_t) + sizeof(int32_t)
                || pReplyData == NULL || replySize == NULL || *replySize != sizeof(int32_t))
            return -EINVAL;
        const effect_param_t *p = (const effect_param_t *) pCmdData;
        const uint32_t voffset = ((p->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);
        if (sizeof(effect_param_t) + voffset + p->vsize > cmdSize) return -EINVAL;
        *(int32_t *) pReplyData = setParameter(c, (const int32_t *) p->data, p->psize,
                p->data + voffset, p->vsize);
        break;
    }
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
    case EFFECT_CMD_SET_INPUT_DEVICE:
    case EFFECT_CMD_SET_AUDIO_SOURCE:
        /* nothing to do; reply when the caller expects one */
        if (pReplyData != NULL && replySize != NULL && *replySize >= sizeof(int)) {
            *(int *) pReplyData = 0;
        }
        break;
    case EFFECT_CMD_OFFLOAD:
        /* not offloadable: tell the framework so */
        if (pReplyData == NULL || replySize == NULL || *replySize != sizeof(int)) return -EINVAL;
        *(int *) pReplyData = -ENOSYS;
        break;
    default:
        return -EINVAL;
    }
    return 0;
}

static int32_t eq_getDescriptor(effect_handle_t self, effect_descriptor_t *pDescriptor)
{
    if (self == NULL || pDescriptor == NULL) return -EINVAL;
    *pDescriptor = kDescriptor;
    return 0;
}

static const struct effect_interface_s gInterface = {
    eq_process,
    eq_command,
    eq_getDescriptor,
    NULL,
};

/* ---- library --------------------------------------------------------------------------- */

static int uuidEqual(const effect_uuid_t *a, const effect_uuid_t *b)
{
    return memcmp(a, b, sizeof(effect_uuid_t)) == 0;
}

static int32_t lib_create(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId,
        effect_handle_t *pHandle)
{
    (void) sessionId;
    (void) ioId;
    if (uuid == NULL || pHandle == NULL || !uuidEqual(uuid, &kDescriptor.uuid)) return -EINVAL;
    eq_context_t *c = (eq_context_t *) calloc(1, sizeof(eq_context_t));
    if (c == NULL) return -ENOMEM;
    c->itfe = &gInterface;
    c->preset = 3;      /* Flat */
    c->config.inputCfg.samplingRate = 48000;
    c->config.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    c->config.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    c->config.outputCfg = c->config.inputCfg;
    c->config.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    setRate(c, 48000);
    resetState(c);
    *pHandle = (effect_handle_t) c;
    return 0;
}

static int32_t lib_release(effect_handle_t handle)
{
    if (handle == NULL) return -EINVAL;
    free(handle);
    return 0;
}

static int32_t lib_getDescriptor(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor)
{
    if (uuid == NULL || pDescriptor == NULL || !uuidEqual(uuid, &kDescriptor.uuid))
        return -EINVAL;
    *pDescriptor = kDescriptor;
    return 0;
}

__attribute__ ((visibility ("default")))
audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "KiroOS Equalizer Library",
    .implementor = "KiroOS",
    .create_effect = lib_create,
    .release_effect = lib_release,
    .get_descriptor = lib_getDescriptor,
};
