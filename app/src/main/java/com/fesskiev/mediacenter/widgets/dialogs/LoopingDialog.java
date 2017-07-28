package com.fesskiev.mediacenter.widgets.dialogs;


import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.fesskiev.mediacenter.R;
import com.fesskiev.mediacenter.widgets.seekbar.RangeSeekBar;

public class LoopingDialog extends DialogFragment {

    private static final String AUDIO_FILE_DURATION = "com.fesskiev.player.AUDIO_FILE_DURATION ";

    public static LoopingDialog newInstance(int duration) {
        LoopingDialog dialog = new LoopingDialog();
        Bundle args = new Bundle();
        args.putInt(AUDIO_FILE_DURATION, duration);
        dialog.setArguments(args);
        return dialog;
    }

    private int duration;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.CustomFragmentDialog);

        duration = getArguments().getInt(AUDIO_FILE_DURATION);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return getActivity().getLayoutInflater().inflate(R.layout.dialog_looping, null);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RangeSeekBar<Integer> rangeSeekBar = (RangeSeekBar) view.findViewById(R.id.rangeSeekBar);
        rangeSeekBar.setRangeValues(0, duration);

        rangeSeekBar.setCutType(0);
        rangeSeekBar.setOnRangeSeekBarChangeListener((bar, minValue, maxValue) -> {

        });

    }
}
