package com.fesskiev.mediacenter.ui.audio.tracklist;

import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.fesskiev.mediacenter.MediaApplication;
import com.fesskiev.mediacenter.R;
import com.fesskiev.mediacenter.data.model.AudioFile;
import com.fesskiev.mediacenter.data.source.DataRepository;
import com.fesskiev.mediacenter.players.AudioPlayer;
import com.fesskiev.mediacenter.services.PlaybackService;
import com.fesskiev.mediacenter.ui.audio.player.AudioPlayerActivity;
import com.fesskiev.mediacenter.ui.audio.utils.CONTENT_TYPE;
import com.fesskiev.mediacenter.ui.audio.utils.Constants;
import com.fesskiev.mediacenter.utils.AppLog;
import com.fesskiev.mediacenter.utils.BitmapHelper;
import com.fesskiev.mediacenter.utils.RxUtils;
import com.fesskiev.mediacenter.utils.Utils;
import com.fesskiev.mediacenter.widgets.cards.SlidingCardView;
import com.fesskiev.mediacenter.widgets.dialogs.EditTrackDialog;
import com.fesskiev.mediacenter.widgets.recycleview.HidingScrollListener;
import com.fesskiev.mediacenter.widgets.recycleview.ScrollingLinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;


public class TrackListFragment extends Fragment {

    public static TrackListFragment newInstance(CONTENT_TYPE contentType, String contentValue) {
        TrackListFragment fragment = new TrackListFragment();
        Bundle args = new Bundle();
        args.putSerializable(Constants.EXTRA_CONTENT_TYPE, contentType);
        args.putString(Constants.EXTRA_CONTENT_TYPE_VALUE, contentValue);
        fragment.setArguments(args);
        return fragment;
    }

    private Subscription subscription;
    private DataRepository repository;
    private TrackListAdapter adapter;
    private AudioPlayer audioPlayer;
    private List<SlidingCardView> openCards;
    private CONTENT_TYPE contentType;
    private String contentValue;

