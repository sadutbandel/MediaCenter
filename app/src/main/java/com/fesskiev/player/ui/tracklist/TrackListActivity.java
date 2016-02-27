package com.fesskiev.player.ui.tracklist;

import android.content.ComponentName;
import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.IntentCompat;
import android.support.v7.widget.Toolbar;
import android.view.View;

import com.fesskiev.player.MediaApplication;
import com.fesskiev.player.R;
import com.fesskiev.player.ui.MainActivity;
import com.fesskiev.player.ui.player.PlaybackActivity;

public class TrackListActivity extends PlaybackActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_song_list);
        if (savedInstanceState == null) {
            Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
            if (toolbar != null) {
                String folderName =
                        MediaApplication.getInstance().getAudioPlayer().currentAudioFolder.folderName;
                toolbar.setTitle(folderName);
                toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
                toolbar.setNavigationOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        navigateUpToFromChild(TrackListActivity.this,
                                IntentCompat.makeMainActivity(new ComponentName(TrackListActivity.this,
                                        MainActivity.class)));
                    }
                });

                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.content, TrackListFragment.newInstance(),
                        TrackListFragment.class.getName());
                transaction.commit();

            }
        }
    }
}