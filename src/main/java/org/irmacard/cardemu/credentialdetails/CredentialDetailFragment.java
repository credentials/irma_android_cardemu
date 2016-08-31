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

package org.irmacard.cardemu.credentialdetails;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.irmacard.cardemu.R;
import org.irmacard.cardemu.store.AndroidFileReader;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.IssuerDescription;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * A fragment representing a single Credential detail screen. This fragment is
 * either contained in a CredentialListActivity in two-pane mode (on
 * tablets) or a {@link CredentialDetailActivity} on handsets.
 */
public class CredentialDetailFragment extends Fragment {
	/**
	 * The fragment argument representing the item ID that this fragment
	 * represents.
	 */
	public static final String ATTRIBUTES = "attributes";
	public static final String HASHCODE = "hashcode";
	
	public interface Callbacks {
		/**
		 * Callback when credential has to be deleted
		 */
		void onDeleteCredential(int hashCode);
	}

	private Attributes attributes;
	private CredentialDescription cd;
	private int hashCode;
	private AndroidFileReader fileReader;

	private LayoutInflater inflater;
	private CredentialDetailFragment.Callbacks mCallbacks;

	/**
	 * Mandatory empty constructor for the fragment manager to instantiate the
	 * fragment (e.g. upon screen orientation changes).
	 */
	public CredentialDetailFragment() {
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (getArguments().containsKey(ATTRIBUTES)) {
			hashCode = getArguments().getInt(HASHCODE);
			attributes = (Attributes) getArguments().getSerializable(ATTRIBUTES);
			if (attributes == null)
				throw new NullPointerException("attributes missing from arguments");
			cd = attributes.getCredentialDescription();
		}
		
		fileReader = new AndroidFileReader(this.getActivity());
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_credential_detail,
				container, false);

		this.inflater = inflater;

		return rootView;
	}

	public void onViewCreated(View view, Bundle savedInstanceState) {
		LinearLayout list = (LinearLayout) view
				.findViewById(R.id.detail_attribute_list);

		TextView issuerName = (TextView) view.findViewById(R.id.detail_issuer_description_name);
		TextView issuerAddress = (TextView) view.findViewById(R.id.detail_issuer_description_address);
		TextView issuerEMail = (TextView) view.findViewById(R.id.detail_issuer_description_email);
		TextView credentialDescription = (TextView) view.findViewById(R.id.detail_credential_desc_text);
		TextView validityValue = (TextView) view.findViewById(R.id.detail_validity_value);
		TextView validityRemaining = (TextView) view.findViewById(R.id.detail_validity_remaining);
		TextView schemeManagerName = (TextView) view.findViewById(R.id.detail_schememanager_text);
		ImageView issuerLogo = (ImageView) view.findViewById(R.id.detail_issuer_logo);
		Button deleteButton = (Button) view.findViewById(R.id.detail_delete_button);

		IssuerDescription issuer = cd.getIssuerDescription();
		issuerName.setText(issuer.getName());
		issuerAddress.setText(issuer.getContactAddress());
		issuerEMail.setText(issuer.getContactEMail());

		schemeManagerName.setText(issuer.getIdentifier().getSchemeManager().getHumanReadableName());

		// Add the attributes
		AttributesRenderer renderer = new AttributesRenderer(getActivity().getBaseContext(), inflater);
		renderer.render(attributes, list, false, null);

		// Display expiry
		if (!attributes.isExpired()) {
			DateFormat sdf = SimpleDateFormat.getDateInstance(DateFormat.LONG);
			Date expirydate = attributes.getExpiryDate();
			validityValue.setText(sdf.format(expirydate));

			int deltaDays = (int) ((expirydate.getTime() - Calendar
					.getInstance().getTime().getTime())
					/ (1000 * 60 * 60 * 24));
			// FIXME: text should be from resources
			validityRemaining.setText(getString(R.string.validity_days_remaining, deltaDays));

		} else {
			// Credential has expired
			validityValue.setText(R.string.credential_no_longer_valid);
			validityValue.setTextColor(ContextCompat.getColor(getContext(), R.color.irmared));
			validityRemaining.setText("");
		}
		
		credentialDescription.setText(cd.getDescription());
		
		// Setting logo of issuer
		Bitmap logo = fileReader.getIssuerLogo(cd.getIssuerDescription());

		if(logo != null) {
			issuerLogo.setImageBitmap(logo);
		}

		// On delete button clicked
		deleteButton.setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v){
		        clickedDeleteButton();
		    }
		});
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);

		// Activities containing this fragment must implement its callbacks.
		if (!(context instanceof Callbacks)) {
			throw new IllegalStateException(
					"Activity must implement fragment's callbacks.");
		}

		mCallbacks = (Callbacks) context;
	}

	private void clickedDeleteButton() {
		Log.i("blaat", "Delete button clicked");

		new AlertDialog.Builder(this.getActivity())
				.setTitle(R.string.confirm_delete_title)
				.setMessage(R.string.confirm_delete_question)
				.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dialog, int id) {
						mCallbacks.onDeleteCredential(hashCode);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}
}
