package org.irmacard.cardemu.preferences;

import android.os.Bundle;
import android.preference.PreferenceFragment;

import org.irmacard.cardemu.R;

public class ScreenshotPreferenceFragment extends PreferenceFragment {
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences_screenshots);
	}
}
