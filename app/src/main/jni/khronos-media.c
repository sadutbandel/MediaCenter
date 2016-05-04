#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <assert.h>
#include <SLES/OpenSLES.h>
#include<android/log.h>

#define MAX_NUMBER_INTERFACES 3

// engine interfaces
SLObjectItf engineObject = NULL;
SLEngineItf engineEngine;

// output mix interfaces
SLObjectItf outputMixObject = NULL;
SLEqualizerItf eqOutputItf = NULL;

// URI player interfaces
SLObjectItf uriPlayerObject = NULL;
SLPlayItf uriPlayerPlay;
SLSeekItf uriPlayerSeek;
SLMuteSoloItf uriPlayerMuteSolo;
SLVolumeItf uriPlayerVolume;
SLBassBoostItf uriBassBoost;
SLVirtualizerItf uriVirtualizer;

JavaVM *gJavaVM;
jobject gCallbackObject = NULL;

#define SL_PREFETCHSTATUS_UNKNOWN 0
#define SL_PREFETCHSTATUS_ERROR   ((SLuint32) -1)

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
SLuint32 prefetch_status = SL_PREFETCHSTATUS_UNKNOWN;

#define PREFETCHEVENT_ERROR_CANDIDATE \
        (SL_PREFETCHEVENT_STATUSCHANGE | SL_PREFETCHEVENT_FILLLEVELCHANGE)


JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native", "JNI_OnLoad");
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_registerCallback(JNIEnv *env, jobject instance) {
    gCallbackObject = (*env)->NewGlobalRef(env, instance);
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_unregisterCallback(JNIEnv *env,
                                                                     jobject instance) {
    (*env)->DeleteGlobalRef(env, gCallbackObject);
    gCallbackObject = NULL;
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_createEngine(JNIEnv *env, jobject instance) {

    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);

    SLboolean required[MAX_NUMBER_INTERFACES];
    SLInterfaceID iidArray[MAX_NUMBER_INTERFACES];

    int i;
    for (i = 0; i < MAX_NUMBER_INTERFACES; i++) {
        required[i] = SL_BOOLEAN_FALSE;
        iidArray[i] = SL_IID_NULL;
    }

    // get the engine interface, which is needed in order to create other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);

    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, iidArray,
                                              required);

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);

}


void handlingCallback(int event) {
    JNIEnv *env;
    int isAttached = 0;

    if (!gCallbackObject) return;

    if (((*gJavaVM)->GetEnv(gJavaVM, (void **) &env, JNI_VERSION_1_6)) < 0) {
        if (((*gJavaVM)->AttachCurrentThread(gJavaVM, &env, NULL)) < 0) {
            return;
        }
        isAttached = 1;
    }

    jclass cls = (*env)->GetObjectClass(env, gCallbackObject);
    if (!cls) {
        if (isAttached) (*gJavaVM)->DetachCurrentThread(gJavaVM);
        return;
    }


    jmethodID method = (*env)->GetMethodID(env, cls, "playStatusCallback", "(I)V");
    if (!method) {
        if (isAttached) (*gJavaVM)->DetachCurrentThread(gJavaVM);
        return;
    }

    (*env)->CallVoidMethod(env, gCallbackObject, method, event);

    if (isAttached) (*gJavaVM)->DetachCurrentThread(gJavaVM);
}


void playStatusCallback(SLPlayItf play, void *context, SLuint32 event) {
//    __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native", "The value is %d", event);
    handlingCallback(event);
}


void prefetch_callback(SLPrefetchStatusItf caller, void *context __unused, SLuint32 event) {
    __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native", "prefetch_callback");
    SLresult result;
    assert(context == NULL);
    SLpermille level;
    result = (*caller)->GetFillLevel(caller, &level);
    assert(SL_RESULT_SUCCESS == result);
    SLuint32 status;
    result = (*caller)->GetPrefetchStatus(caller, &status);
    assert(SL_RESULT_SUCCESS == result);
    SLuint32 new_prefetch_status;
    if ((event & PREFETCHEVENT_ERROR_CANDIDATE) == PREFETCHEVENT_ERROR_CANDIDATE
        && level == 0 && status == SL_PREFETCHSTATUS_UNDERFLOW) {
        new_prefetch_status = SL_PREFETCHSTATUS_ERROR;
    } else if (event == SL_PREFETCHEVENT_STATUSCHANGE &&
               status == SL_PREFETCHSTATUS_SUFFICIENTDATA) {
        new_prefetch_status = status;
    } else {
        return;
    }
    __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native", "prefetch_callback status");
    int ok;
    ok = pthread_mutex_lock(&mutex);
    assert(ok == 0);
    prefetch_status = new_prefetch_status;
    ok = pthread_cond_signal(&cond);
    assert(ok == 0);
    ok = pthread_mutex_unlock(&mutex);
    assert(ok == 0);
}

