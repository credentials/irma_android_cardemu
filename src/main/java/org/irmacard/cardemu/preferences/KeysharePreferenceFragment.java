package org.irmacard.cardemu.preferences;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.v4.app.NavUtils;

import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.R;

public class KeysharePreferenceFragment extends PreferenceFragment {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean isDistributed = CredentialManager.isDistributed();
        PreferenceManager.getDefaultSharedPreferences(getActivity())
                .edit().putBoolean("is_distributed", isDistributed).apply();

        addPreferencesFromResource(R.xml.preferences_keyshare);

        final Preference p = findPreference("is_distributed");
        p.setEnabled(isDistributed);
        p.setTitle(isDistributed ? getString(R.string.linked_to_keyshare) : getString(R.string.not_linked_to_keyshare));
        p.setSummary(CredentialManager.getKeyshareServer());

        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference preference, Object newValue) {
                new AlertDialog.Builder(KeysharePreferenceFragment.this.getActivity())
                        .setTitle(R.string.confirm_delete_all_title)
                        .setMessage(R.string.unlink_keyshare_question)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                CredentialManager.unlinkFromKeyshareServer();
                                NavUtils.navigateUpFromSameTask(getActivity());
                            }
                        })
                        .show();

                return false;
            }
        });
    }
}
