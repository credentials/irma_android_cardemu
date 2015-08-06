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
		return doPost(type, url, null);
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
		HttpURLConnection c = null;
		String method;

		if (object == null)
			method = "GET";
		else
			method = "POST";

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
					return gson.fromJson(inputStreamToString(c.getInputStream()), type);
				default:
					throw new HttpClientException(status, null);
			}
		} catch (JsonSyntaxException |IOException e) { // IOException includes MalformedURLException
			e.printStackTrace();
			throw new HttpClientException(0, e);
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
		public int status;
		public Throwable cause;

		public HttpClientException(int status, Throwable cause) {
			super(cause);
			this.cause = cause;
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
