package com.fesskiev.mediacenter.ui.playlist;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.fesskiev.mediacenter.MediaApplication;
import com.fesskiev.mediacenter.R;
import com.fesskiev.mediacenter.analytics.AnalyticsActivity;
import com.fesskiev.mediacenter.data.model.AudioFile;
import com.fesskiev.mediacenter.data.model.MediaFile;
import com.fesskiev.mediacenter.data.source.DataRepository;
import com.fesskiev.mediacenter.players.AudioPlayer;
import com.fesskiev.mediacenter.ui.audio.player.AudioPlayerActivity;
import com.fesskiev.mediacenter.ui.video.player.VideoExoPlayerActivity;
import com.fesskiev.mediacenter.utils.AppLog;
import com.fesskiev.mediacenter.utils.BitmapHelper;
import com.fesskiev.mediacenter.utils.RxUtils;
import com.fesskiev.mediacenter.utils.Utils;
import com.fesskiev.mediacenter.widgets.recycleview.ScrollingLinearLayoutManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class PlayListActivity extends AnalyticsActivity {

    private Subscription subscription;
    private DataRepository repository;

    private AudioTracksAdapter adapter;
    private CardView emptyPlaylistCard;
    private Menu menu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);

        repository = MediaApplication.getInstance().getRepository();

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(getString(R.string.title_playlist_activity));
            setSupportActionBar(toolbar);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        emptyPlaylistCard = (CardView) findViewById(R.id.emptyPlaylistCard);

        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.recycleView);
        recyclerView.setLayoutManager(new ScrollingLinearLayoutManager(this,
                LinearLayoutManager.VERTICAL, false, 1000));
        adapter = new AudioTracksAdapter(this);
        recyclerView.setAdapter(adapter);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.menu = menu;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_playlist, menu);
        fetchPlayListFiles();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_clear_playlist:
                clearPlaylist();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public String getActivityName() {
        return this.getLocalClassName();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxUtils.unsubscribe(subscription);
    }


    private void clearPlaylist() {
        showEmptyCardPlaylist();
        repository.clearPlaylist();
        adapter.clearAdapter();
    }

    private void fetchPlayListFiles() {
        subscription = Observable.zip(repository.getAudioFilePlaylist(),
                repository.getVideoFilePlaylist(),
                (audioFiles, videoFiles) -> {
                    List<MediaFile> mediaFiles = new ArrayList<>();
                    mediaFiles.addAll(audioFiles);
                    mediaFiles.addAll(videoFiles);
                    return mediaFiles;
                })
                .first()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(mediaFiles -> {
                    AppLog.DEBUG("playlist size: " + mediaFiles.size());
                    if (!mediaFiles.isEmpty()) {
                        adapter.refreshAdapter(mediaFiles);
                        hideEmptyCardPlaylist();
                    } else {
                        showEmptyCardPlaylist();
                        hideMenu();
                    }
                });
    }

    private void hideMenu() {
        menu.findItem(R.id.action_clear_playlist).setVisible(false);
    }


    private void showEmptyCardPlaylist() {
        emptyPlaylistCard.setVisibility(View.VISIBLE);
    }

    private void hideEmptyCardPlaylist() {
        emptyPlaylistCard.setVisibility(View.GONE);
    }


    private static class AudioTracksAdapter extends RecyclerView.Adapter<AudioTracksAdapter.ViewHolder> {

        private WeakReference<Activity> activity;
        private List<MediaFile> mediaFiles;

        public AudioTracksAdapter(Activity activity) {
            this.activity = new WeakReference<>(activity);
            this.mediaFiles = new ArrayList<>();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {

            TextView duration;
            TextView title;
            TextView filePath;
            ImageView cover;

            public ViewHolder(View v) {
                super(v);
                v.setOnClickListener(v1 -> startPlayerActivity(getAdapterPosition()));

                cover = (ImageView) v.findViewById(R.id.itemCover);
                duration = (TextView) v.findViewById(R.id.itemDuration);
                title = (TextView) v.findViewById(R.id.itemTitle);
                filePath = (TextView) v.findViewById(R.id.itemPath);
                filePath.setSelected(true);

            }
        }

        @Override
        public AudioTracksAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_audio_track, parent, false);

            return new AudioTracksAdapter.ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(AudioTracksAdapter.ViewHolder holder, int position) {

            MediaFile mediaFile = mediaFiles.get(position);
            if (mediaFile != null) {

                BitmapHelper.getInstance().loadTrackListArtwork(mediaFile, holder.cover);

                switch (mediaFile.getMediaType()) {
                    case VIDEO:
                        holder.duration.setText(Utils.getVideoFileTimeFormat(mediaFile.getLength()));
                        break;
                    case AUDIO:
                        holder.duration.setText(Utils.getDurationString(mediaFile.getLength()));
                        break;
                }
                holder.title.setText(mediaFile.getTitle());
                holder.filePath.setText(mediaFile.getFilePath());
            }
        }

        @Override
        public int getItemCount() {
            return mediaFiles.size();
        }

        public void refreshAdapter(List<MediaFile> newMediaFiles) {
            mediaFiles.addAll(newMediaFiles);
            notifyDataSetChanged();
        }

        public void clearAdapter() {
            mediaFiles.clear();
            notifyDataSetChanged();
        }

        private void startPlayerActivity(int position) {
            Activity act = activity.get();
            if (act != null) {
                MediaFile mediaFile = mediaFiles.get(position);
                if (mediaFile != null) {
                    switch (mediaFile.getMediaType()) {
                        case VIDEO:
                            Intent intent = new Intent(act, VideoExoPlayerActivity.class);
                            intent.putExtra(VideoExoPlayerActivity.URI_EXTRA, mediaFile.getFilePath());
                            intent.putExtra(VideoExoPlayerActivity.VIDEO_NAME_EXTRA, mediaFile.getFileName());
                            intent.setAction(VideoExoPlayerActivity.ACTION_VIEW_URI);
                            act.startActivity(intent);
                            break;
                        case AUDIO:
                            AudioPlayer audioPlayer = MediaApplication.getInstance().getAudioPlayer();
                            audioPlayer.setCurrentAudioFileAndPlay((AudioFile) mediaFile);
                            AudioPlayerActivity.startPlayerActivity(act);
                            break;
                    }
                }
            }
        }
    }
}
