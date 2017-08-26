package com.fesskiev.mediacenter.ui.wear;

import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.widget.Toolbar;
import android.util.TypedValue;
import android.view.ViewGroup;

import com.fesskiev.mediacenter.R;
import com.fesskiev.mediacenter.analytics.AnalyticsActivity;


public class WearActivity extends AnalyticsActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear);

        TypedValue typedValue = new TypedValue();
        getResources().getValue(R.dimen.activity_window_height, typedValue, true);
        float scaleValue = typedValue.getFloat();

        int height = (int) (getResources().getDisplayMetrics().heightPixels * scaleValue);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(getString(R.string.title_wear_activity));
            setSupportActionBar(toolbar);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.content, WearFragment.newInstance(),
                    WearFragment.class.getName());
            transaction.commit();
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
}