JNIEXPORT jboolean JNICALL
Java_com_fesskiev_player_services_PlaybackService_createUriAudioPlayer(JNIEnv *env,
                                                                       jobject instance,
                                                                       jstring uri) {
    SLresult result;

    /* Source of audio data to play */
    SLDataSource audioSource;
    SLDataLocator_URI locatorUri;
    SLDataFormat_MIME mime;

    /* Data sinks for the audio player */
    SLDataSink audioSink;
    SLDataLocator_OutputMix locator_outputmix;

    SLPrefetchStatusItf prefetchItf = NULL;
    SLMetadataExtractionItf metadataItf = NULL;

    // convert Java string to UTF-8
    const char *utf8 = (*env)->GetStringUTFChars(env, uri, NULL);

    /* Setup the data sink structure */
    locator_outputmix.locatorType = SL_DATALOCATOR_OUTPUTMIX;
    locator_outputmix.outputMix = outputMixObject;
    audioSink.pLocator = (void *) &locator_outputmix;
    audioSink.pFormat = NULL;

    locatorUri.locatorType = SL_DATALOCATOR_URI;
    locatorUri.URI = (SLchar *) utf8;
    mime.formatType = SL_DATAFORMAT_MIME;
    mime.mimeType = (SLchar *) NULL;
    mime.containerType = SL_CONTAINERTYPE_UNSPECIFIED;

    audioSource.pFormat = (void *) &mime;
    audioSource.pLocator = (void *) &locatorUri;

    const SLInterfaceID ids[7] = {SL_IID_SEEK,
                                  SL_IID_MUTESOLO,
                                  SL_IID_VOLUME,
                                  SL_IID_BASSBOOST,
                                  SL_IID_VIRTUALIZER,
                                  SL_IID_PREFETCHSTATUS,
                                  SL_IID_METADATAEXTRACTION};

    const SLboolean req[7] = {SL_BOOLEAN_TRUE,
                              SL_BOOLEAN_TRUE,
                              SL_BOOLEAN_TRUE,
                              SL_BOOLEAN_TRUE,
                              SL_BOOLEAN_TRUE,
                              SL_BOOLEAN_TRUE,
                              SL_BOOLEAN_TRUE};


    /* Create the audio player */
    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &uriPlayerObject, &audioSource,
                                                &audioSink, 7,
                                                ids, req);

    (*env)->ReleaseStringUTFChars(env, uri, utf8);

    // realize the player
    result = (*uriPlayerObject)->Realize(uriPlayerObject, SL_BOOLEAN_FALSE);
    // this will always succeed on Android, but we check result for portability to other platforms
    if (SL_RESULT_SUCCESS != result) {
        (*uriPlayerObject)->Destroy(uriPlayerObject);
        uriPlayerObject = NULL;
        return JNI_FALSE;
    }


    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_PLAY, &uriPlayerPlay);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_SEEK, &uriPlayerSeek);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_MUTESOLO, &uriPlayerMuteSolo);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_VOLUME, &uriPlayerVolume);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_BASSBOOST, &uriBassBoost);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_VIRTUALIZER, &uriVirtualizer);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_PREFETCHSTATUS, &prefetchItf);
    result = (*uriPlayerObject)->GetInterface(uriPlayerObject, SL_IID_METADATAEXTRACTION,
                                              &metadataItf);

    result = (*prefetchItf)->RegisterCallback(prefetchItf, prefetch_callback, NULL);
    result = (*prefetchItf)->SetCallbackEventsMask(prefetchItf,
                                                   SL_PREFETCHEVENT_STATUSCHANGE |
                                                   SL_PREFETCHEVENT_FILLLEVELCHANGE);

    result = (*uriPlayerPlay)->SetPlayState(uriPlayerPlay, SL_PLAYSTATE_PAUSED);

    pthread_mutex_lock(&mutex);
    while (prefetch_status == SL_PREFETCHSTATUS_UNKNOWN) {
        pthread_cond_wait(&cond, &mutex);
        __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native", "SL_PREFETCHSTATUS_UNKNOWN");
    }

    pthread_mutex_unlock(&mutex);
    if (prefetch_status == SL_PREFETCHSTATUS_ERROR) {
        if (outputMixObject != NULL) {
            (*outputMixObject)->Destroy(outputMixObject);
            outputMixObject = NULL;
            eqOutputItf = NULL;
        }
        if (engineObject != NULL) {
            (*engineObject)->Destroy(engineObject);
            engineObject = NULL;
            engineEngine = NULL;
        }
    }


    result = (*uriPlayerPlay)->SetMarkerPosition(uriPlayerPlay, 2000);
    result = (*uriPlayerPlay)->SetPositionUpdatePeriod(uriPlayerPlay, 500);


    result = (*uriPlayerPlay)->RegisterCallback(uriPlayerPlay,
                                                playStatusCallback, 0);
    result = (*uriPlayerPlay)->SetCallbackEventsMask(uriPlayerPlay, SL_PLAYEVENT_HEADATMARKER |
                                                                    SL_PLAYEVENT_HEADATNEWPOS |
                                                                    SL_PLAYEVENT_HEADATEND);

    SLuint32 itemCount;
    result = (*metadataItf)->GetItemCount(metadataItf, &itemCount);
    __android_log_print(ANDROID_LOG_VERBOSE,
                        "OpenSl native",
                        "item count: " + *&itemCount);
    SLuint32 i, keySize, valueSize;
    SLMetadataInfo *keyInfo, *value;
    for (i = 0; i < itemCount; i++) {
        keyInfo = NULL;
        keySize = 0;
        value = NULL;
        valueSize = 0;
        result = (*metadataItf)->GetKeySize(metadataItf, i, &keySize);
        result = (*metadataItf)->GetValueSize(metadataItf, i, &valueSize);
        keyInfo = (SLMetadataInfo *) malloc(keySize);
        if (NULL != keyInfo) {
            result = (*metadataItf)->GetKey(metadataItf, i, keySize, keyInfo);

            __android_log_print(ANDROID_LOG_VERBOSE,
                                "OpenSl native",
                                "key[%d] size=%d, name=%s \tvalue size=%d \n", i, keyInfo->size,
                                keyInfo->data, valueSize);
            free(keyInfo);
        }
    }


    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setPlayingUriAudioPlayer(JNIEnv *env,
                                                                           jobject instance,
                                                                           jboolean isPlaying) {
    if (NULL != uriPlayerPlay) {
        SLresult result = (*uriPlayerPlay)->SetPlayState(uriPlayerPlay, isPlaying ?
                                                                        SL_PLAYSTATE_PLAYING
                                                                                  : SL_PLAYSTATE_PAUSED);
    }
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setVolumeUriAudioPlayer(JNIEnv *env,
                                                                          jobject instance,
                                                                          jint milliBel) {
    if (NULL != uriPlayerVolume) {
        SLresult result = (*uriPlayerVolume)->SetVolumeLevel(uriPlayerVolume, milliBel);
    }
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setSeekUriAudioPlayer(JNIEnv *env,
                                                                        jobject instance,
                                                                        jlong milliseconds) {
    if (NULL != uriPlayerSeek) {
        SLresult result = (*uriPlayerSeek)->SetPosition(uriPlayerSeek, milliseconds,
                                                        SL_SEEKMODE_FAST);
    }
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_releaseUriAudioPlayer(JNIEnv *env,
                                                                        jobject instance) {
    if (uriPlayerObject != NULL) {
        (*uriPlayerObject)->Destroy(uriPlayerObject);
        uriPlayerObject = NULL;
        uriPlayerPlay = NULL;
        uriPlayerSeek = NULL;
        uriPlayerMuteSolo = NULL;
        uriPlayerVolume = NULL;
    }

}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_releaseEngine(JNIEnv *env, jobject instance) {

    // destroy output mix object, and invalidate all associated interfaces
    if (outputMixObject != NULL) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = NULL;
        eqOutputItf = NULL;
    }

    // destroy engine object, and invalidate all associated interfaces
    if (engineObject != NULL) {
        (*engineObject)->Destroy(engineObject);
        engineObject = NULL;
        engineEngine = NULL;
    }

}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getDuration(JNIEnv *env, jobject instance) {

    if (NULL != uriPlayerPlay) {

        SLmillisecond msec;
        SLresult result = (*uriPlayerPlay)->GetDuration(uriPlayerPlay, &msec);
        return msec;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getPosition(JNIEnv *env, jobject instance) {

    if (NULL != uriPlayerPlay) {

        SLmillisecond msec;
        SLresult result = (*uriPlayerPlay)->GetPosition(uriPlayerPlay, &msec);
        return msec;
    }

    return 0;
}

SLuint32 getPlayState() {

    if (NULL != uriPlayerPlay) {

        SLuint32 state;
        SLresult result = (*uriPlayerPlay)->GetPlayState(uriPlayerPlay, &state);
        (void) result;

        return state;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_fesskiev_player_services_PlaybackService_isPlaying(JNIEnv *env, jobject instance) {

    return getPlayState() == SL_PLAYSTATE_PLAYING;
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setEnableEQ(JNIEnv *env, jobject instance,
                                                              jboolean isEnable) {
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->SetEnabled(eqOutputItf, isEnable ?
                                                                  SL_BOOLEAN_TRUE
                                                                           : SL_BOOLEAN_FALSE);
    }
}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_usePreset(JNIEnv *env, jobject instance,
                                                            jint presetValue) {

    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->UsePreset(eqOutputItf, (SLuint16) presetValue);
    }
}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getNumberOfBands(JNIEnv *env, jobject instance) {

    SLuint16 numberBands = 0;

    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->GetNumberOfBands(eqOutputItf, &numberBands);
    }
    return numberBands;
}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getNumberOfPresets(JNIEnv *env,
                                                                     jobject instance) {

    SLuint16 numberPresets = 0;
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->GetNumberOfPresets(eqOutputItf, &numberPresets);
        assert(SL_RESULT_SUCCESS == result);
    }
    return numberPresets;

}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getCurrentPreset(JNIEnv *env, jobject instance) {

    SLuint16 preset = 0;
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->GetCurrentPreset(eqOutputItf, &preset);
        assert(SL_RESULT_SUCCESS == result);
    }
    return preset;

}

JNIEXPORT jintArray JNICALL
Java_com_fesskiev_player_services_PlaybackService_getBandLevelRange(JNIEnv *env, jobject instance) {

    int size = 2;
    jintArray result;
    result = (*env)->NewIntArray(env, size);
    if (result == NULL) {
        return NULL;
    }

    SLmillibel minLevel, maxLevel = 0;
    jint fill[size];
    if (NULL != eqOutputItf) {
        (*eqOutputItf)->GetBandLevelRange(eqOutputItf, &minLevel, &maxLevel);

        fill[0] = minLevel;
        fill[1] = maxLevel;

        __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native",
                            "Band level range = %dmB to %dmB\n", minLevel, maxLevel);
    }

    (*env)->SetIntArrayRegion(env, result, 0, size, fill);
    return result;

}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setBandLevel(JNIEnv *env, jobject instance,
                                                               jint bandNumber, jint milliBel) {
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->SetBandLevel(eqOutputItf, (SLuint16) bandNumber,
                                                       (SLmillibel) milliBel);
    }
}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getBandLevel(JNIEnv *env, jobject instance,
                                                               jint bandNumber) {
    SLmillibel level = NULL;
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->GetBandLevel(eqOutputItf, (SLuint16) bandNumber, &level);
        assert(SL_RESULT_SUCCESS == result);
    }
    return level;

}

