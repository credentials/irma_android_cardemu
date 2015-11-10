package org.irmacard.cardemu.updates;

import java.util.Arrays;
import java.util.List;

public class AppVersionInfo {
	private String name;
	private int versionCode;
	private String versionName;
	private byte[] hash;
	private List<String> changes;

	public AppVersionInfo(String name, int versionCode, String versionName, byte[] hash, List<String> changes) {
		this.name = name;
		this.versionCode = versionCode;
		this.versionName = versionName;
		this.hash = hash;
		this.changes = changes;
	}

	public List<String> getChanges() {
		return changes;
	}

	public int getVersionCode() {
		return versionCode;
	}

	public String getVersionName() {
		return versionName;
	}

	public String getName() {
		return name;
	}

	public byte[] getHash() {
		return hash;
	}
}
