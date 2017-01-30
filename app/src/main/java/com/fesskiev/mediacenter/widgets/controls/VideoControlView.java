package com.fesskiev.mediacenter.widgets.controls;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Animatable;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewPropertyAnimatorListener;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckedTextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.fesskiev.mediacenter.R;
import com.fesskiev.mediacenter.utils.AnimationUtils;
import com.fesskiev.mediacenter.widgets.buttons.PlayPauseButton;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.RandomTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.Arrays;
import java.util.Locale;

import static com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL;
import static com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
import static com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT;
import static com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH;

public class VideoControlView extends FrameLayout {


    public interface OnVideoPlayerControlListener {

        void playPauseButtonClick(boolean isPlaying);

        void seekVideo(int progress);

        void nextVideo();

        void previousVideo();

        void resizeModeChanged(int mode);
    }

    private OnVideoPlayerControlListener listener;
    private View trackSelectionPanel;
    private View videoControlPanel;
    private PlayPauseButton playPauseButton;
    private SeekBar seekVideo;
    private TextView videoTimeCount;
    private TextView videoTimeTotal;
    private TextView resizeModeState;
    private TextView videoName;

    private TextView audioTrackView;
    private TextView videoTrackView;
    private TextView subTrackView;

    private boolean isPlaying;
    private int resizeMode;
    private boolean showPanel;
    private boolean animatePanel;
    private boolean showControl;
    private boolean animateControl;

    private static final TrackSelection.Factory FIXED_FACTORY = new FixedTrackSelection.Factory();
    private static final TrackSelection.Factory RANDOM_FACTORY = new RandomTrackSelection.Factory();

    private MappingTrackSelector selector;
    private TrackSelection.Factory adaptiveVideoTrackSelectionFactory;
    private MappingTrackSelector.SelectionOverride override;
    private MappingTrackSelector.MappedTrackInfo trackInfo;
    private int rendererIndex;
    private TrackGroupArray trackGroups;
    private boolean[] trackGroupsAdaptive;
    private boolean isDisabled;

    private CheckedTextView disableView;
    private CheckedTextView defaultView;
    private CheckedTextView enableRandomAdaptationView;
    private CheckedTextView[][] trackViews;


    public VideoControlView(Context context) {
        super(context);
        init(context);
    }