JNIEXPORT jintArray JNICALL
Java_com_fesskiev_player_services_PlaybackService_getBandFrequencyRange(JNIEnv *env,
                                                                        jobject instance,
                                                                        jint bandNumber) {
    int size = 2;
    jintArray result;
    result = (*env)->NewIntArray(env, size);
    if (result == NULL) {
        return NULL;
    }

    SLmilliHertz minFreg;
    SLmilliHertz maxFreg;
    jint fill[size];

    if (NULL != eqOutputItf) {
        (*eqOutputItf)->GetBandFreqRange(eqOutputItf,
                                         (SLuint16) bandNumber, &minFreg, &maxFreg);

        __android_log_print(ANDROID_LOG_VERBOSE, "OpenSl native",
                            "Band %d freg = %dHz to %dHz\n", bandNumber, minFreg / 1000,
                            maxFreg / 1000);

        fill[0] = minFreg;
        fill[1] = maxFreg;
    }

    (*env)->SetIntArrayRegion(env, result, 0, size, fill);
    return result;

}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getCenterFrequency(JNIEnv *env, jobject instance,
                                                                     jint bandNumber) {
    SLmilliHertz centerFreq;
    if (NULL != eqOutputItf) {
        SLresult result =
                (*eqOutputItf)->GetCenterFreq(eqOutputItf, (SLuint16) bandNumber, &centerFreq);
        assert(SL_RESULT_SUCCESS == result);
    }
    return centerFreq;

}

