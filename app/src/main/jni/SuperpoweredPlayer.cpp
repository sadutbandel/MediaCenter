#include "SuperpoweredPlayer.h"
#include "Superpowered/SuperpoweredSimple.h"
#include <jni.h>
#include <stdio.h>
#include <android/log.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>

static SuperpoweredPlayer *player = NULL;

static void playerEventCallback(void *clientData, SuperpoweredAdvancedAudioPlayerEvent event,
                                void *__unused value) {
    if (event == SuperpoweredAdvancedAudioPlayerEvent_LoadSuccess) {
        SuperpoweredAdvancedAudioPlayer *player = *((SuperpoweredAdvancedAudioPlayer **) clientData);
        player->setBpm(126.0f);
        player->setFirstBeatMs(353);
        player->setPosition(player->firstBeatMs, false, false);
    };
}

static bool audioProcessing(void *clientdata, short int *audioIO, int numberOfSamples,
                            int __unused samplerate) {
    return ((SuperpoweredPlayer *) clientdata)->process(audioIO, (unsigned int) numberOfSamples);
}

SuperpoweredPlayer::SuperpoweredPlayer(unsigned int samplerate, unsigned int buffersize,
                                       const char *path) : volume(1.0f) {

    buffer = (float *) memalign(16, (buffersize + 16) * sizeof(float) * 2);

    player = new SuperpoweredAdvancedAudioPlayer(&player, playerEventCallback, samplerate, 0);
    player->open(path);

    player->syncMode = SuperpoweredAdvancedAudioPlayerSyncMode_TempoAndBeat;

    audioSystem = new SuperpoweredAndroidAudioIO(samplerate, buffersize, false, true,
                                                 audioProcessing, this, -1, SL_ANDROID_STREAM_MEDIA,
                                                 buffersize * 2);
}

bool SuperpoweredPlayer::process(short int *output, unsigned int numberOfSamples) {

    if (player->process(buffer, false, numberOfSamples)) {
        SuperpoweredFloatToShortInt(buffer, output, numberOfSamples);
        return true;
    } else return false;
}

void SuperpoweredPlayer::setPlaying(bool isPlaying) {
    if (isPlaying) {
        player->play(false);
    } else {
        player->pause();
    }
}


SuperpoweredPlayer::~SuperpoweredPlayer() {
    delete audioSystem;
    delete player;
    free(buffer);
}

void SuperpoweredPlayer::setVolume(float value) {
    volume = value;
}

void SuperpoweredPlayer::setSeek(int value) {
    player->seek(value);
}

int SuperpoweredPlayer::getDuration() {
    return player->durationMs;
}

int SuperpoweredPlayer::getPosition() {
    return (int) player->positionMs;
}

bool SuperpoweredPlayer::isPlaying() {
    return player->playing;
}

void SuperpoweredPlayer::setLooping(bool looping) {

}


extern "C" JNIEXPORT void
Java_com_fesskiev_player_services_PlaybackService_setLoopingAudioPlayer(JNIEnv *env, jclass type,
                                                                        jboolean isLooping) {
    player->setLooping(isLooping);

}

extern "C" JNIEXPORT jboolean
Java_com_fesskiev_player_services_PlaybackService_isPlaying(JNIEnv *env, jobject instance) {
    return player->isPlaying();
}

extern "C" JNIEXPORT jint
Java_com_fesskiev_player_services_PlaybackService_getPosition(JNIEnv *env, jobject instance) {

    return player->getPosition();

}

extern "C" JNIEXPORT jint
Java_com_fesskiev_player_services_PlaybackService_getDuration(JNIEnv *env, jobject instance) {

    return player->getDuration();

}

extern "C" JNIEXPORT void
Java_com_fesskiev_player_services_PlaybackService_createAudioPlayer(JNIEnv *env, jobject instance,
                                                                    jstring path_, jint sampleRate,
                                                                    jint bufferSize) {
    const char *path = env->GetStringUTFChars(path_, 0);

    player = new SuperpoweredPlayer((unsigned int) sampleRate, (unsigned int) bufferSize, path);

    env->ReleaseStringUTFChars(path_, path);
}


extern "C" JNIEXPORT void
Java_com_fesskiev_player_services_PlaybackService_setPlayingAudioPlayer(JNIEnv *env,
                                                                        jobject instance,
                                                                        jboolean isPlaying) {
    player->setPlaying(isPlaying);
}

extern "C" JNIEXPORT void
Java_com_fesskiev_player_services_PlaybackService_setVolumeAudioPlayer(JNIEnv *env,
                                                                       jobject instance,
                                                                       jint value) {
    player->setVolume(value);

}

extern "C" JNIEXPORT void
Java_com_fesskiev_player_services_PlaybackService_setSeekAudioPlayer(JNIEnv *env, jobject instance,
                                                                     jint value) {
    player->setSeek(value);
}