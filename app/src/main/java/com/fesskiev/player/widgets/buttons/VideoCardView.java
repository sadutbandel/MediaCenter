package com.fesskiev.player.widgets.buttons;


import android.content.Context;
import android.support.v7.widget.CardView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.fesskiev.player.R;

public class VideoCardView extends CardView {

    public interface OnVideoCardViewListener {

        void onPopupMenuButtonCall(View view);
    }

    private GestureDetector detector;
    private ImageView popupMenu;
    private ImageView frameView;
    private TextView description;
    private OnVideoCardViewListener listener;

    public VideoCardView(Context context) {
        super(context);
        init(context);
    }

    public VideoCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VideoCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.video_card_view, this, true);

        popupMenu = (ImageView) view.findViewById(R.id.popupMenu);
        frameView = (ImageView) view.findViewById(R.id.frameView);
        description = (TextView) view.findViewById(R.id.fileDescription);

        detector = new GestureDetector(getContext(), new GestureListener());
    }

    public void setOnVideoCardViewListener(OnVideoCardViewListener l) {
        this.listener = l;
    }

    public void setDescription(String text) {
        description.setText(text);
    }

    public ImageView getFrameView() {
        return frameView;
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            Log.d("test", "onSingleTapUp");
            if (isPointInsideView(e.getRawX(), e.getRawY(), popupMenu)) {
                if (listener != null) {
                    listener.onPopupMenuButtonCall(popupMenu);
                }
            }
            return true;
        }
    }

    private boolean isPointInsideView(float x, float y, View view) {
        int location[] = new int[2];
        view.getLocationOnScreen(location);
        int viewX = location[0];
        int viewY = location[1];
        return (x > viewX && x < (viewX + view.getWidth())) &&
                (y > viewY && y < (viewY + view.getHeight()));
    }


    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        Log.d("test", "dispatchTouchEvent");
        return detector.onTouchEvent(ev);
    }

}
