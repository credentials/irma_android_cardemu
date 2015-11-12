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

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Convenience class to synchroniously do HTTP GET and PUT requests,
 * and serialize the in- and output automatically using Gson. <br/>
 * NOTE: the methods of this class must not be used on the main thread,
 * as otherwise a NetworkOnMainThreadException will occur.
 */
public class HttpClient {
	private Gson gson;
	private SSLSocketFactory socketFactory;
	private int timeout = 5000;

	/**
	 * Instantiate a new HttpClient.
	 *
	 * @param gson The Gson object that will handle (de)serialization.
	 */
	public HttpClient(Gson gson) {
		this(gson, null);
	}

	/**
	 * Instantiate a new HttpClient.
	 * @param gson The Gson object that will handle (de)serialization.
	 * @param socketFactory The SSLSocketFactory to use.
	 */
	public HttpClient(Gson gson, SSLSocketFactory socketFactory) {
		this.gson = gson;
		this.socketFactory = socketFactory;
	}

	/**
	 * Performs a GET on the specified url. See the javadoc of doPost.
	 *
	 * @param type The type to which the return value should be cast. If the casting fails
	 *             an exception will be raised.
	 * @param url The url to post to.
	 * @param <T> The object to post. May be null, in which case we do a GET instead
	 *            of post.
	 * @return The T returned by the server, if successful.
	 * @throws HttpClientException
	 */
	public <T> T doGet(final Type type, String url) throws HttpClientException {
		return doRequest(type, url, null, "", "GET");
	}

	/**
	 * POSTs the specified object to the specified url, and attempts to cast the response
	 * of the server to the type specified by T and type. (Note: apparently it is not possible
	 * get a Type from T or vice versa, so the type variable T and Type type must both be
	 * given. Fortunately, the T is inferrable so this method can be called like so:<br/>
	 * <code>YourType x = doPost(YourType.class, "http://www.example.com/post", object);</code><br/><br/>
	 *
	 * This method does not (yet) support the posting of generics to the server.
	 *
	 * @param type The type to which the return value should be cast. If the casting fails
	 *             an exception will be raised.
	 * @param url The url to post to.
	 * @param object The object to post. May be null, in which case we do a GET instead
	 *               of post.
	 * @param <T> The type to which the return value should be cast.
	 * @return The T returned by the server, if successful.
	 * @throws HttpClientException If the casting failed, <code>status</code> will be zero.
	 * Otherwise, if the communication with the server failed, <code>status</code> will
	 * be an HTTP status code.
	 */
	public <T> T doPost(final Type type, String url, Object object) throws HttpClientException {
		return doRequest(type, url, object, "", "POST");
	}

	/**
	 * POSTs the specified object to the specified url, ignoring the output of the server.<br/><br/>
	 *
	 * This method does not (yet) support the posting of generics to the server.
	 *
	 * @param url The url to post to.
	 * @param object The object to post. May be null, in which case we do a GET instead
	 *               of post.
	 * @throws HttpClientException If the casting failed, <code>status</code> will be zero.
	 * Otherwise, if the communication with the server failed, <code>status</code> will
	 * be an HTTP status code.
	 */
	public void doPost(String url, Object object) throws HttpClientException {
		doRequest(Object.class, url, object, "", "POST");
	}

	/**
	 * POSTs the specified object to the specified url, and attempts to cast the response
	 * of the server to the type specified by T and type. (Note: apparently it is not possible
	 * get a Type from T or vice versa, so the type variable T and Type type must both be
	 * given. Fortunately, the T is inferrable so this method can be called like so:<br/>
	 * <code>YourType x = doPost(YourType.class, "http://www.example.com/post", object);</code><br/><br/>
	 *
	 * This method does not (yet) support the posting of generics to the server.
	 *
	 * @param type The type to which the return value should be cast. If the casting fails
	 *             an exception will be raised.
	 * @param url The url to post to.
	 * @param object The object to post. May be null, in which case we do a GET instead
	 *               of post.
	 * @param authorization The authorization header to be sent. If this equals the empty string, no such header will
	 *                      be sent.
	 * @param <T> The type to which the return value should be cast.
	 * @return The T returned by the server, if successful.
	 * @throws HttpClientException If the casting failed, <code>status</code> will be zero.
	 * Otherwise, if the communication with the server failed, <code>status</code> will
	 * be an HTTP status code.
	 */
	public <T> T doPost(Type type, String url, Object object, String authorization) throws HttpClientException {
		return doRequest(type, url, object, authorization, "POST");
	}

	/**
	 * Performs a DELETE request.
	 *
	 * @param url The url to DELETE
	 * @throws HttpClientException
	 */
	public void doDelete(String url) throws HttpClientException {
		doRequest(Object.class, url, null, "", "DELETE");
	}

	/**
	 * Worker method
	 */
	private <T> T doRequest(Type type, String url, Object object, String authorization, String method)
	throws HttpClientException {
		HttpURLConnection c = null;

		try {
			URL u = new URL(url);
			c = (HttpURLConnection) u.openConnection();
			if (url.startsWith("https") && socketFactory != null)
				((HttpsURLConnection) c).setSSLSocketFactory(socketFactory);
			c.setRequestMethod(method);
			c.setUseCaches(false);
			c.setConnectTimeout(timeout);
			c.setReadTimeout(timeout);
			c.setDoInput(true);

			byte[] objectBytes = new byte[] {};

			if (method.equals("POST")) {
				objectBytes = gson.toJson(object).getBytes();
				c.setDoOutput(true);
				// See http://www.evanjbrunner.info/posts/json-requests-with-httpurlconnection-in-android/
				c.setFixedLengthStreamingMode(objectBytes.length);
				c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
			}

			if (authorization.length() > 0) {
				c.setRequestProperty("Authorization", authorization);
			}

			c.connect();

			if (method.equals("POST")) {
				OutputStream os = new BufferedOutputStream(c.getOutputStream());
				os.write(objectBytes);
				os.flush();
			}

			int status = c.getResponseCode();
			switch (status) {
				case 200:
				case 201:
				case 204:
					return gson.fromJson(inputStreamToString(c.getInputStream()), type);
				default:
					String error;
					try {
						error = inputStreamToString(c.getErrorStream());
					} catch (Exception e) {
						error = "";
					}
					throw new HttpClientException(status, error);
			}
		} catch (JsonSyntaxException|IOException e) { // IOException includes MalformedURLException
			e.printStackTrace();
			throw new HttpClientException(e);
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}

	/**
	 * Exception class for HttpClient.
	 */
	public class HttpClientException extends Exception {
		private static final long serialVersionUID = -3138843561169048744L;

		public int status = 0;
		public Throwable cause;

		@Override
		public String toString() {
			return "HttpClientException{" +
					"status=" + status +
					", cause=" + cause +
					'}';
		}

		public HttpClientException(Throwable cause) {
			super(cause);
			this.cause = cause;
		}

		public HttpClientException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	public static String inputStreamToString(InputStream is) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null)
			sb.append(line).append("\n");

		br.close();
		is.close();
		return sb.toString();
	}
}
