#ifndef Header_SuperpoweredExample
#define Header_SuperpoweredExample

#include <math.h>
#include <pthread.h>
#include <Superpowered3BandEQ.h>
#include <SuperpoweredReverb.h>
#include <SuperpoweredEcho.h>
#include <SuperpoweredMixer.h>

#include "SuperpoweredPlayer.h"
#include "SuperpoweredAdvancedAudioPlayer.h"
#include "AndroidIO/SuperpoweredAndroidAudioIO.h"


class SuperpoweredPlayer {

public:

    SuperpoweredPlayer(unsigned int samplerate, unsigned int buffersize);

    ~SuperpoweredPlayer();

    bool process(short int *output, unsigned int numberOfSamples);
    void togglePlayback();
    void setVolume(float value);
    void setSeek(int value);
    unsigned int getDuration();
    unsigned int getPosition();
    float getVolume();
    float getPositionPercent();
    bool isPlaying();
    bool isLooping();
    bool isEnableEQ();
    bool isEnableReverb();
    void setLooping(bool looping);
    void open(const char *path);
    void setEQBands(int index, int value);
    void enableEQ(bool enable);
    void echoValue(int value);
    void reverbValue(int mix, int width, int damp, int roomSize);
    void enableReverb(bool enable);
    void onForeground();
    void onBackground();


private:
    SuperpoweredAndroidAudioIO *audioSystem;
    SuperpoweredAdvancedAudioPlayer *player;
    Superpowered3BandEQ *bandEQ;
    SuperpoweredStereoMixer *mixer;
    SuperpoweredReverb *reverb;
    SuperpoweredEcho *echo;

    float *buffer;
    float volume;

    float left;
    float right;
};

#endif
