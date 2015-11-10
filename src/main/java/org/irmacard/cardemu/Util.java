package org.irmacard.cardemu;

import java.util.regex.Pattern;

public class Util {
	/**
	 * Check if an IP address is valid.
	 *
	 * @param url The IP to check
	 * @return True if valid, false otherwise.
	 */
	public static Boolean isValidIPAddress(String url) {
		Pattern IP_ADDRESS = Pattern.compile(
				"((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
						+ "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
						+ "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
						+ "|[1-9][0-9]|[0-9]))");
		return IP_ADDRESS.matcher(url).matches();
	}

	/**
	 * Check if a given domain name is valid. We consider it valid if it consists of
	 * alphanumeric characters and dots, and if the first character is not a dot.
	 *
	 * @param url The domain to check
	 * @return True if valid, false otherwise
	 */
	public static Boolean isValidDomainName(String url) {
		Pattern VALID_DOMAIN = Pattern.compile("([\\w]+[\\.\\w]*)");
		return VALID_DOMAIN.matcher(url).matches();
	}


	/**
	 * Check if a given URL is valid. We consider it valid if it is either a valid
	 * IP address or a valid domain name, which is checked using
	 * using {@link #isValidDomainName(String)} Boolean} and
	 * {@link #isValidIPAddress(String) Boolean}.
	 *
	 * @param url The URL to check
	 * @return True if valid, false otherwise
	 */
	public static Boolean isValidURL(String url) {
		String[] parts = url.split("\\.");

		// If the part of the url after the rightmost dot consists
		// only of numbers, it must be an IP address
		if (Pattern.matches("[\\d]+", parts[parts.length - 1]))
			return isValidIPAddress(url);
		else
			return isValidDomainName(url);
	}
}