    public VideoControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VideoControlView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.video_player_control, this, true);

        showControl = true;
        showPanel = true;
        videoControlPanel = view.findViewById(R.id.videoControlPanel);
        videoControlPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {

                    @Override
                    public void onGlobalLayout() {

                        hidePanel(100);
                        getViewTreeObserver().removeGlobalOnLayoutListener(this);
                    }
                });

        trackSelectionPanel = view.findViewById(R.id.trackSelectionPanel);

        videoTimeCount = (TextView) view.findViewById(R.id.videoTimeCount);
        videoTimeTotal = (TextView) view.findViewById(R.id.videoTimeTotal);

        view.findViewById(R.id.resizeModeState).setOnClickListener(v -> changeResizeMode());

        audioTrackView = (TextView) view.findViewById(R.id.audioTrackButton);
        audioTrackView.setOnClickListener(v -> setAudioTrack((int) v.getTag()));

        videoTrackView = (TextView) view.findViewById(R.id.videoTrackButton);
        videoTrackView.setOnClickListener(v -> setVideoTrack((int) v.getTag()));

        subTrackView = (TextView) view.findViewById(R.id.subTrackButton);
        subTrackView.setOnClickListener(v -> setSubTrack((int) v.getTag()));

        resizeModeState = (TextView) view.findViewById(R.id.resizeModeState);
        videoName = (TextView) view.findViewById(R.id.videoName);


        ImageView settingsButton = (ImageView) view.findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(v -> {
            AnimationUtils.getInstance().rotateAnimation(settingsButton);
            togglePanel();
        });

        ImageView nextVideo = (ImageView) findViewById(R.id.nextVideo);
        nextVideo.setOnClickListener(v -> {
            ((Animatable) nextVideo.getDrawable()).start();
            if (listener != null) {
                listener.nextVideo();
            }
        });

        ImageView previousVideo = (ImageView) findViewById(R.id.previousVideo);
        previousVideo.setOnClickListener(v -> {
            ((Animatable) previousVideo.getDrawable()).start();
            if (listener != null) {
                listener.previousVideo();
            }
        });
        playPauseButton = (PlayPauseButton) view.findViewById(R.id.playPauseButton);
        playPauseButton.setColor(ContextCompat.getColor(context, R.color.primary));
        playPauseButton.setOnClickListener(v -> {
            isPlaying = !isPlaying;
            playPauseButton.setPlay(isPlaying);
            if (listener != null) {
                listener.playPauseButtonClick(isPlaying);
            }

        });

        seekVideo = (SeekBar) findViewById(R.id.seekVideo);
        seekVideo.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int progress;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                this.progress = progress;

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {


            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (listener != null) {
                    listener.seekVideo(progress);
                }
            }
        });
    }

    private void setSubTrack(int index) {
        rendererIndex = index;
        setTrack();
    }

    private void setVideoTrack(int index) {
        rendererIndex = index;
        setTrack();
    }

    private void setAudioTrack(int index) {
        rendererIndex = index;
        setTrack();
    }

    private void changeResizeMode() {
        switch (resizeMode) {
            case RESIZE_MODE_FIT:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fill));
                resizeMode = RESIZE_MODE_FILL;
                break;
            case RESIZE_MODE_FILL:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fixed_width));
                resizeMode = RESIZE_MODE_FIXED_WIDTH;
                break;
            case RESIZE_MODE_FIXED_HEIGHT:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fit));
                resizeMode = RESIZE_MODE_FIT;
                break;
            case RESIZE_MODE_FIXED_WIDTH:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fixed_height));
                resizeMode = RESIZE_MODE_FIXED_HEIGHT;
                break;

        }

        if (listener != null) {
            listener.resizeModeChanged(resizeMode);
        }
    }

    public void setResizeModeState(@AspectRatioFrameLayout.ResizeMode int resizeMode) {
        switch (resizeMode) {
            case RESIZE_MODE_FIT:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fit));
                this.resizeMode = RESIZE_MODE_FIT;
                break;
            case RESIZE_MODE_FILL:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fill));
                this.resizeMode = RESIZE_MODE_FILL;
                break;
            case RESIZE_MODE_FIXED_HEIGHT:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fixed_height));
                this.resizeMode = RESIZE_MODE_FIXED_HEIGHT;
                break;
            case RESIZE_MODE_FIXED_WIDTH:
                resizeModeState.setText(getResources().getString(R.string.resize_mode_fixed_width));
                this.resizeMode = RESIZE_MODE_FIXED_WIDTH;
                break;
        }
    }


    public void setOnVideoPlayerControlListener(OnVideoPlayerControlListener l) {
        this.listener = l;
    }

    public void setVideoTimeTotal(String time) {
        videoTimeTotal.setText(time);
    }

    public void setVideoTimeCount(String time) {
        videoTimeCount.setText(time);
    }

    public void setProgress(int progress) {
        seekVideo.setProgress(progress);
    }

    public void setVideoName(String name) {
        videoName.setText(name);
    }

    public void setPlay(boolean play) {
        this.isPlaying = play;
        playPauseButton.setPlay(play);
    }

    public void resetIndicators() {
        videoTimeTotal.setText("0:00");
        videoTimeCount.setText("0:00");
        seekVideo.setProgress(0);
    }


    public void setVideoTrackInfo(SimpleExoPlayer player, MappingTrackSelector selector,
                                  TrackSelection.Factory adaptiveVideoTrackSelectionFactory) {
        this.selector = selector;
        this.adaptiveVideoTrackSelectionFactory = adaptiveVideoTrackSelectionFactory;

        trackInfo = selector.getCurrentMappedTrackInfo();
        if (trackInfo == null) {
            return;
        }
        for (int i = 0; i < trackInfo.length; i++) {
            TrackGroupArray trackGroups = trackInfo.getTrackGroups(i);
            if (trackGroups.length != 0) {
                switch (player.getRendererType(i)) {
                    case C.TRACK_TYPE_AUDIO:
                        audioTrackView.setVisibility(VISIBLE);
                        audioTrackView.setTag(i);
                        break;
                    case C.TRACK_TYPE_VIDEO:
                        videoTrackView.setVisibility(VISIBLE);
                        videoTrackView.setTag(i);
                        break;
                    case C.TRACK_TYPE_TEXT:
                        subTrackView.setVisibility(VISIBLE);
                        subTrackView.setTag(i);
                        break;
                    default:
                }
            }
        }
    }


    private OnClickListener onClickListener = this::handleTrackClick;

    private void handleTrackClick(View view) {
        if (view == disableView) {
            isDisabled = true;
            override = null;
        } else if (view == defaultView) {
            isDisabled = false;
            override = null;
        } else if (view == enableRandomAdaptationView) {
            setOverride(override.groupIndex, override.tracks, !enableRandomAdaptationView.isChecked());
        } else {
            isDisabled = false;
            @SuppressWarnings("unchecked")
            Pair<Integer, Integer> tag = (Pair<Integer, Integer>) view.getTag();
            int groupIndex = tag.first;
            int trackIndex = tag.second;
            if (!trackGroupsAdaptive[groupIndex] || override == null
                    || override.groupIndex != groupIndex) {
                override = new MappingTrackSelector.SelectionOverride(FIXED_FACTORY, groupIndex, trackIndex);
            } else {
                // The group being modified is adaptive and we already have a non-null override.
                boolean isEnabled = ((CheckedTextView) view).isChecked();
                int overrideLength = override.length;
                if (isEnabled) {
                    // Remove the track from the override.
                    if (overrideLength == 1) {
                        // The last track is being removed, so the override becomes empty.
                        override = null;
                        isDisabled = true;
                    } else {
                        setOverride(groupIndex, getTracksRemoving(override, trackIndex),
                                enableRandomAdaptationView.isChecked());
                    }
                } else {
                    // Add the track to the override.
                    setOverride(groupIndex, getTracksAdding(override, trackIndex),
                            enableRandomAdaptationView.isChecked());
                }
            }
        }
        // Update the views with the new state.
        updateViews();
    }

    private void setTrack() {
        Log.e("test_", "setTrack: " + rendererIndex);

        MappingTrackSelector.MappedTrackInfo trackInfo = selector.getCurrentMappedTrackInfo();

        trackGroups = trackInfo.getTrackGroups(rendererIndex);
        trackGroupsAdaptive = new boolean[trackGroups.length];
        for (int i = 0; i < trackGroups.length; i++) {
            trackGroupsAdaptive[i] = adaptiveVideoTrackSelectionFactory != null
                    && trackInfo.getAdaptiveSupport(rendererIndex, i, false)
                    != RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
                    && trackGroups.get(i).length > 1;
        }
        isDisabled = selector.getRendererDisabled(rendererIndex);
        override = selector.getSelectionOverride(rendererIndex, trackGroups);

        buildView(getContext());

    }

    @SuppressLint("InflateParams")
    private void buildView(Context context) {

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        ViewGroup root = (ViewGroup) trackSelectionPanel.findViewById(R.id.trackSelectionRoot);
        root.removeAllViews();

        TypedArray attributeArray = context.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        int selectableItemBackgroundResourceId = attributeArray.getResourceId(0, 0);
        attributeArray.recycle();

        // View for disabling the renderer.
        disableView = (CheckedTextView) inflater.inflate(
                R.layout.item_video_track_single_layout, root, false);
        disableView.setBackgroundResource(selectableItemBackgroundResourceId);
        disableView.setText(getResources().getString(R.string.video_track_disable));
        disableView.setFocusable(true);
        disableView.setOnClickListener(onClickListener);
        root.addView(disableView);

        // View for clearing the override to allow the selector to use its default selection logic.
        defaultView = (CheckedTextView) inflater.inflate(R.layout.item_video_track_single_layout, root, false);
        defaultView.setBackgroundResource(selectableItemBackgroundResourceId);
        defaultView.setText(getResources().getString(R.string.video_track_default));
        defaultView.setFocusable(true);
        defaultView.setOnClickListener(onClickListener);
        root.addView(inflater.inflate(R.layout.item_video_list_divider, root, false));
        root.addView(defaultView);

        // Per-track views.
        boolean haveSupportedTracks = false;
        boolean haveAdaptiveTracks = false;
        trackViews = new CheckedTextView[trackGroups.length][];
        for (int groupIndex = 0; groupIndex < trackGroups.length; groupIndex++) {
            TrackGroup group = trackGroups.get(groupIndex);
            boolean groupIsAdaptive = trackGroupsAdaptive[groupIndex];
            haveAdaptiveTracks |= groupIsAdaptive;
            trackViews[groupIndex] = new CheckedTextView[group.length];
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (trackIndex == 0) {
                    root.addView(inflater.inflate(R.layout.item_video_list_divider, root, false));
                }
                int trackViewLayoutId = groupIsAdaptive ? R.layout.item_video_track_multiple_layout
                        : R.layout.item_video_track_single_layout;
                CheckedTextView trackView = (CheckedTextView) inflater.inflate(
                        trackViewLayoutId, root, false);
                trackView.setBackgroundResource(selectableItemBackgroundResourceId);
                trackView.setText(buildTrackName(group.getFormat(trackIndex)));
                if (trackInfo.getTrackFormatSupport(rendererIndex, groupIndex, trackIndex)
                        == RendererCapabilities.FORMAT_HANDLED) {
                    trackView.setFocusable(true);
                    trackView.setTag(Pair.create(groupIndex, trackIndex));
                    trackView.setOnClickListener(onClickListener);
                    haveSupportedTracks = true;
                } else {
                    trackView.setFocusable(false);
                    trackView.setEnabled(false);
                }
                trackViews[groupIndex][trackIndex] = trackView;
                root.addView(trackView);
            }
        }

        if (!haveSupportedTracks) {
            // Indicate that the default selection will be nothing.
            defaultView.setText(getResources().getString(R.string.video_track_default_none));
        } else if (haveAdaptiveTracks) {
            // View for using random adaptation.
            enableRandomAdaptationView = (CheckedTextView) inflater.inflate(
                    R.layout.item_video_track_multiple_layout, root, false);
            enableRandomAdaptationView.setBackgroundResource(selectableItemBackgroundResourceId);
            enableRandomAdaptationView.setText(getResources().getString(R.string.video_track_adaptation));
            enableRandomAdaptationView.setOnClickListener(onClickListener);
            root.addView(inflater.inflate(R.layout.item_video_list_divider, root, false));
            root.addView(enableRandomAdaptationView);
        }

        root.addView(inflater.inflate(R.layout.item_video_list_divider, root, false));

        View buttonRoot = inflater.inflate(R.layout.item_video_track_select_button, root, false);
        buttonRoot.findViewById(R.id.buttonSelectTrack).setOnClickListener(v -> selectTrack());
        root.addView(buttonRoot);

        updateViews();
    }

    private void selectTrack() {
        selector.setRendererDisabled(rendererIndex, isDisabled);
        if (override != null) {
            selector.setSelectionOverride(rendererIndex, trackGroups, override);
        } else {
            selector.clearSelectionOverrides(rendererIndex);
        }
    }

    private void updateViews() {
        disableView.setChecked(isDisabled);
        defaultView.setChecked(!isDisabled && override == null);
        for (int i = 0; i < trackViews.length; i++) {
            for (int j = 0; j < trackViews[i].length; j++) {
                trackViews[i][j].setChecked(override != null && override.groupIndex == i
                        && override.containsTrack(j));
            }
        }
        if (enableRandomAdaptationView != null) {
            boolean enableView = !isDisabled && override != null && override.length > 1;
            enableRandomAdaptationView.setEnabled(enableView);
            enableRandomAdaptationView.setFocusable(enableView);
            if (enableView) {
                enableRandomAdaptationView.setChecked(!isDisabled
                        && override.factory instanceof RandomTrackSelection.Factory);
            }
        }
    }

    private void setOverride(int group, int[] tracks, boolean enableRandomAdaptation) {
        TrackSelection.Factory factory = tracks.length == 1 ? FIXED_FACTORY
                : (enableRandomAdaptation ? RANDOM_FACTORY : adaptiveVideoTrackSelectionFactory);
        override = new MappingTrackSelector.SelectionOverride(factory, group, tracks);
    }

    private static int[] getTracksRemoving(MappingTrackSelector.SelectionOverride override, int removedTrack) {
        int[] tracks = new int[override.length - 1];
        int trackCount = 0;
        for (int i = 0; i < tracks.length + 1; i++) {
            int track = override.tracks[i];
            if (track != removedTrack) {
                tracks[trackCount++] = track;
            }
        }
        return tracks;
    }

    private static int[] getTracksAdding(MappingTrackSelector.SelectionOverride override, int addedTrack) {
        int[] tracks = override.tracks;
        tracks = Arrays.copyOf(tracks, tracks.length + 1);
        tracks[tracks.length - 1] = addedTrack;
        return tracks;
    }

    private static String buildTrackName(Format format) {
        String trackName;
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            trackName = joinWithSeparator(joinWithSeparator(buildResolutionString(format),
                    buildBitrateString(format)), buildTrackIdString(format));
        } else if (MimeTypes.isAudio(format.sampleMimeType)) {
            trackName = joinWithSeparator(joinWithSeparator(joinWithSeparator(buildLanguageString(format),
                    buildAudioPropertyString(format)), buildBitrateString(format)),
                    buildTrackIdString(format));
        } else {
            trackName = joinWithSeparator(joinWithSeparator(buildLanguageString(format),
                    buildBitrateString(format)), buildTrackIdString(format));
        }
        return trackName.length() == 0 ? "unknown" : trackName;
    }

    private static String buildResolutionString(Format format) {
        return format.width == Format.NO_VALUE || format.height == Format.NO_VALUE
                ? "" : format.width + "x" + format.height;
    }

    private static String buildAudioPropertyString(Format format) {
        return format.channelCount == Format.NO_VALUE || format.sampleRate == Format.NO_VALUE
                ? "" : format.channelCount + "ch, " + format.sampleRate + "Hz";
    }

    private static String buildLanguageString(Format format) {
        return TextUtils.isEmpty(format.language) || "und".equals(format.language) ? ""
                : format.language;
    }

    private static String buildBitrateString(Format format) {
        return format.bitrate == Format.NO_VALUE ? ""
                : String.format(Locale.US, "%.2fMbit", format.bitrate / 1000000f);
    }

    private static String joinWithSeparator(String first, String second) {
        return first.length() == 0 ? second : (second.length() == 0 ? first : first + ", " + second);
    }

    private static String buildTrackIdString(Format format) {
        return format.id == null ? "" : ("id:" + format.id);
    }


    private void hidePanel(int duration) {
        if (!animatePanel) {
            animatePanel = true;
            ViewCompat.animate(videoControlPanel)
                    .translationY(-videoControlPanel.getHeight())
                    .setDuration(duration)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .setListener(new ViewPropertyAnimatorListener() {
                        @Override
                        public void onAnimationStart(View view) {
                        }

                        @Override
                        public void onAnimationEnd(View view) {
                            showPanel = false;
                            animatePanel = false;
                        }

                        @Override
                        public void onAnimationCancel(View view) {

                        }
                    }).start();
        }

    }

    private void showPanel() {
        if (!animatePanel) {
            animatePanel = true;
            ViewCompat.animate(videoControlPanel)
                    .translationY(0)
                    .setDuration(800)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .setListener(new ViewPropertyAnimatorListener() {
                        @Override
                        public void onAnimationStart(View view) {

                        }

                        @Override
                        public void onAnimationEnd(View view) {
                            showPanel = true;
                            animatePanel = false;
                        }

                        @Override
                        public void onAnimationCancel(View view) {

                        }
                    }).start();
        }
    }

    private void togglePanel() {
        if (showPanel) {
            hidePanel(800);
        } else {
            showPanel();
        }
    }

    public void toggleControl() {
        if (showControl) {
            hideControl();
        } else {
            showControl();
        }
    }

    private void hideControl() {
        if (!animateControl) {
            animateControl = true;
            ViewCompat.animate(this)
                    .alpha(0f)
                    .setDuration(800)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .setListener(new ViewPropertyAnimatorListener() {
                        @Override
                        public void onAnimationStart(View view) {
                        }

                        @Override
                        public void onAnimationEnd(View view) {
                            showControl = false;
                            animateControl = false;
                        }

                        @Override
                        public void onAnimationCancel(View view) {

                        }
                    }).start();
        }
    }

    private void showControl() {
        if (!animateControl) {
            animateControl = true;
            ViewCompat.animate(this)
                    .alpha(0.9f)
                    .setDuration(800)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .setListener(new ViewPropertyAnimatorListener() {
                        @Override
                        public void onAnimationStart(View view) {
                        }

                        @Override
                        public void onAnimationEnd(View view) {
                            showControl = true;
                            animateControl = false;
                        }

                        @Override
                        public void onAnimationCancel(View view) {

                        }
                    }).start();
        }
    }
}