JNIEXPORT jint JNICALL
Java_com_fesskiev_player_services_PlaybackService_getNumberOfPreset(JNIEnv *env, jobject instance) {

    SLuint16 numberPreset = 0;
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->GetNumberOfPresets(eqOutputItf, &numberPreset);
    }
    return numberPreset;

}

JNIEXPORT jstring JNICALL
Java_com_fesskiev_player_services_PlaybackService_getPresetName(JNIEnv *env, jobject instance,
                                                                jint presetNumber) {
    const SLchar *namePreset = NULL;
    if (NULL != eqOutputItf) {
        SLresult result = (*eqOutputItf)->GetPresetName(eqOutputItf, (SLuint16) presetNumber,
                                                        &namePreset);
    }
    return (*env)->NewStringUTF(env, namePreset);

}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setEnableBassBoost(JNIEnv *env, jobject instance,
                                                                     jboolean isEnable) {
    if (NULL != uriBassBoost) {

        SLboolean enable = isEnable ?
                           SL_BOOLEAN_TRUE : SL_BOOLEAN_FALSE;
        SLresult result = (*uriBassBoost)->SetEnabled(uriBassBoost, enable);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_fesskiev_player_services_PlaybackService_isSupportedBassBoost(JNIEnv *env,
                                                                       jobject instance) {
    if (NULL != uriBassBoost) {
        SLboolean strengthSupported = SL_BOOLEAN_FALSE;
        SLresult result = (*uriBassBoost)->IsStrengthSupported(uriBassBoost, &strengthSupported);
        return (jboolean) strengthSupported;
    }
    return JNI_FALSE;
}


JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setBassBoostValue(JNIEnv *env, jobject instance,
                                                                    jint value) {
    if (NULL != uriBassBoost) {
        SLresult result = (*uriBassBoost)->SetStrength(uriBassBoost, (SLuint16) value);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_fesskiev_player_services_PlaybackService_isEnabledBassBoost(JNIEnv *env,
                                                                     jobject instance) {
    if (NULL != uriBassBoost) {
        SLboolean strengthSupported = SL_BOOLEAN_FALSE;
        SLresult result = (*uriBassBoost)->IsEnabled(uriBassBoost, &strengthSupported);
        return (jboolean) strengthSupported;
    }

    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fesskiev_player_services_PlaybackService_isSupportedVirtualizer(JNIEnv *env,
                                                                         jobject instance) {
    if (NULL != uriVirtualizer) {
        SLboolean strengthSupported = SL_BOOLEAN_FALSE;
        SLresult result = (*uriVirtualizer)->IsStrengthSupported(uriVirtualizer,
                                                                 &strengthSupported);
        return (jboolean) strengthSupported;
    }
    return JNI_FALSE;

}

JNIEXPORT jboolean JNICALL
Java_com_fesskiev_player_services_PlaybackService_isEnabledVirtualizer(JNIEnv *env,
                                                                       jobject instance) {

    if (NULL != uriVirtualizer) {
        SLboolean strengthSupported = SL_BOOLEAN_FALSE;
        SLresult result = (*uriVirtualizer)->IsEnabled(uriVirtualizer, &strengthSupported);
        return (jboolean) strengthSupported;
    }

    return JNI_FALSE;

}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setEnableVirtualizer(JNIEnv *env,
                                                                       jobject instance,
                                                                       jboolean isEnable) {
    if (NULL != uriVirtualizer) {

        SLboolean enable = isEnable ?
                           SL_BOOLEAN_TRUE : SL_BOOLEAN_FALSE;
        SLresult result = (*uriVirtualizer)->SetEnabled(uriVirtualizer, enable);
    }

}

JNIEXPORT void JNICALL
Java_com_fesskiev_player_services_PlaybackService_setBassVirtualizerValue(JNIEnv *env,
                                                                          jobject instance,
                                                                          jint value) {
    if (NULL != uriVirtualizer) {
        SLresult result = (*uriVirtualizer)->SetStrength(uriVirtualizer, (SLuint16) value);
    }
}