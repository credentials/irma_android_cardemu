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

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.R;
import org.irmacard.verification.common.AttributeDisjunction;
import org.irmacard.verification.common.AttributeIdentifier;

public class DisjunctionFragment extends Fragment {
	AttributeDisjunction disjunction;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		this.disjunction = (AttributeDisjunction) getArguments().getSerializable("disjunction");
		LinearLayout list = (LinearLayout) inflater.inflate(R.layout.disjunction_fragment, container, false);

		TextView title = (TextView) list.findViewById(R.id.disjunction_title);
		title.setText(disjunction.getLabel());
		if (CredentialManager.getCandidates(disjunction).isEmpty()) {
			title.setTextColor(getResources().getColor(R.color.irmared));
		}

		LinearLayout content = (LinearLayout) list.findViewById(R.id.disjunction_content);
		for (AttributeIdentifier ai : disjunction) {
			View view = inflater.inflate(R.layout.disjunction_item, container, false);

			TextView textView = (TextView) view.findViewById(R.id.disjunction_title);
			String text;
			if (ai.isCredential())
				text = ai.getIssuerName() + " - " + ai.getCredentialName() + " (possession of credential)";
			else
				text = ai.getIssuerName() + " - " + ai.getAttributeName();
			textView.setText(text);

			if (!CredentialManager.contains(ai)) {
				ImageView image = (ImageView) view.findViewById(R.id.disjunction_icon);
				image.setImageResource(R.drawable.irma_icon_missing_064px);
			}

			content.addView(view);
		}

		return list;
	}
}
