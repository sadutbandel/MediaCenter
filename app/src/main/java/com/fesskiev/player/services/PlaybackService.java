package com.fesskiev.player.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.IBinder;
import android.support.annotation.Keep;
import android.support.annotation.Nullable;
import android.util.Log;

import com.fesskiev.player.MediaApplication;
import com.fesskiev.player.data.model.PlaybackState;
import com.fesskiev.player.utils.AudioFocusManager;
import com.fesskiev.player.utils.AudioNotificationManager;

import org.greenrobot.eventbus.EventBus;

import java.util.Timer;
import java.util.TimerTask;

public class PlaybackService extends Service {

    private static final String TAG = PlaybackService.class.getSimpleName();

    private static final int END_TRACK = 1;

    public static final String ACTION_OPEN_FILE =
            "com.fesskiev.player.action.ACTION_OPEN_FILE";
    public static final String ACTION_START_PLAYBACK =
            "com.fesskiev.player.action.ACTION_START_PLAYBACK";
    public static final String ACTION_STOP_PLAYBACK =
            "com.fesskiev.player.action.ACTION_STOP_PLAYBACK";
    public static final String ACTION_PLAYBACK_SEEK =
            "com.fesskiev.player.action.ACTION_PLAYBACK_SEEK";
    public static final String ACTION_PLAYBACK_VOLUME =
            "com.fesskiev.player.action.ACTION_PLAYBACK_VOLUME";
    public static final String ACTION_PLAYBACK_EQ_STATE =
            "com.fesskiev.player.action.ACTION_PLAYBACK_EQ_STATE";
    public static final String ACTION_PLAYBACK_MUTE_SOLO_STATE =
            "com.fesskiev.player.action.ACTION_PLAYBACK_MUTE_SOLO_STATE";
    public static final String ACTION_PLAYBACK_REPEAT_STATE =
            "com.fesskiev.player.action.ACTION_PLAYBACK_REPEAT_STATE";


    public static final String PLAYBACK_EXTRA_MUSIC_FILE_PATH
            = "com.fesskiev.player.extra.PLAYBACK_EXTRA_MUSIC_FILE_PATH";
    public static final String PLAYBACK_EXTRA_SEEK
            = "com.fesskiev.player.extra.SEEK";
    public static final String PLAYBACK_EXTRA_VOLUME
            = "com.fesskiev.player.extra.PLAYBACK_EXTRA_VOLUME";
    public static final String PLAYBACK_EXTRA_EQ_ENABLE
            = "com.fesskiev.player.extra.PLAYBACK_EXTRA_EQ_STATE";
    public static final String PLAYBACK_EXTRA_MUTE_SOLO_STATE
            = "com.fesskiev.player.extra.PLAYBACK_EXTRA_MUTE_SOLO_STATE";
    public static final String PLAYBACK_EXTRA_REPEAT_STATE
            = "com.fesskiev.player.extra.PLAYBACK_EXTRA_REPEAT_STATE";

    private Timer timer;
    private AudioFocusManager audioFocusManager;
    private AudioNotificationManager audioNotificationManager;
    private static PlaybackState playbackState;

    public static void createPlaybackState() {
        playbackState = new PlaybackState();
    }

    public static void startPlaybackService(Context context) {
        Intent intent = new Intent(context, PlaybackService.class);
        context.startService(intent);
    }

