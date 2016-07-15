package org.irmacard.cardemu.preferences;

import android.preference.PreferenceFragment;
import org.irmacard.cardemu.R;

public class ScreenshotPreferenceFragment extends PreferenceFragment {
	@Override
	public void onResume() {
		super.onResume();

		addPreferencesFromResource(R.xml.preferences_screenshots);
	}
}
