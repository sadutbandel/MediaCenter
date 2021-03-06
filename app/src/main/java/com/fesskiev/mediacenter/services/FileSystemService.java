package com.fesskiev.mediacenter.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.provider.MediaStore;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.fesskiev.mediacenter.MediaApplication;
import com.fesskiev.mediacenter.data.model.AudioFile;
import com.fesskiev.mediacenter.data.model.AudioFolder;
import com.fesskiev.mediacenter.data.model.MediaFile;
import com.fesskiev.mediacenter.data.model.MediaFolder;
import com.fesskiev.mediacenter.data.model.VideoFile;
import com.fesskiev.mediacenter.data.model.VideoFolder;
import com.fesskiev.mediacenter.data.source.DataRepository;
import com.fesskiev.mediacenter.data.source.memory.MemoryDataSource;
import com.fesskiev.mediacenter.utils.AppLog;
import com.fesskiev.mediacenter.utils.AppSettingsManager;
import com.fesskiev.mediacenter.utils.CacheManager;
import com.fesskiev.mediacenter.utils.NotificationHelper;
import com.fesskiev.mediacenter.utils.RxUtils;
import com.fesskiev.mediacenter.utils.StorageUtils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static com.fesskiev.mediacenter.utils.NotificationHelper.EXTRA_ACTION_BUTTON;


public class FileSystemService extends JobService {

    public enum SCAN_TYPE {
        AUDIO, VIDEO, BOTH
    }

    public enum SCAN_STATE {
        PREPARE, SCANNING_ALL, FINISHED
    }


    private static final String TAG = FileSystemService.class.getSimpleName();

    private static Uri MEDIA_URI = Uri.parse("content://" + MediaStore.AUTHORITY + "/");

    private static final int MEDIA_CONTENT_JOB = 31;

    private static final int HANDLE_MEDIA = 0;
    private static final int HANDLE_VIDEO = 1;
    private static final int HANDLE_AUDIO = 2;

    private static final String ACTION_START_FETCH_MEDIA = "com.fesskiev.player.action.FETCH_MEDIA";
    private static final String ACTION_START_FETCH_AUDIO = "com.fesskiev.player.action.START_FETCH_AUDIO";
    private static final String ACTION_START_FETCH_VIDEO = "com.fesskiev.player.action.START_FETCH_VIDEO";
    private static final String ACTION_FETCH_STATE = "com.fesskiev.player.action.ACTION_FETCH_STATE";

    public static final String ACTION_REFRESH_AUDIO_FRAGMENT = "com.fesskiev.player.action.ACTION_REFRESH_AUDIO_FRAGMENT";
    public static final String ACTION_REFRESH_VIDEO_FRAGMENT = "com.fesskiev.player.action.ACTION_REFRESH_VIDEO_FRAGMENT";

    private int notificationId;

    private DataRepository repository;

    private Handler handler;
    private FetchContentThread fetchContentThread;
    private MediaObserver observer;

    private SCAN_TYPE scanType;
    private SCAN_STATE scanState;

    public static void startFileSystemService(Context context) {
        Intent intent = new Intent(context, FileSystemService.class);
        context.startService(intent);
    }

    public static void stopFileSystemService(Context context) {
        Intent intent = new Intent(context, FileSystemService.class);
        context.stopService(intent);
    }

    public static void startFetchMedia(Context context) {
        Intent intent = new Intent(context, FileSystemService.class);
        intent.setAction(ACTION_START_FETCH_MEDIA);
        context.startService(intent);
    }

    public static void startFetchAudio(Context context) {
        Intent intent = new Intent(context, FileSystemService.class);
        intent.setAction(ACTION_START_FETCH_AUDIO);
        context.startService(intent);
    }

    public static void startFetchVideo(Context context) {
        Intent intent = new Intent(context, FileSystemService.class);
        intent.setAction(ACTION_START_FETCH_VIDEO);
        context.startService(intent);
    }

    public static void requestFetchState(Context context) {
        Intent intent = new Intent(context, FileSystemService.class);
        intent.setAction(ACTION_FETCH_STATE);
        context.startService(intent);
    }


    public static void scheduleJob(Context context, int periodic) {

        JobScheduler js = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        JobInfo.Builder builder = new JobInfo.Builder(MEDIA_CONTENT_JOB,
                new ComponentName(context, FileSystemService.class));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setMinimumLatency(periodic);
        } else {
            builder.setPeriodic(periodic);
        }
        builder.setOverrideDeadline(periodic);
        builder.setRequiresDeviceIdle(false);
        builder.setRequiresCharging(false);