    public static void changeEQEnable(Context context, boolean enable) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_PLAYBACK_EQ_STATE);
        intent.putExtra(PLAYBACK_EXTRA_EQ_ENABLE, enable);
        context.startService(intent);
    }

    public static void changeRepeatState(Context context, boolean state) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_PLAYBACK_REPEAT_STATE);
        intent.putExtra(PLAYBACK_EXTRA_REPEAT_STATE, state);
        context.startService(intent);
    }

    public static void changeMuteSoloState(Context context, boolean state) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_PLAYBACK_MUTE_SOLO_STATE);
        intent.putExtra(PLAYBACK_EXTRA_MUTE_SOLO_STATE, state);
        context.startService(intent);
    }


    public static void openFile(Context context, String path) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_OPEN_FILE);
        intent.putExtra(PLAYBACK_EXTRA_MUSIC_FILE_PATH, path);
        context.startService(intent);
    }

    public static void startPlayback(Context context) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_START_PLAYBACK);
        context.startService(intent);
    }

    public static void stopPlayback(Context context) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_STOP_PLAYBACK);
        context.startService(intent);
    }

    public static void seekPlayback(Context context, int seek) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_PLAYBACK_SEEK);
        intent.putExtra(PLAYBACK_EXTRA_SEEK, seek);
        context.startService(intent);
    }

    public static void volumePlayback(Context context, int volume) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_PLAYBACK_VOLUME);
        intent.putExtra(PLAYBACK_EXTRA_VOLUME, volume);
        context.startService(intent);
    }

    public static void destroyPlayer(Context context) {
        context.stopService(new Intent(context, PlaybackService.class));
    }


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Create playback service!");

        audioNotificationManager = new AudioNotificationManager(this, this);
        audioFocusManager = new AudioFocusManager();
        audioFocusManager.setOnAudioFocusManagerListener(
                state -> {
                    switch (state) {
                        case AudioFocusManager.AUDIO_FOCUSED:
                            Log.d(TAG, "onFocusChanged: FOCUSED");
                            play();
                            setVolumeAudioPlayer(playbackState.getVolume());
                            break;
                        case AudioFocusManager.AUDIO_NO_FOCUS_CAN_DUCK:
                            Log.d(TAG, "onFocusChanged: NO_FOCUS_CAN_DUCK");
                            setVolumeAudioPlayer(50);
                            break;
                        case AudioFocusManager.AUDIO_NO_FOCUS_NO_DUCK:
                            Log.d(TAG, "onFocusChanged: NO_FOCUS_NO_DUCK");
                            stop();
                            break;
                    }
                });

        registerHeadsetReceiver();

        createPlayer();
    }

    private void next() {
        Log.w(TAG, "NEXT FROM SERVICE");
        MediaApplication.getInstance().getAudioPlayer().next();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                Log.d(TAG, "playback service handle intent: " + action);
                switch (action) {
                    case ACTION_OPEN_FILE:
                        String openPath = intent.getStringExtra(PLAYBACK_EXTRA_MUSIC_FILE_PATH);
                        openFile(openPath);
                        break;
                    case ACTION_START_PLAYBACK:
                        play();
                        break;
                    case ACTION_STOP_PLAYBACK:
                        stop();
                        break;
                    case ACTION_PLAYBACK_SEEK:
                        int seekValue = intent.getIntExtra(PLAYBACK_EXTRA_SEEK, -1);
                        seek(seekValue);
                        break;
                    case ACTION_PLAYBACK_VOLUME:
                        int volumeValue = intent.getIntExtra(PLAYBACK_EXTRA_VOLUME, -1);
                        volume(volumeValue);
                        break;
                    case ACTION_PLAYBACK_EQ_STATE:
                        boolean eqEnable = intent.getBooleanExtra(PLAYBACK_EXTRA_EQ_ENABLE, false);
                        changEQState(eqEnable);
                        break;
                    case ACTION_PLAYBACK_REPEAT_STATE:
                        boolean repeatState =
                                intent.getBooleanExtra(PLAYBACK_EXTRA_REPEAT_STATE, false);
                        repeat(repeatState);
                        break;
                }
            }
        }

        return START_STICKY;
    }

    private void changEQState(boolean enable) {
        enableEQ(enable);
    }

    private void registerHeadsetReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
        registerReceiver(headsetReceiver,
                intentFilter);
    }

    private void unregisterHeadsetReceiver() {
        unregisterReceiver(headsetReceiver);
    }

    private BroadcastReceiver headsetReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_HEADSET_PLUG:
                    int state = intent.getIntExtra("state", -1);
                    if (state == 1) {
                        play();
                    } else {
                        stop();
                    }
                    break;
            }
        }
    };


    private void createPlayer() {

        String sampleRateString, bufferSizeString;
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        sampleRateString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        bufferSizeString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        if (sampleRateString == null) {
            sampleRateString = "44100";
        }
        if (bufferSizeString == null) {
            bufferSizeString = "512";
        }

        Log.d(TAG, "create audio player!");
        createAudioPlayer(Integer.valueOf(sampleRateString), Integer.valueOf(bufferSizeString));

    }

    private void openFile(String path) {
        Log.d(TAG, "open audio player!");
        setPlayingAudioPlayer(false);
       openAudioFile(path);

        playbackState.setPlaying(false);
        EventBus.getDefault().post(false);
    }


    private void volume(int volumeValue) {
        playbackState.setVolume(volumeValue);
        setVolumeAudioPlayer(volumeValue);
    }

    private void repeat(boolean repeat) {
        playbackState.setRepeat(repeat);
        setLoopingAudioPlayer(repeat);
    }

    private void seek(int seekValue) {

        setSeekAudioPlayer(seekValue);
        audioNotificationManager.seekToPosition(seekValue * playbackState.getDurationScale());
    }

    private void play() {
        if (!isPlaying()) {
            Log.d(TAG, "start playback");
            setPlayingAudioPlayer(true);
            startUpdateTimer();

            playbackState.setPlaying(true);
            EventBus.getDefault().post(true);

            audioFocusManager.tryToGetAudioFocus();
        }
    }


    private void stop() {
        if (isPlaying()) {
            Log.d(TAG, "stop playback");
            setPlayingAudioPlayer(false);
            stopUpdateTimer();

            playbackState.setPlaying(false);
            EventBus.getDefault().post(false);

            audioFocusManager.giveUpAudioFocus();
        }
    }

    private void createValuesScale() {

        int duration = getDuration();
        playbackState.setDuration(duration);

        int progress = getPosition();
        playbackState.setProgress(progress);

        audioNotificationManager.setProgress(progress);
        if (duration > 0) {
            int durationScale = duration / 100;
            playbackState.setDurationScale(durationScale);

            int progressScale = progress / durationScale;
            playbackState.setProgressScale(progressScale);

            EventBus.getDefault().post(playbackState);

        }
    }


    private void startUpdateTimer() {
        stopUpdateTimer();
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                createValuesScale();
            }
        }, 1000, 1000);
    }

    private void stopUpdateTimer() {
        if (timer != null) {
            timer.cancel();
        }
    }

    public static PlaybackState getPlaybackState() {
        return playbackState;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy playback service");
        stop();
        audioNotificationManager.stopNotification();
        unregisterHeadsetReceiver();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static {
        System.loadLibrary("SuperpoweredPlayer");
    }

    public native void onDestroyAudioPlayer();

    public native void onBackground();

    public native void onForeground();

    public native void registerCallback();

    public native void unregisterCallback();

    public native void createAudioPlayer(int sampleRate, int bufferSize);

    public native void openAudioFile(String path);

    public native void setPlayingAudioPlayer(boolean isPlaying);

    public native void setVolumeAudioPlayer(int value);

    public native void setSeekAudioPlayer(int value);

    public native int getDuration();

    public native int getPosition();

    public native boolean isPlaying();

    public native void setLoopingAudioPlayer(boolean isLooping);

    /***
     * EQ methods
     */

    public native void enableEQ(boolean enable);

    public native void setEQBands(int band, int value);

    @Keep
    public void playStatusCallback(int status) {
        if (status == END_TRACK) {
            next();
        }
    }

}
