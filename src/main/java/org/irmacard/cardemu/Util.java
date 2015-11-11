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