        if (js != null) {
            int jobValue = js.schedule(builder.build());
            if (jobValue == JobScheduler.RESULT_FAILURE) {
                Log.w(TAG, "JobScheduler launch the task failure");
            } else {
                Log.w(TAG, "JobScheduler launch the task success: " + jobValue);
            }
            Log.i(TAG, "JOB SCHEDULED!");
        }
    }

    public static boolean isScheduled(Context context) {
        JobScheduler js = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            List<JobInfo> jobs = js.getAllPendingJobs();
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).getId() == MEDIA_CONTENT_JOB) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void cancelJob(Context context) {
        JobScheduler js = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            js.cancel(MEDIA_CONTENT_JOB);
            Log.i(TAG, "JOB SCHEDULED? " + isScheduled(context));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "File System Service created");

        repository = MediaApplication.getInstance().getRepository();

        fetchContentThread = new FetchContentThread();
        fetchContentThread.start();

        observer = new MediaObserver(handler);
        getContentResolver().registerContentObserver(
                MEDIA_URI,
                true,
                observer);
        registerNotificationReceiver();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "File System Service destroyed");

        fetchContentThread.quitSafely();

        getContentResolver().unregisterContentObserver(observer);
        unregisterNotificationReceiver();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            if (action != null) {
                Log.w(TAG, "HANDLE INTENT: " + action);
                switch (action) {
                    case ACTION_START_FETCH_MEDIA:
                        handler.sendEmptyMessage(HANDLE_MEDIA);
                        break;
                    case ACTION_START_FETCH_VIDEO:
                        handler.sendEmptyMessage(HANDLE_VIDEO);
                        break;
                    case ACTION_START_FETCH_AUDIO:
                        handler.sendEmptyMessage(HANDLE_AUDIO);
                        break;
                    case ACTION_FETCH_STATE:
                        sendFetchState();
                        break;
                }
            }
        }
        return START_NOT_STICKY;
    }

    private void registerNotificationReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(NotificationHelper.ACTION_SKIP_BUTTON);
        filter.addAction(NotificationHelper.ACTION_ADD_BUTTON);
        registerReceiver(notificationReceiver, filter);

    }

    private void unregisterNotificationReceiver() {
        unregisterReceiver(notificationReceiver);
    }

    private BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action != null) {
                final String extrasPath = intent.getStringExtra(EXTRA_ACTION_BUTTON);
                switch (action) {
                    case NotificationHelper.ACTION_SKIP_BUTTON:
                        removeMediaFromCache(extrasPath);
                        break;
                    case NotificationHelper.ACTION_ADD_BUTTON:
                        addMedia(extrasPath);
                        break;
                }
            }
        }
    };

    private void removeMediaFromCache(String path) {
        MediaFileNotification mediaFileNotification = observer.removeMediaFileByPath(path);
        if (mediaFileNotification != null) {
            NotificationHelper.removeNotificationAndCloseBar(getApplicationContext(),
                    mediaFileNotification.getNotificationId());
        }
        MediaFolderNotification mediaFolderNotification = observer.removeMediaFolderByPath(path);
        if (mediaFolderNotification != null) {
            NotificationHelper.removeNotificationAndCloseBar(getApplicationContext(),
                    mediaFolderNotification.getNotificationId());
        }
    }

    private void addMedia(String extrasPath) {
        Observable.just(extrasPath)
                .subscribeOn(Schedulers.io())
                .doOnNext(path -> {
                    MediaFileNotification mediaFileNotification = observer.getMediaFileByPath(path);
                    if (mediaFileNotification != null) {
                        MediaFile mediaFile = mediaFileNotification.getMediaFile();
                        if (mediaFile instanceof AudioFile) {
                            repository.insertAudioFile((AudioFile) mediaFile);
                        } else if (mediaFile instanceof VideoFile) {
                            repository.insertVideoFile((VideoFile) mediaFile);
                        }
                    } else {
                        MediaFolderNotification mediaFolderNotification = observer.getMediaFolderByPath(path);
                        if (mediaFolderNotification != null) {
                            MediaFolder mediaFolder = mediaFolderNotification.getMediaFolder();
                            List<MediaFile> mediaFiles = mediaFolderNotification.getMediaFiles();
                            if (mediaFolder instanceof AudioFolder) {
                                repository.insertAudioFolder((AudioFolder) mediaFolder);
                                for (MediaFile audioFile : mediaFiles) {
                                    repository.insertAudioFile((AudioFile) audioFile);
                                }
                                refreshAudioFragment();
                            } else if (mediaFolder instanceof VideoFolder) {
                                repository.insertVideoFolder((VideoFolder) mediaFolder);
                                for (MediaFile videoFile : mediaFiles) {
                                    repository.insertVideoFile((VideoFile) videoFile);
                                }
                                refreshVideoFragment();
                            }
                        }
                    }
                })
                .flatMap(path -> {
                    MediaFileNotification mediaFileNotification = observer.removeMediaFileByPath(path);
                    if (mediaFileNotification != null) {
                        return Observable.just(mediaFileNotification.getNotificationId());
                    }
                    MediaFolderNotification mediaFolderNotification = observer.removeMediaFolderByPath(path);
                    if (mediaFolderNotification != null) {
                        return Observable.just(mediaFolderNotification.getNotificationId());
                    }
                    return Observable.empty();
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(notificationId -> NotificationHelper.removeNotificationAndCloseBar(getApplicationContext(), notificationId));
    }

    private void refreshVideoFragment() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_REFRESH_VIDEO_FRAGMENT));
    }

    private void refreshAudioFragment() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_REFRESH_AUDIO_FRAGMENT));
    }

    private void sendFetchState() {
        EventBus.getDefault().post(FileSystemService.this);
    }

    private void prepareScan() {
        scanState = SCAN_STATE.PREPARE;
        EventBus.getDefault().post(FileSystemService.this);
    }

    private void startScanAll() {
        scanState = SCAN_STATE.SCANNING_ALL;
        EventBus.getDefault().post(FileSystemService.this);
    }

    private void finishScan() {
        scanState = SCAN_STATE.FINISHED;
        EventBus.getDefault().post(FileSystemService.this);
    }

    private void startScan(SCAN_TYPE scanType) {
        prepareScan();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        String[] storagePaths = StorageUtils.getStorageDirectories(getApplicationContext());
        if (storagePaths.length > 0) {
            startScanAll();
            for (String path : storagePaths) {
                Log.e(TAG, "path: " + path);
                fileWalk(path, scanType);
            }
        }

        finishScan();
    }

    private void fileWalk(String startPath, SCAN_TYPE scanType) {
        Collection<File> listOfFiles = FileUtils.listFilesAndDirs(new File(startPath),
                TrueFileFilter.INSTANCE,
                new IOFileFilter() {
                    @Override
                    public boolean accept(File file) {
                        try {
                            return isPlainDir(file);
                        } catch (IOException ex) {
                            return false;
                        }
                    }

                    @Override
                    public boolean accept(File dir, String name) {
                        try {
                            return isPlainDir(dir);
                        } catch (IOException ex) {
                            return false;
                        }
                    }
                });

        try {

            int size = listOfFiles.size();
            float count = 0;

            for (File n : listOfFiles) {
                if (n.getAbsolutePath().equals(CacheManager.EXTERNAL_STORAGE)) {
                    continue;
                }
                if (isPlainDir(n)) {
                    checkDir(n, scanType);
                } else {
                    checkFile(n, scanType);
                }
                EventBus.getDefault().post(+(count / (float) size) * 100);
                count++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void checkFile(File file, SCAN_TYPE scanType) {
        switch (scanType) {
            case BOTH:
                filterAudioFile(file);
                filterVideoFile(file);
                break;
            case AUDIO:
                filterAudioFile(file);
                break;
            case VIDEO:
                filterVideoFile(file);
                break;
        }
    }

    private void filterVideoFile(File file) {

    }

    private void filterAudioFile(File file) {

    }

    private void checkDir(File dir, SCAN_TYPE scanType) {
        switch (scanType) {
            case BOTH:
                filterAudioFolders(dir);
                filterVideoFolders(dir);
                break;
            case AUDIO:
                filterAudioFolders(dir);
                break;
            case VIDEO:
                filterVideoFolders(dir);
                break;
        }

    }

    private void sendFileDescription(String name) {
        EventBus.getDefault().post(new FetchDescription(null, name));
    }

    private void sendFolderDescription(String name) {
        EventBus.getDefault().post(new FetchDescription(name, null));
    }

    private void sendFolderCreated(int type) {
        EventBus.getDefault().post(new FetchFolderCreate(type));
    }

    private void filterVideoFolders(File directoryFile) {
        File[] videoPaths = directoryFile.listFiles(videoFilter());
        if (videoPaths != null && videoPaths.length > 0) {

            VideoFolder videoFolder = new VideoFolder();

            videoFolder.folderPath = directoryFile;
            videoFolder.folderName = directoryFile.getName();
            videoFolder.id = UUID.randomUUID().toString();
            videoFolder.timestamp = System.currentTimeMillis();


            sendFolderDescription(videoFolder.folderName);

            for (File path : videoPaths) {

                VideoFile videoFile = new VideoFile(path, videoFolder.id);

                repository.insertVideoFile(videoFile);

                sendFileDescription(videoFile.description);
            }

            repository.insertVideoFolder(videoFolder);

            sendFolderCreated(FetchFolderCreate.VIDEO);

        }
    }

    private void filterAudioFolders(File directoryFile) {
        File[] audioPaths = directoryFile.listFiles(audioFilter());
        if (audioPaths != null && audioPaths.length > 0) {

            AudioFolder audioFolder = new AudioFolder();

            File[] filterImages = directoryFile.listFiles(folderImageFilter());
            if (filterImages != null && filterImages.length > 0) {
                audioFolder.folderImage = filterImages[0];
            }

            audioFolder.folderPath = directoryFile;
            audioFolder.folderName = directoryFile.getName();
            audioFolder.id = UUID.randomUUID().toString();
            audioFolder.timestamp = System.currentTimeMillis();

            sendFolderDescription(audioFolder.folderName);

            for (File path : audioPaths) {
                AudioFile audioFile = new AudioFile(getApplicationContext(), path, audioFolder.id);
                File folderImage = audioFolder.folderImage;
                if (folderImage != null) {
                    audioFile.folderArtworkPath = folderImage.getAbsolutePath();
                }
                repository.insertAudioFile(audioFile);

                sendFileDescription(audioFile.artist + "-" + audioFile.title);
            }

            repository.insertAudioFolder(audioFolder);

            sendFolderCreated(FetchFolderCreate.AUDIO);
        }
    }

    private class MediaObserver extends ContentObserver {

        private List<MediaFileNotification> mediaFiles;
        private List<MediaFolderNotification> mediaFolders;
        private List<String> folderPaths;
        private List<String> filePaths;

        private long lastTimeCall = 0L;
        private long lastTimeUpdate = 0L;
        private long threshold = 100;

        public MediaObserver(Handler handler) {
            super(handler);
            mediaFiles = new ArrayList<>();
            mediaFolders = new ArrayList<>();
            folderPaths = new ArrayList<>();
            filePaths = new ArrayList<>();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange);

            lastTimeCall = System.currentTimeMillis();

            if (lastTimeCall - lastTimeUpdate > threshold) {

                Cursor cursor = null;
                try {
                    String[] projection = {MediaStore.Files.FileColumns.DATA};
                    cursor = getContentResolver().query(uri, projection, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToLast()) {
                            String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA));
                            AppLog.ERROR("FIND PATH: " + path);
                            File file = new File(path);
                            File parent = file.getParentFile();
                            if (parent.isDirectory()) {
                                if (!containsMediaFolder(parent.getAbsolutePath())) {
                                    folderPaths.add(parent.getAbsolutePath());
                                    File[] audioPaths = parent.listFiles(audioFilter());
                                    if (audioPaths != null && audioPaths.length > 0) {
                                        if (!repository.containAudioFolder(parent.getAbsolutePath())) {
                                            addAudioFolderToCache(parent);
                                        } else {
                                            if (!containMediaFile(path)) {
                                                filePaths.add(path);

                                                addAudioFileToCache(file);
                                            }
                                        }
                                    }
                                    File[] videoPaths = parent.listFiles(videoFilter());
                                    if (videoPaths != null && videoPaths.length > 0) {
                                        if (!repository.containVideoFolder(parent.getAbsolutePath())) {
                                            addVideoFolderToCache(parent);
                                        } else {
                                            if (!containMediaFile(path)) {
                                                filePaths.add(path);

                                                addVideoFileToCache(file);
                                            }
                                        }
                                    }
                                }
                            } else if (file.isFile()) {
                                if (!containMediaFile(path)) {
                                    filePaths.add(path);

                                    if (isAudioFile(path)) {
                                        addAudioFileToCache(file);
                                    } else if (isVideoFile(path)) {
                                        addVideoFileToCache(file);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                lastTimeUpdate = System.currentTimeMillis();
            }
        }

        private void addVideoFileToCache(File file) {
            repository.getVideoFolderByPath(file.getParent())
                    .firstOrError()
                    .toObservable()
                    .subscribeOn(Schedulers.io())
                    .flatMap(videoFolder -> {
                        if (videoFolder != null) {
                            return Observable.just(new VideoFile(file, videoFolder.id));
                        }
                        return Observable.empty();
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(videoFile -> {
                        if (videoFile != null) {
                            notificationId++;
                            NotificationHelper.getInstance()
                                    .createMediaFileNotification(videoFile, notificationId);
                            mediaFiles.add(new MediaFileNotification(notificationId, videoFile));
                        }
                    });
        }

        private void addAudioFileToCache(File file) {
            repository.getAudioFolderByPath(file.getParent())
                    .firstOrError()
                    .toObservable()
                    .subscribeOn(Schedulers.io())
                    .flatMap(audioFolder -> {
                        if (audioFolder != null) {
                            return Observable.just(new AudioFile(getApplicationContext(), file,
                                    audioFolder.id));
                        }
                        return Observable.empty();
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(audioFile -> {
                        if (audioFile != null) {
                            notificationId++;
                            NotificationHelper.getInstance()
                                    .createMediaFileNotification(audioFile, notificationId);
                            mediaFiles.add(new MediaFileNotification(notificationId, audioFile));
                        }
                    });
        }

        private void addVideoFolderToCache(File parent) {
            Observable.just(parent)
                    .delay(40, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .flatMap(pt -> {

                        VideoFolder videoFolder = new VideoFolder();
                        videoFolder.folderPath = parent;
                        videoFolder.folderName = parent.getName();
                        videoFolder.id = UUID.randomUUID().toString();
                        videoFolder.timestamp = System.currentTimeMillis();

                        List<MediaFile> videoFiles = new ArrayList<>();
                        File[] videoPaths = parent.listFiles(videoFilter());
                        for (File p : videoPaths) {
                            VideoFile videoFile = new VideoFile(p, videoFolder.id);
                            videoFiles.add(videoFile);
                        }
                        MediaFolderNotification mediaFolderNotification =
                                new MediaFolderNotification(videoFolder, videoFiles);
                        mediaFolders.add(mediaFolderNotification);

                        return Observable.just(mediaFolderNotification);
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(mediaFolderNotification -> {
                        if (mediaFolderNotification != null) {
                            notificationId++;
                            mediaFolderNotification.setNotificationId(notificationId);
                            NotificationHelper.getInstance()
                                    .createMediaFolderNotification(mediaFolderNotification.getMediaFolder(),
                                            notificationId);
                        }
                    });
        }

        private void addAudioFolderToCache(File parent) {
            Observable.just(parent)
                    .delay(20, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .flatMap(pt -> {

                        AudioFolder audioFolder = new AudioFolder();
                        File[] filterImages = parent.listFiles(folderImageFilter());
                        if (filterImages != null && filterImages.length > 0) {
                            audioFolder.folderImage = filterImages[0];
                        }
                        audioFolder.folderPath = parent;
                        audioFolder.folderName = parent.getName();
                        audioFolder.id = UUID.randomUUID().toString();
                        audioFolder.timestamp = System.currentTimeMillis();

                        List<MediaFile> audioFiles = new ArrayList<>();
                        File[] audioPaths = parent.listFiles(audioFilter());
                        for (File p : audioPaths) {
                            AudioFile audioFile = new AudioFile(getApplicationContext(), p,
                                    audioFolder.id);
                            File folderImage = audioFolder.folderImage;
                            if (folderImage != null) {
                                audioFile.folderArtworkPath =
                                        folderImage.getAbsolutePath();
                            }
                            audioFiles.add(audioFile);
                        }
                        MediaFolderNotification mediaFolderNotification
                                = new MediaFolderNotification(audioFolder, audioFiles);
                        mediaFolders.add(mediaFolderNotification);

                        return Observable.just(mediaFolderNotification);

                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(mediaFolderNotification -> {
                        if (mediaFolderNotification != null) {
                            notificationId++;
                            mediaFolderNotification.setNotificationId(notificationId);
                            NotificationHelper.getInstance()
                                    .createMediaFolderNotification(mediaFolderNotification.getMediaFolder(),
                                            notificationId);
                        }
                    });
        }

        private boolean containsMediaFolder(String path) {
            for (String p : folderPaths) {
                if (p.equals(path)) {
                    return true;
                }
            }
            return false;
        }

        private boolean containMediaFile(String path) {
            for (String p : filePaths) {
                if (p.equals(path)) {
                    return true;
                }
            }
            return false;
        }

        public MediaFileNotification getMediaFileByPath(String path) {
            for (MediaFileNotification mediaFile : mediaFiles) {
                if (mediaFile.getMediaFile().getFilePath().equals(path)) {
                    return mediaFile;
                }
            }
            return null;
        }

        public MediaFileNotification removeMediaFileByPath(String path) {
            for (MediaFileNotification mediaFile : mediaFiles) {
                if (mediaFile.getMediaFile().getFilePath().equals(path)) {
                    mediaFiles.remove(mediaFile);
                    return mediaFile;
                }
            }
            return null;
        }

        public MediaFolderNotification removeMediaFolderByPath(String path) {
            for (Iterator<MediaFolderNotification> it = mediaFolders.listIterator(); it.hasNext(); ) {
                MediaFolderNotification mediaFolder = it.next();
                if (mediaFolder.getMediaFolder().getPath().equals(path)) {
                    it.remove();
                    return mediaFolder;
                }
            }
            return null;
        }

        public MediaFolderNotification getMediaFolderByPath(String path) {
            for (MediaFolderNotification mediaFolder : mediaFolders) {
                if (mediaFolder.getMediaFolder().getPath().equals(path)) {
                    return mediaFolder;
                }
            }
            return null;
        }
    }

    private class FetchContentThread extends HandlerThread {

        public FetchContentThread() {
            super(FetchContentThread.class.getName(), Process.THREAD_PRIORITY_BACKGROUND);
        }

        @Override
        protected void onLooperPrepared() {
            super.onLooperPrepared();
            handler = new Handler(getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case HANDLE_MEDIA:
                            getMediaContent(msg);
                            break;
                        case HANDLE_VIDEO:
                            getVideoContent();
                            break;
                        case HANDLE_AUDIO:
                            getAudioContent();
                            break;
                    }
                }
            };
        }
    }

    private void getMediaContent(Message msg) {
        Observable.zip(RxUtils.fromCallable(repository.resetAudioContentDatabase()),
                RxUtils.fromCallable(repository.resetVideoContentDatabase()),
                (integer, integer2) -> Observable.empty())
                .firstOrError()
                .toObservable()
                .doOnNext(observable -> refreshRepository())
                .doOnNext(observable -> clearImagesCache())
                .subscribe(observable -> {
                    JobParameters jobParameters = (JobParameters) msg.obj;

                    if (jobParameters != null) {
                        NotificationHelper.getInstance().createFetchNotification();
                    }

                    getMediaContent();

                    if (jobParameters != null) {
                        int interval = (int) AppSettingsManager.getInstance().getMediaContentUpdateTime();
                        if (interval > 0) {
                            scheduleJob(getApplicationContext(), interval);
                            jobFinished(jobParameters, false);
                        } else {
                            jobFinished(jobParameters, true);
                        }
                    }
                }, throwable -> finishScan());
    }

    private void clearImagesCache() {
        CacheManager.clearVideoImagesCache();
        CacheManager.clearAudioImagesCache();
    }

    private void refreshRepository() {
        MemoryDataSource memoryDataSource = repository.getMemorySource();
        memoryDataSource.setCacheArtistsDirty(true);
        memoryDataSource.setCacheGenresDirty(true);
        memoryDataSource.setCacheFoldersDirty(true);
        memoryDataSource.setCacheVideoFoldersDirty(true);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "onStartJob");
        if (handler != null) {
            Message msg = handler.obtainMessage();
            msg.obj = params;
            msg.what = HANDLE_MEDIA;
            handler.sendMessage(msg);
            Log.i(TAG, "Send job...");
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "onStopJob");
        return false;
    }


    private void getAudioContent() {
        scanType = SCAN_TYPE.AUDIO;
        startScan(scanType);
    }

    private void getVideoContent() {
        scanType = SCAN_TYPE.VIDEO;
        startScan(scanType);
    }

    private void getMediaContent() {
        scanType = SCAN_TYPE.BOTH;
        startScan(scanType);
    }


    public static FilenameFilter audioFilter() {
        return (dir, name) -> {
            String lowercaseName = name.toLowerCase();
            return lowercaseName.endsWith(".mp3") || lowercaseName.endsWith(".flac") || lowercaseName.endsWith(".wav")
                    || lowercaseName.endsWith(".m4a") || lowercaseName.endsWith(".aac") || lowercaseName.endsWith(".aiff");
        };
    }

    public static FilenameFilter folderImageFilter() {
        return (dir, name) -> {
            String lowercaseName = name.toLowerCase();
            return (lowercaseName.endsWith(".png") || lowercaseName.endsWith(".jpg"));
        };
    }

    public static FilenameFilter videoFilter() {
        return (dir, name) -> {
            String lowercaseName = name.toLowerCase();
            return (lowercaseName.endsWith(".mp4") || lowercaseName.endsWith(".ts") || lowercaseName.endsWith(".mkv"));
        };
    }


    /**
     * Determine if {@code file} is a directory and is not a symbolic link.
     *
     * @param file File to test.
     * @return True if {@code file} is a directory and is not a symbolic link.
     * @throws IOException If a symbolic link could not be determined. This is ultimately
     *                     caused by a call to {@link File#getCanonicalFile()}.
     */
    private static boolean isPlainDir(File file) throws IOException {
        return file.isDirectory() && !isSymbolicLink(file);
    }

    /**
     * Given a {@link File} object, test if it is likely to be a symbolic link.
     *
     * @param file File to test for symbolic link.
     * @return {@code true} if {@code file} is a symbolic link.
     * @throws NullPointerException If {@code file} is null.
     * @throws IOException          If a symbolic link could not be determined. This is ultimately
     *                              caused by a call to {@link File#getCanonicalFile()}.
     */
    private static boolean isSymbolicLink(File file) throws IOException {
        if (file == null) {
            throw new NullPointerException("File must not be null");
        }
        File canon;
        if (file.getParent() == null) {
            canon = file;
        } else {
            File canonDir = file.getParentFile().getCanonicalFile();
            canon = new File(canonDir, file.getName());
        }
        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    }

    public SCAN_TYPE getScanType() {
        return scanType;
    }

    public SCAN_STATE getScanState() {
        return scanState;
    }

    public static class FetchDescription {

        private String folderName;
        private String fileName;

        public FetchDescription(String folderName, String fileName) {
            this.folderName = folderName;
            this.fileName = fileName;
        }

        public String getFolderName() {
            return folderName;
        }

        public String getFileName() {
            return fileName;
        }
    }

    public static boolean isVideoFile(String path) {
        String mimeType = URLConnection.guessContentTypeFromName(path);
        return mimeType != null && mimeType.startsWith("video");
    }

    public static boolean isAudioFile(String path) {
        String mimeType = URLConnection.guessContentTypeFromName(path);
        return mimeType != null && mimeType.startsWith("audio");
    }

    public static class FetchFolderCreate {

        public static final int AUDIO = 0;
        public static final int VIDEO = 1;

        private int type;

        public FetchFolderCreate(int type) {
            this.type = type;
        }

        public int getType() {
            return type;
        }
    }

    private class MediaFileNotification {

        private int notificationId;
        private MediaFile mediaFile;

        public MediaFileNotification(int notificationId, MediaFile mediaFile) {
            this.notificationId = notificationId;
            this.mediaFile = mediaFile;
        }

        public int getNotificationId() {
            return notificationId;
        }

        public MediaFile getMediaFile() {
            return mediaFile;
        }
    }

    private class MediaFolderNotification {

        private int notificationId;
        private MediaFolder mediaFolder;
        private List<MediaFile> mediaFiles;

        public MediaFolderNotification(MediaFolder mediaFolder,
                                       List<MediaFile> mediaFiles) {
            this.mediaFolder = mediaFolder;
            this.mediaFiles = mediaFiles;
        }

        public void setNotificationId(int notificationId) {
            this.notificationId = notificationId;
        }

        public int getNotificationId() {
            return notificationId;
        }

        public MediaFolder getMediaFolder() {
            return mediaFolder;
        }

        public List<MediaFile> getMediaFiles() {
            return mediaFiles;
        }
    }


}
