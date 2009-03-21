/*
 * Copyright 2002-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import org.springframework.integration.core.Message;
import org.springframework.integration.message.MessageDeliveryException;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link OutboundRequestMapper}.
 * 
 * @author Mark Fisher
 * @since 1.0.2
 */
public class DefaultOutboundRequestMapper implements OutboundRequestMapper {

	private volatile URL defaultUrl;

	private volatile boolean extractPayload = true;

	private volatile String charset = "UTF-8";


	/**
	 * Create a DefaultOutboundRequestMapper with no default URL.
	 */
	public DefaultOutboundRequestMapper() {
	}

	/**
	 * Create a DefaultOutboundRequestMapper with the given default URL.
	 */
	public DefaultOutboundRequestMapper(URL defaultUrl) {
		this.defaultUrl = defaultUrl;
	}


	/**
	 * Specify the default URL to use when the outbound message does not
	 * contain a value for the {@link HttpHeaders#REQUEST_URL} header.
	 * This default is optional, but if no value is provided, and a Message
	 * does not contain the header, then a MessageDeliveryException will be
	 * thrown at runtime.
	 */
	public void setDefaultUrl(URL defaultUrl) {
		this.defaultUrl = defaultUrl;
	}

	/**
	 * Specify whether the outbound message's payload should be extracted
	 * when preparing the request body. Otherwise the Message instance itself
	 * will be serialized. The default value is <code>true</code>.
	 */
	public void setExtractPayload(boolean extractPayload) {
		this.extractPayload = extractPayload;
	}

	/**
	 * Specify the charset name to use for converting String-typed payloads to
	 * bytes. The default is 'UTF-8'.
	 */
	public void setCharset(String charset) {
		Assert.isTrue(Charset.isSupported(charset), "unsupported charset '" + charset + "'");
		this.charset = charset;
	}

	public HttpRequest fromMessage(Message<?> message) throws Exception {
		Assert.notNull(message, "message must not be null");
		URL url = this.resolveUrl(message);
		if (url == null) {
			throw new MessageDeliveryException(message, "failed to determine a target URL for Message");
		}
		Object requestMethodHeader = message.getHeaders().get(HttpHeaders.REQUEST_METHOD);
		String requestMethod = (requestMethodHeader != null) ?
				requestMethodHeader.toString().toUpperCase() : "POST";
		if (this.extractPayload) {
			Object payload = message.getPayload();
			Assert.notNull(payload, "payload must not be null");
			return this.createRequestFromPayload(payload, url, requestMethod);
		}
		return this.createRequestFromMessage(message, url, requestMethod);
	}

	private HttpRequest createRequestFromPayload(Object payload, URL url, String requestMethod) throws Exception {
		ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
		String contentType = null;
		if ("POST".equals(requestMethod) || "PUT".equals(requestMethod)) {
			contentType = this.writeToRequestBody(payload, requestBody);
		}
		else {
			Assert.isTrue(payload instanceof Map,
					"Message payload must be a Map for a '" + requestMethod + "' request.");
			Map<String, String[]> parameterMap = this.createParameterMap((Map<?,?>) payload);
			Assert.notNull(parameterMap, "Payload must be a Map with String typed keys and " +
					"String or String array typed values for a '" + requestMethod + "' request.");
			url = this.addQueryParametersToUrl(url, parameterMap);
		}
		return new DefaultHttpRequest(url, requestMethod, requestBody, contentType);
	}

	private HttpRequest createRequestFromMessage(Message<?> message, URL url, String requestMethod) throws Exception {
		Assert.isTrue("POST".equals(requestMethod) || "PUT".equals(requestMethod),
				"POST or PUT request method is required when the 'extractPayload' value is false.");
		ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
		String contentType = this.writeToRequestBody(message, requestBody);
		return new DefaultHttpRequest(url, requestMethod, requestBody, contentType);
	}

	/**
	 * Creates a parameter map with String keys and String array values from
	 * the provided map if possible. If the provided map contains any keys that
	 * are not String typed, or any values that are not String or String array
	 * typed, then this method will return <code>null</code>.
	 */
	private Map<String, String[]> createParameterMap(Map<?,?> map) {
		Map<String, String[]> parameterMap = new HashMap<String, String[]>();
		for (Object key : map.keySet()) {
			if (!(key instanceof String)) {
				return null;
			}
			String[] stringArrayValue = null;
			Object value = map.get(key);
			if (value instanceof String) {
				stringArrayValue = new String[] { (String) value };
			}
			else if (value instanceof String[]) {
				stringArrayValue = (String[]) value;
			}
			else {
				return null;
			}
			parameterMap.put((String) key, stringArrayValue);
		}
		return parameterMap;
	}

