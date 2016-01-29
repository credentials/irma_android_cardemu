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
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.View;
import org.irmacard.mno.common.util.GsonUtil;
import org.irmacard.cardemu.R;
import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.AttributeDisjunctionList;

public class DisclosureInformationActivity extends Activity {
	AttributeDisjunctionList disjunctions;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// The extras framework does not seem to retain type information: putting the AttributeDisjunctionList
		// into it directly as a Serializable causes a ClassCastException here when we try to deserialize it,
		// saying that it is an ArrayList, not an AttributeDisjunctionList. So, we use Gson.
		disjunctions = GsonUtil.getGson().fromJson(
				getIntent().getStringExtra("disjunctions"), AttributeDisjunctionList.class);

		setContentView(R.layout.activity_disclose_info);

		if (savedInstanceState != null)
			return;

		FragmentTransaction transaction = getFragmentManager().beginTransaction();
		for (AttributeDisjunction disjunction : disjunctions) {
			DisjunctionFragment fragment = new DisjunctionFragment();
			Bundle bundle = new Bundle();
			bundle.putSerializable("disjunction", disjunction);
			fragment.setArguments(bundle);
			transaction.add(R.id.disjunction_container, fragment, "disjunction_" + disjunction.getLabel());
		}
		transaction.commit();
	}

	public void onBackButtonTouch(View button) {
		finish();
	}
}
