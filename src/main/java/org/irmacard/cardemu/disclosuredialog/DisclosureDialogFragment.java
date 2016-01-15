/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.cardemu.disclosuredialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import org.irmacard.cardemu.R;
import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.DisclosureProofRequest;

/**
 * DialogFragment for asking permission of a user to disclose specified attributes, and allowing him to choose which
 * ones. It takes a {@link DisclosureProofRequest}, and in the onDiscloseOK method that the caller should implement,
 * the same request is returned but with an attribute selected for each disjunction in the request.
 */
public class DisclosureDialogFragment extends DialogFragment {
	private DisclosureProofRequest request;
	private static DisclosureDialogListener listener;

	public interface DisclosureDialogListener {
		void onDiscloseOK(DisclosureProofRequest request);
		void onDiscloseCancel();
	}

	/**
	 * Constructs and returns a new DisclosureDialogFragment. Users must implement the DisclosureDialogListener
	 * interface.
	 */
	public static DisclosureDialogFragment newInstance(DisclosureProofRequest request, DisclosureDialogListener listener) {
		DisclosureDialogFragment.listener = listener;
		DisclosureDialogFragment dialog = new DisclosureDialogFragment();

		Bundle args = new Bundle();
		args.putSerializable("request", request);
		dialog.setArguments(args);

		return dialog;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		request = (DisclosureProofRequest) getArguments().getSerializable("request");
	}

	public static void populate(Activity activity, View view, final DisclosureProofRequest request) {
		LayoutInflater inflater = activity.getLayoutInflater();
		Resources resources = activity.getResources();
		LinearLayout list = (LinearLayout) view.findViewById(R.id.attributes_container);

		if (list == null)
			throw new IllegalArgumentException("Can't populate view: of incorrect type" +
					" (should be R.layout.dialog_disclosure)");

		// When a user chooses an item in the spinner, this listener notifies the adapter of the spinner which item
		// was selected
		AdapterView.OnItemSelectedListener spinnerListener = new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				AttributesPickerAdapter adapter = (AttributesPickerAdapter) parent.getAdapter();
				AttributeDisjunction disjunction = adapter.setSelected(position);
				request.getContent().set(adapter.getIndex(), disjunction);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {
				((AttributesPickerAdapter) parent.getAdapter()).setSelected(-1);
			}
		};

		for (int i = 0; i < request.getContent().size(); ++i) {
			AttributeDisjunction disjunction = request.getContent().get(i);

			View attributeView = inflater.inflate(R.layout.attribute_picker, list, false);
			TextView name = (TextView) attributeView.findViewById(R.id.detail_attribute_name);
			name.setText(disjunction.getLabel());

			Spinner spinner = (Spinner) attributeView.findViewById(R.id.attribute_spinner);
			spinner.setAdapter(new AttributesPickerAdapter(activity, disjunction, i));

			spinner.setOnItemSelectedListener(spinnerListener);

			list.addView(attributeView);
		}

		String question1 = resources
				.getQuantityString(R.plurals.disclose_question_1, request.getContent().size());
		((TextView) view.findViewById(R.id.disclosure_question_1)).setText(question1);
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		LayoutInflater inflater = getActivity().getLayoutInflater();
		View view = inflater.inflate(R.layout.dialog_disclosure, null); // TODO getActivity(), false?

		populate(getActivity(), view, request);

		final AlertDialog d = new AlertDialog.Builder(getActivity())
				.setTitle("Disclose attributes?")
				.setView(view)
				.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						listener.onDiscloseOK(request);
					}
				})
				.setNegativeButton("No", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						listener.onDiscloseCancel();
					}
				})
				.setNeutralButton("More Information", null)
				.create();

		// We set the listener for the neutral ("More Information") button instead of above, because if we set it
		// above then the dialog is dismissed afterwards and we don't want that.
		d.setOnShowListener(new DialogInterface.OnShowListener() {
			@Override
			public void onShow(DialogInterface dialog) {
				d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						Intent intent = new Intent(getActivity(), DisclosureInformationActivity.class);
						intent.putExtra("request", request);
						startActivity(intent);
					}
				});
			}
		});

		return d;
	}
}
