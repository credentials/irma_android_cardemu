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

package org.irmacard.cardemu.credentials;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.irmacard.credentials.info.FileReader;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.IssuerDescription;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;

@SuppressWarnings("unused")
public class AndroidFileReader implements FileReader {
	private static final String assetPath = "irma_configuration";
	private static final String internalPath = "store";
	private Context context;
	private AssetManager assets;

	public AndroidFileReader(Context context) {
		this.context = context;
		this.assets = context.getAssets();
	}

	@Override
	public InputStream retrieveFile(String path) throws InfoException {
		if (path.length() > 0 && !path.startsWith("/"))
			path = "/" + path;

		try {
			return assets.open(assetPath + path);
		} catch (IOException e) { /** Ignore absence of file, try internal storage next */ }

		try {
			File file = new File(context.getDir(internalPath, Context.MODE_PRIVATE), path);
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw new InfoException("Could not read file " + path + " from assets or storage");
		}
	}

	@Override
	public String[] list(String path) {
		if (path.length() > 0 && !path.startsWith("/"))
			path = "/" + path;

		// Using a set to avoid duplicates, if a path is present both in the assets and in internal storage
		HashSet<String> files = new HashSet<String>();

		try {
			files.addAll(Arrays.asList(assets.list(assetPath + path)));
		} catch (IOException e) {
			// assets.list() doesn't throw exceptions when queried on nonexisting paths,
			// (it just returns an empty array); so when it does, something must be wrong
			throw new RuntimeException("Could not list assets at " + path, e);
		}

		File dir = new File(context.getDir("store", Context.MODE_PRIVATE), path);
		String[] internalFiles = dir.list();
		if (internalFiles != null && internalFiles.length > 0)
			files.addAll(Arrays.asList(internalFiles));

		return files.toArray(new String[files.size()]);
	}

	@Override
	public boolean isEmpty(String path) {
		String[] files = list(path);
		return files == null || files.length == 0;
	}

	@Override
	public boolean containsFile(String path, String filename) {
		String[] files = list(path);
		return files != null && Arrays.asList(files).contains(filename);
	}

	@Override
	public boolean containsFile(String path) {
		String[] parts = path.split("/");
		String filename = parts[parts.length-1];
		path = path.substring(0, path.length() - filename.length() - 1);
		return containsFile(path, filename);
	}

	public Bitmap getIssuerLogo(IssuerDescription issuer) {
		Bitmap logo = null;
		String path = issuer.getIdentifier().getPath(false);

		try {
			logo = BitmapFactory.decodeStream(retrieveFile(path + "/logo.png"));
		} catch (InfoException e) {
			e.printStackTrace();
		}

		return logo;
	}
}
