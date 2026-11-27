package dev.lumencraft.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import dev.lumencraft.R;

public final class Appearance {
    private Appearance() {}

    public static int mode(Context base) {
        return base.getSharedPreferences("library", Context.MODE_PRIVATE).getInt("appearance", 0);
    }

    public static Context attach(Context base) {
        int mode = mode(base);
        if (mode == 0) return base;
        Configuration override = new Configuration();
        override.uiMode = (base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == 2 ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(override);
    }

    public static boolean isDark(Context context) {
        int mode = mode(context);
        if (mode != 0) return mode == 2;
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void apply(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.LumenDark : R.style.LumenLight);
    }

    public static boolean motion(Context context) {
        ActivityManager memory = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (!context.getSharedPreferences("library", Context.MODE_PRIVATE).getBoolean("motion", true)
                || memory.isLowRamDevice() || power.isPowerSaveMode()) return false;
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }
}
