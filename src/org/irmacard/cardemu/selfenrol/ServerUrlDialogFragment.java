package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.Util;

import java.util.regex.Pattern;

public class ServerUrlDialogFragment extends DialogFragment {
	private ServerUrlDialogListener listener;

	public interface ServerUrlDialogListener {
		void onServerUrlEntry(String url);
	}

	public String getUrlFromSettings() {
		return getActivity().getSharedPreferences(Passport.SETTINGS, 0)
				.getString("enroll_server_url", getString(R.string.enroll_default_url));
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		// Simple view containing the actual input field
		View v = getActivity().getLayoutInflater().inflate(R.layout.enroll_url_dialog, null);

		// Set the URL field to the appropriate value
		final EditText urlfield = (EditText)v.findViewById(R.id.enroll_url_field);
		urlfield.setText(getUrlFromSettings());

		// Build the dialog
		final AlertDialog urldialog = new AlertDialog.Builder(getActivity())
				.setTitle(R.string.enroll_url_dialog_title)
				.setView(v)
				.setPositiveButton(android.R.string.ok, new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						String newUrl = urlfield.getText().toString();
						getActivity().getSharedPreferences(Passport.SETTINGS, 0)
								.edit().putString("enroll_server_url", newUrl).apply();
						listener.onServerUrlEntry(newUrl);
					}
				}).setNeutralButton(R.string.default_string, null)
				.setNegativeButton(android.R.string.cancel, new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						// Reset the URL field to the last known valid value
						urlfield.setText(getUrlFromSettings());
						listener.onServerUrlCancel();
					}
				})
				.create();


		urldialog.setOnShowListener(new DialogInterface.OnShowListener() {
			@Override
			public void onShow(DialogInterface dialog) {
				// By overriding the neutral button's onClick event in this onShow listener,
				// we prevent the dialog from closing when the default button is pressed.
				Button defaultbutton = urldialog.getButton(DialogInterface.BUTTON_NEUTRAL);
				defaultbutton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						// Just set the text in the TextView here. No need to save it, the OK button handler will do
						// that
						String newUrl = getString(R.string.enroll_default_url);
						urlfield.setText(newUrl);
						// Move cursor to end of field
						urlfield.setSelection(urlfield.getText().length());
					}
				});

				// Move cursor to end of field
				urlfield.setSelection(urlfield.getText().length());
			}
		});

		// If the text from the input field changes to something that we do not consider valid
		// (i.e., it is not a valid IP or domain name), we disable the OK button
		urlfield.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable s) {
				Button okbutton = urldialog.getButton(DialogInterface.BUTTON_POSITIVE);
				okbutton.setEnabled(Util.isValidURL(s.toString()));
			}
		});

		return urldialog;
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		// Verify that the host activity implements the callback interface
		try {
			// Set our listener so we can send events to the host
			listener = (ServerUrlDialogListener) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException(activity.toString() + " must implement ServerUrlDialogListener");
		}
	}
}
