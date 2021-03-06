package com.fesskiev.mediacenter.players;


import android.content.Context;
import android.util.Log;

import com.fesskiev.mediacenter.MediaApplication;
import com.fesskiev.mediacenter.data.model.AudioFile;
import com.fesskiev.mediacenter.data.model.AudioFolder;
import com.fesskiev.mediacenter.data.model.MediaFile;
import com.fesskiev.mediacenter.data.source.DataRepository;
import com.fesskiev.mediacenter.services.PlaybackService;
import com.fesskiev.mediacenter.ui.playback.Playable;
import com.fesskiev.mediacenter.utils.AppSettingsManager;
import com.fesskiev.mediacenter.utils.comparators.SortByDuration;
import com.fesskiev.mediacenter.utils.comparators.SortByFileSize;
import com.fesskiev.mediacenter.utils.comparators.SortByTimestamp;
import com.fesskiev.mediacenter.utils.ffmpeg.FFmpegHelper;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class AudioPlayer implements Playable {

    private static final String TAG = AudioPlayer.class.getSimpleName();

    public static final int SORT_DURATION = 0;
    public static final int SORT_FILE_SIZE = 1;
    public static final int SORT_TRACK_NUMBER = 2;
    public static final int SORT_TIMESTAMP = 3;

    private Context context;
    private DataRepository repository;

    private TrackListIterator trackListIterator;
    private List<AudioFile> currentTrackList;
    private AudioFile currentTrack;
    private int position;


    public AudioPlayer(DataRepository repository) {
        this.repository = repository;

        context = MediaApplication.getInstance().getApplicationContext();
        trackListIterator = new TrackListIterator();
    }

    public void updateCurrentTrackAndTrackList() {
        repository.getSelectedFolderAudioFiles()
                .subscribeOn(Schedulers.io())
                .flatMap(audioFiles -> Observable.just(sortAudioFiles(AppSettingsManager.getInstance()
                        .getSortType(), audioFiles)))
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(audioFiles -> {
                    currentTrackList = audioFiles;
                    notifyCurrentTrackList();
                })
                .subscribeOn(Schedulers.io())
                .flatMap(audioFiles -> repository.getSelectedAudioFile())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(audioFile -> {
                    currentTrack = audioFile;
                    notifyCurrentTrack();
                })
                .firstOrError()
                .subscribe(object -> Log.e(TAG, "UPDATE: " + AudioPlayer.this.toString()),
                        Throwable::printStackTrace);
    }


    public void getCurrentTrackAndTrackList() {
        repository.getSelectedFolderAudioFiles()
                .subscribeOn(Schedulers.io())
                .flatMap(audioFiles -> Observable.just(sortAudioFiles(AppSettingsManager.getInstance()
                        .getSortType(), audioFiles)))
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(audioFiles -> {
                    currentTrackList = audioFiles;
                    notifyCurrentTrackList();
                })
                .subscribeOn(Schedulers.io())
                .flatMap(audioFiles -> repository.getSelectedAudioFile())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(audioFile -> {
                    currentTrack = audioFile;
                    openAudioFile(false);
                    if (audioFile != null) {
                        trackListIterator.findPosition();
                    }
                })
                .firstOrError()
                .subscribe(object -> Log.e(TAG, "GET: " + AudioPlayer.this.toString()), Throwable::printStackTrace);
    }

    @Override
    public void open(MediaFile audioFile) {
        if (audioFile != null) {
            PlaybackService.openFile(context, audioFile.getFilePath());
        }
    }


    @Override
    public void play() {
        PlaybackService.startPlayback(context);
    }

    @Override
    public void pause() {
        PlaybackService.stopPlayback(context);
    }

    @Override
    public void next() {
        if (trackListIterator.hasNext()) {
            AudioFile audioFile = trackListIterator.next();
            if (audioFile != null) {
                currentTrack = audioFile;

                audioFile.isSelected = true;
                repository.updateSelectedAudioFile(audioFile);

                openAudioFile(true);
            }
        }
    }

    @Override
    public void previous() {
        if (trackListIterator.hasPrevious()) {
            AudioFile audioFile = trackListIterator.previous();
            if (audioFile != null) {
                currentTrack = audioFile;

                audioFile.isSelected = true;
                repository.updateSelectedAudioFile(audioFile);

                openAudioFile(true);
            }
        }
    }

    @Override
    public boolean first() {
        return trackListIterator.firstTrack();
    }

    @Override
    public boolean last() {
        return trackListIterator.lastTrack();
    }


    private void openAudioFile(boolean startPlayback) {
        notifyCurrentTrack();
        Log.e(TAG, AudioPlayer.this.toString());

        FFmpegHelper FFmpeg = FFmpegHelper.getInstance();
        if (FFmpeg.isCommandRunning()) {
            FFmpeg.killRunningProcesses();
        }

        if (FFmpegHelper.isAudioFileFLAC(currentTrack)) {
            FFmpeg.convertAudioIfNeed(currentTrack, new FFmpegHelper.OnConvertProcessListener() {

                @Override
                public void onStart() {
                    Log.e(TAG, "onStart() convert");
                    pause();
                    PlaybackService.startConvert(context);
                }

                @Override
                public void onSuccess(AudioFile audioFile) {
                    Log.e(TAG, "onSuccess convert");
                    open(currentTrack);
                    if (startPlayback) {
                        play();
                    }
                }

                @Override
                public void onFailure(Exception error) {
                    Log.e(TAG, "onFailure: " + error.getMessage());
                }
            });
        } else {
            open(currentTrack);
            if (startPlayback) {
                play();
            }
        }
    }

    public void setCurrentAudioFileAndPlay(AudioFile audioFile) {
        currentTrack = audioFile;

        trackListIterator.findPosition();

        audioFile.isSelected = true;
        repository.updateSelectedAudioFile(audioFile);

        openAudioFile(true);
    }


    public void setCurrentTrackList(AudioFolder audioFolder, List<AudioFile> audioFiles) {
        if (audioFiles == null || audioFiles.isEmpty()) {
            return;
        }
        currentTrackList = audioFiles;

        audioFolder.isSelected = true;
        repository.updateSelectedAudioFolder(audioFolder);

        notifyCurrentTrackList();

        Log.e(TAG, AudioPlayer.this.toString());
    }

    public void setCurrentTrackList(List<AudioFile> audioFiles) {
        currentTrackList = audioFiles;

        notifyCurrentTrackList();

        Log.e(TAG, AudioPlayer.this.toString());
    }

    public void setSortingTrackList(List<AudioFile> audioFiles) {
        if (audioFiles == null || audioFiles.isEmpty()) {
            return;
        }
        if (isSortingCurrentTrackList(audioFiles)) {
            currentTrackList = audioFiles;
            trackListIterator.findPosition();

            notifyCurrentTrackList();

            Log.e(TAG, AudioPlayer.this.toString());
        }
    }


    private void notifyCurrentTrackList() {
        if (currentTrackList != null) {
            EventBus.getDefault().post(currentTrackList);
        }
    }

    private void notifyCurrentTrack() {
        if (currentTrack != null) {
            EventBus.getDefault().post(currentTrack);
        }
    }


    private boolean isSortingCurrentTrackList(List<AudioFile> audioFiles) {
        if (currentTrackList == null) {
            return false;
        }
        return currentTrackList.containsAll(audioFiles);
    }

    public AudioFile getCurrentTrack() {
        return currentTrack;
    }


    public List<AudioFile> getCurrentTrackList() {
        return currentTrackList;
    }

    public boolean isDeletedFolderSelect(AudioFolder audioFolder) {
        if (currentTrack != null && currentTrack.folderId.equals(audioFolder.id)) {
            return true;
        }
        if (currentTrackList != null) {
            for (AudioFile audioFile : currentTrackList) {
                if (audioFile.folderId.equals(audioFolder.id)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void nextAfterEnd() {
        if (!last()) {
            next();
        }
    }

    public List<AudioFile> sortAudioFiles(int type, List<AudioFile> unsortedList) {
        List<AudioFile> sortedList = new ArrayList<>(unsortedList);
        switch (type) {
            case SORT_DURATION:
                Collections.sort(sortedList, new SortByDuration());
                break;
            case SORT_FILE_SIZE:
                Collections.sort(sortedList, new SortByFileSize());
                break;
            case SORT_TIMESTAMP:
                Collections.sort(sortedList, new SortByTimestamp());
                break;
            case SORT_TRACK_NUMBER:
                Collections.sort(sortedList);
                break;
        }
        return sortedList;
    }

    public void playTrackByTitle(String title) {
        if (currentTrackList != null) {
            for (AudioFile audioFile : currentTrackList) {
                if (audioFile.title.equals(title)) {
                    setCurrentAudioFileAndPlay(audioFile);
                }
            }
        }
    }


    private class TrackListIterator implements ListIterator<AudioFile> {

        public TrackListIterator() {
            position = -1;
        }

        @Override
        public boolean hasNext() {
            return !lastTrack();
        }

        @Override
        public boolean hasPrevious() {
            return !firstTrack();
        }

        @Override
        public AudioFile next() {
            nextIndex();
            try {
                return currentTrackList.get(position);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public AudioFile previous() {
            previousIndex();
            try {
                return currentTrackList.get(position);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        public int nextIndex() {
            return position++;
        }

        @Override
        public int previousIndex() {
            return position--;
        }

        public boolean lastTrack() {
            if (currentTrackList == null) {
                return true;
            }
            return position == (currentTrackList.size() - 1);
        }

        public boolean firstTrack() {
            if (currentTrackList == null) {
                return true;
            }
            return position == 0;
        }

        public void findPosition() {
            if (currentTrackList != null && currentTrackList.contains(currentTrack)) {
                position = currentTrackList.indexOf(currentTrack);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(AudioFile audioFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(AudioFile audioFile) {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public String toString() {
        return "AudioPlayer{" +
                "currentTrackList=" + "\n" + printCurrentTrackList() +
                "currentTrack=" + currentTrack + "\n" +
                "position=" + position +
                '}';
    }

    private String printCurrentTrackList() {
        if (currentTrackList != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < currentTrackList.size(); i++) {
                AudioFile audioFile = currentTrackList.get(i);
                sb.append(String.format(Locale.getDefault(), "%d. %s", i, audioFile.getFileName()));
                sb.append("\n");
            }
            return sb.toString();
        }
        return null;
    }

}
