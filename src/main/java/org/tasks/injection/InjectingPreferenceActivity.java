package org.tasks.injection;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.tasks.R;
import org.tasks.analytics.Tracker;
import org.tasks.locale.LocaleUtils;
import org.tasks.preferences.AppCompatPreferenceActivity;
import org.tasks.themes.Theme;
import org.tasks.ui.MenuColorizer;

import javax.inject.Inject;

import timber.log.Timber;

public abstract class InjectingPreferenceActivity extends AppCompatPreferenceActivity implements InjectingActivity {

    private ActivityComponent activityComponent;

    protected Toolbar toolbar;

    @Inject Theme theme;
    @Inject Tracker tracker;

    public InjectingPreferenceActivity() {
        LocaleUtils.updateConfig(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        activityComponent = ((InjectingApplication) getApplication())
                .getComponent()
                .plus(new ActivityModule(this));
        inject(activityComponent);

        theme.applyThemeAndStatusBarColor(this);

        super.onCreate(savedInstanceState);

        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        View content = root.getChildAt(0);
        LinearLayout toolbarContainer = (LinearLayout) View.inflate(this, R.layout.activity_prefs, null);

        root.removeAllViews();
        toolbarContainer.addView(content);
        root.addView(toolbarContainer);

        toolbar = (Toolbar) toolbarContainer.findViewById(R.id.toolbar);
        try {
            ComponentName componentName = new ComponentName(this, getClass());
            ActivityInfo activityInfo = getPackageManager().getActivityInfo(componentName, 0);
            toolbar.setTitle(activityInfo.labelRes);
        } catch (Exception e) {
            Timber.e(e, e.getMessage());
            toolbar.setTitle(getTitle());
        }
        toolbar.setNavigationIcon(getResources().getDrawable(R.drawable.ic_arrow_back_24dp));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        MenuColorizer.colorToolbar(this, toolbar);
    }

    @Override
    public ActivityComponent getComponent() {
        return activityComponent;
    }

    @Override
    protected void onResume() {
        super.onResume();

        tracker.showScreen(getClass().getSimpleName());
    }

    protected void requires(int prefGroup, boolean passesCheck, int... resIds) {
        if (!passesCheck) {
            remove((PreferenceCategory) findPreference(getString(prefGroup)), resIds);
        }
    }

    protected void requires(boolean passesCheck, int... resIds) {
        if (!passesCheck) {
            remove(getPreferenceScreen(), resIds);
        }
    }

    private void remove(PreferenceGroup preferenceGroup, int[] resIds) {
        for (int resId : resIds) {
            preferenceGroup.removePreference(getPref(resId));
        }
    }

    protected Preference getPref(int resId) {
        return findPreference(getString(resId));
    }
}
