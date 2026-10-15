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

static void design(eq_context_t *c)
{
    float maxBoost = 0;
    for (int b = 0; b < NUM_BANDS; b++) {
        const float db = c->levels[b] / 100.0f;
        c->active[b] = c->levels[b] != 0 && kCenterHz[b] < c->rate * 0.45f;
        if (!c->active[b]) {
            continue;
        }
        if (db > maxBoost) maxBoost = db;
        /* RBJ cookbook peaking EQ */
        const float A = powf(10.0f, db / 40.0f);
        const float w0 = 2.0f * (float) M_PI * kCenterHz[b] / c->rate;
        const float alpha = sinf(w0) / (2.0f * BAND_Q);
        const float cw = cosf(w0);
        const float a0 = 1.0f + alpha / A;
        c->bq[b].b0 = (1.0f + alpha * A) / a0;
        c->bq[b].b1 = (-2.0f * cw) / a0;
        c->bq[b].b2 = (1.0f - alpha * A) / a0;
        c->bq[b].a1 = (-2.0f * cw) / a0;
        c->bq[b].a2 = (1.0f - alpha / A) / a0;
    }
    /* half of the biggest boost back as headroom, the limiter takes care of the rest */
    c->pregain = powf(10.0f, -maxBoost * 0.5f / 20.0f);
}

static void resetState(eq_context_t *c)
{
    memset(c->z, 0, sizeof(c->z));
    c->gain = 1.0f;
}

static void setRate(eq_context_t *c, uint32_t rate)
{
    c->rate = rate > 0 ? (float) rate : 48000.0f;
    /* limiter release: back to full gain in about 120 ms */
    c->release = 1.0f - expf(-1.0f / (0.120f * c->rate));
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
        for (int ch = 0; ch < 2; ch++) {
            /* the tiny offset keeps the filter states out of denormals in silence (slow on
             * ARM VFP); it is far below what 16 bits can show */
            float v = x[ch] * c->pregain + 1e-20f;
            for (int b = 0; b < NUM_BANDS; b++) {
                if (!c->active[b]) continue;
                const biquad_t *q = &c->bq[b];
                float *z = c->z[b][ch];
                /* transposed direct form II */
                const float y = q->b0 * v + z[0];
                z[0] = q->b1 * v - q->a1 * y + z[1];
                z[1] = q->b2 * v - q->a2 * y;
                v = y;
            }
            x[ch] = v;
        }
        /* peak limiter on both channels together (keeps the stereo image) */
        const float peak = fmaxf(fabsf(x[0]), fabsf(x[1]));
        const float want = peak > LIMIT ? LIMIT / peak : 1.0f;
        if (want < c->gain) {
            c->gain = want;                             /* instant attack */
        } else {
            c->gain += (want - c->gain) * c->release;   /* smooth release */
        }
        for (int ch = 0; ch < 2; ch++) {
            float s = x[ch] * c->gain * 32768.0f;
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