    private boolean lastPlaying;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            contentType = (CONTENT_TYPE)
                    getArguments().getSerializable(Constants.EXTRA_CONTENT_TYPE);
            contentValue = getArguments().getString(Constants.EXTRA_CONTENT_TYPE_VALUE);
        }

        audioPlayer = MediaApplication.getInstance().getAudioPlayer();
        repository = MediaApplication.getInstance().getRepository();
        openCards = new ArrayList<>();

        EventBus.getDefault().register(this);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_track_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recyclerView = (RecyclerView) view.findViewById(R.id.recycleView);
        recyclerView.setLayoutManager(new ScrollingLinearLayoutManager(getActivity(),
                LinearLayoutManager.VERTICAL, false, 1000));
        adapter = new TrackListAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new HidingScrollListener() {
            @Override
            public void onHide() {

            }

            @Override
            public void onShow() {

            }

            @Override
            public void onItemPosition(int position) {
                closeOpenCards();
            }
        });

        fetchContentByType();
    }

    @Override
    public void onResume() {
        super.onResume();
        notifyTrackStateChanged();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RxUtils.unsubscribe(subscription);
        EventBus.getDefault().unregister(this);

    }


    private void notifyTrackStateChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackStateEvent(PlaybackService playbackState) {
        boolean playing = playbackState.isPlaying();
        if (lastPlaying != playing) {
            lastPlaying = playing;
            notifyTrackStateChanged();
        }

    }

    private void fetchContentByType() {
        Observable<List<AudioFile>> audioFilesObservable = null;

        switch (contentType) {
            case GENRE:
                audioFilesObservable = repository.getGenreTracks(contentValue);
                break;
            case FOLDERS:
                audioFilesObservable = repository.getFolderTracks(contentValue);
                break;
            case ARTIST:
                audioFilesObservable = repository.getArtistTracks(contentValue);
                break;
        }

        if (audioFilesObservable != null) {
            subscription = audioFilesObservable
                    .first()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(audioFiles -> {
                        AppLog.INFO("onNext:track list: " + audioFiles.size());
                        adapter.refreshAdapter(audioFiles);

                    });
        }
    }

    private void closeOpenCards() {
        if (!openCards.isEmpty()) {
            for (SlidingCardView cardView : openCards) {
                if (cardView.isOpen()) {
                    cardView.animateSlidingContainer(false);
                }
            }
        }
    }


    private class TrackListAdapter extends RecyclerView.Adapter<TrackListAdapter.ViewHolder> {

        private List<AudioFile> audioFiles;

        public TrackListAdapter() {
            this.audioFiles = new ArrayList<>();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {

            TextView duration;
            TextView title;
            TextView filePath;
            ImageView cover;
            ImageView playEq;

            public ViewHolder(final View v) {
                super(v);

                duration = (TextView) v.findViewById(R.id.itemDuration);
                title = (TextView) v.findViewById(R.id.itemTitle);
                filePath = (TextView) v.findViewById(R.id.filePath);
                cover = (ImageView) v.findViewById(R.id.itemCover);
                playEq = (ImageView) v.findViewById(R.id.playEq);

                ((SlidingCardView) v).
                        setOnSlidingCardListener(new SlidingCardView.OnSlidingCardListener() {
                            @Override
                            public void onDeleteClick() {
                                deleteFile(getAdapterPosition());
                            }

                            @Override
                            public void onEditClick() {
                                showEditDialog(getAdapterPosition());
                            }

                            @Override
                            public void onClick() {
                                startPlayerActivity(getAdapterPosition());
                            }

                            @Override
                            public void onPlaylistClick() {
                                addToPlaylist(getAdapterPosition());
                            }

                            @Override
                            public void onAnimateChanged(SlidingCardView cardView, boolean open) {
                                if (open) {
                                    openCards.add(cardView);
                                } else {
                                    openCards.remove(cardView);
                                }
                            }
                        });
            }
        }

        private void addToPlaylist(int position) {
            AudioFile audioFile = audioFiles.get(position);
            if (audioFile != null) {
                audioFile.inPlayList = true;
                repository.updateAudioFile(audioFile);
                Utils.showCustomSnackbar(getView(),
                        getContext().getApplicationContext(),
                        getString(R.string.add_to_playlist_text),
                        Snackbar.LENGTH_SHORT).show();
                closeOpenCards();
            }
        }

        private void startPlayerActivity(int position) {
            if (position != -1) {
                AudioFile audioFile = audioFiles.get(position);
                if (audioFile != null) {
                    if (audioFile.exists()) {
                        audioPlayer.getCurrentAudioFile()
                                .first()
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(selectedTrack -> {
                                    if (selectedTrack != null && selectedTrack.equals(audioFile)) {
                                        AudioPlayerActivity.startPlayerActivity(getActivity());
                                    } else {
                                        audioPlayer.setCurrentAudioFileAndPlay(audioFile);
                                        AudioPlayerActivity.startPlayerActivity(getActivity());
                                    }

                                });
                    } else {
                        Utils.showCustomSnackbar(getView(),
                                getContext(), getString(R.string.snackbar_file_not_exist),
                                Snackbar.LENGTH_LONG).show();
                    }
                }
            }
        }

        private void showEditDialog(final int position) {
            AudioFile audioFile = audioFiles.get(position);
            if (audioFile != null) {
                EditTrackDialog editTrackDialog = new EditTrackDialog(getActivity(), audioFile,
                        new EditTrackDialog.OnEditTrackChangedListener() {
                            @Override
                            public void onEditTrackChanged(AudioFile audioFile) {
                                adapter.updateItem(position, audioFile);
                            }

                            @Override
                            public void onEditTrackError() {
                            }
                        });
                editTrackDialog.show();
            }
        }

        private void deleteFile(final int position) {
            final AudioFile audioFile = audioFiles.get(position);
            AlertDialog.Builder builder =
                    new AlertDialog.Builder(getActivity(), R.style.AppCompatAlertDialogStyle);
            builder.setTitle(getString(R.string.dialog_delete_file_title));
            builder.setMessage(R.string.dialog_delete_file_message);
            builder.setPositiveButton(R.string.dialog_delete_file_ok,
                    (dialog, which) -> {
                        if (audioFile.filePath.delete()) {
                            Utils.showCustomSnackbar(getView(),
                                    getContext(),
                                    getString(R.string.shackbar_delete_file),
                                    Snackbar.LENGTH_LONG).show();

                            repository.deleteAudioFile(audioFile.getFilePath());
                            adapter.removeItem(position);

                        }
                    });
            builder.setNegativeButton(R.string.dialog_delete_file_cancel,
                    (dialog, which) -> dialog.cancel());
            builder.show();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_track_list, parent, false);

            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {

            AudioFile audioFile = audioFiles.get(position);
            if (audioFile != null) {

                holder.duration.setText(Utils.getDurationString(audioFile.length));
                holder.title.setText(audioFile.title);
                holder.filePath.setText(audioFile.filePath.getName());

                audioPlayer.getCurrentAudioFolder()
                        .first()
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(audioFolder -> {
                            BitmapHelper.getInstance().loadTrackListArtwork(audioFile, audioFolder, holder.cover);
                        });

                audioPlayer.getCurrentAudioFile()
                        .first()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(selectedTrack -> {
                            if (selectedTrack != null && selectedTrack.equals(audioFile) && lastPlaying) {
                                holder.playEq.setVisibility(View.VISIBLE);

                                AnimationDrawable animation = (AnimationDrawable) ContextCompat.
                                        getDrawable(getContext().getApplicationContext(), R.drawable.ic_equalizer);
                                holder.playEq.setImageDrawable(animation);
                                if (animation != null) {
                                    if (lastPlaying) {
                                        animation.start();
                                    } else {
                                        animation.stop();
                                    }
                                }
                            } else {
                                holder.playEq.setVisibility(View.INVISIBLE);
                            }

                        });
            }
        }

        @Override
        public int getItemCount() {
            return audioFiles.size();
        }

        public void refreshAdapter(List<AudioFile> receiverAudioFiles) {
            audioFiles.clear();
            audioFiles.addAll(receiverAudioFiles);
            notifyDataSetChanged();
        }

        public void removeItem(int position) {
            audioFiles.remove(position);
            notifyItemRemoved(position);
        }

        public void updateItem(int position, AudioFile audioFile) {
            audioFiles.set(position, audioFile);
            notifyItemChanged(position);
        }
    }
}