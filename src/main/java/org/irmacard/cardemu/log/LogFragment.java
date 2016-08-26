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

package org.irmacard.cardemu.log;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.paging.listview.PagingListView;

import org.irmacard.cardemu.R;
import org.irmacard.credentials.util.log.LogEntry;

import java.util.List;

public class LogFragment extends Fragment {
	private LogListAdapter listAdapter;
	private List<LogEntry> logs = null;

	public static final String ARG_LOG = "log";
	private static int PAGE_COUNT = 20;

	/**
	 * Mandatory empty constructor for the fragment manager to instantiate the
	 * fragment (e.g. upon screen orientation changes).
	 */
	public LogFragment() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if(getArguments().containsKey(ARG_LOG)) {
			logs = (List<LogEntry>) getArguments().getSerializable(ARG_LOG);
		}

	    listAdapter = new LogListAdapter(getActivity());
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_log,
				container, false);

		final PagingListView list = (PagingListView) rootView.findViewById(R.id.log_list);
		TextView no_items = (TextView) rootView.findViewById(R.id.log_no_log);

		list.setAdapter(listAdapter);
		list.setHasMoreItems(true);
		list.setPagingableListener(new PagingListView.Pagingable() {
			private int index = 0;

			@Override
			public void onLoadMoreItems() {
				if (index >= logs.size()) {
					list.onFinishLoading(false, null);
					return;
				}

				new AsyncTask<Void,Void,List<LogEntry>>() {
					@Override protected List<LogEntry> doInBackground(Void... args) {
						List<LogEntry> items = logs.subList(index, Math.min(index+PAGE_COUNT, logs.size()));
						index += PAGE_COUNT;
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						return items;
					}
					@Override protected void onPostExecute(List<LogEntry> views) {
						list.onFinishLoading(index+PAGE_COUNT < logs.size(), views);
					}
				}.execute();
			}
		});

		if(logs == null || logs.isEmpty()) {
			list.setVisibility(View.INVISIBLE);
			no_items.setVisibility(View.VISIBLE);
			if(logs == null) {
				no_items.setText(R.string.error_logs_not_read);
			}
		} else {
			list.setVisibility(View.VISIBLE);
			no_items.setVisibility(View.INVISIBLE);
		}
		return rootView;
	}
}