	private String writeToRequestBody(Object object, ByteArrayOutputStream byteStream) throws Exception {
		String contentType = null;
		if (object instanceof byte[]) {
			byteStream.write((byte[]) object);
			contentType = "application/octet-stream";
		}
		else if (object instanceof String) {
			byteStream.write(((String) object).getBytes(this.charset));
			contentType = "text/plain; charset=" + this.charset;
		}
		else if (object instanceof Serializable) {
			byteStream.write(this.serializeObject((Serializable) object));
			contentType = "application/x-java-serialized-object";
		}
		else {
			throw new IllegalArgumentException("payload must be a byte array, " +
					"String, or Serializable object for a 'POST' or 'PUT' request");
		}
		return contentType;
	}

	private byte[] serializeObject(Serializable object) throws IOException {
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
		objectStream.writeObject(object);
		objectStream.flush();
		objectStream.close();
		return byteStream.toByteArray();
	}

	/**
	 * Resolve the request URL for the given Message. This implementation
	 * returns the value associated with the {@link HttpHeaders#REQUEST_URL}
	 * key if available in the Message's headers. Otherwise, it falls back to
	 * the default URL as provided to the constructor of this mapper instance.
	 * @throws MalformedURLException if an error occurs while constructing the URL
	 */
	private URL resolveUrl(Message<?> message) throws MalformedURLException {
		Object urlHeader = message.getHeaders().get(HttpHeaders.REQUEST_URL);
		if (urlHeader == null) {
			return this.defaultUrl;
		}
		if (urlHeader instanceof URL) {
			return (URL) urlHeader;
		}
		if (urlHeader instanceof URI) {
			return ((URI) urlHeader).toURL();
		}
		if (urlHeader instanceof String) {
			return new URL((String) urlHeader);
		}
		throw new IllegalArgumentException("Target URL in Message header must be a URL, URI, or String.");
	}

	/**
	 * Constructs a query string by appending the parameter map values to the URL.
	 * @throws Exception if an error occurs encoding or constructing the URL
	 */
	private URL addQueryParametersToUrl(URL url, Map<String, String[]> parameterMap) throws Exception {
		if (parameterMap == null || parameterMap.size() == 0) {
			return url;
		}
		String urlString = url.toExternalForm();
		String fragment = "";
		int fragmentStartIndex = urlString.indexOf('#');
		if (fragmentStartIndex != -1) {
			fragment = urlString.substring(fragmentStartIndex);
			urlString = urlString.substring(0, fragmentStartIndex);
		}
		StringBuilder sb = new StringBuilder(urlString);
		if (urlString.indexOf('?') == -1) {
			sb.append('?');
		}
		for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
			String[] values = entry.getValue();
			for (String value : values) {
				char lastChar = sb.charAt(sb.length() -1);
				if (lastChar != '?' && lastChar != '&') {
					sb.append('&');
				}
				sb.append(URLEncoder.encode(entry.getKey(), this.charset) + "=");
				sb.append(URLEncoder.encode(value, this.charset));
			}
		}
		sb.append(fragment);
		return new URL(sb.toString());
	}


	/**
	 * Default implementation of {@link HttpRequest}.
	 */
	class DefaultHttpRequest implements HttpRequest {

		private final URL targetUrl;

		private final String requestMethod;

		private final String contentType;

		private volatile ByteArrayOutputStream requestBody;


		DefaultHttpRequest(
				URL targetUrl, String requestMethod, ByteArrayOutputStream requestBody, String contentType)
				throws IOException {
			Assert.notNull(targetUrl, "target url must not be null");
			this.targetUrl = targetUrl;
			this.requestMethod = (requestMethod != null) ? requestMethod : "POST";
			this.requestBody = requestBody;
			this.contentType = contentType;
		}


		public URL getTargetUrl() {
			return this.targetUrl;
		}

		public String getRequestMethod() {
			return this.requestMethod;
		}

		public String getContentType() {
			return this.contentType;
		}

		public Integer getContentLength() {
			return (this.requestBody != null) ? this.requestBody.size() : null;
		}

		public ByteArrayOutputStream getBody() {
			return this.requestBody;
		}

	}

}
