package com.shadworld.jsonrpc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpEventListener;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.security.BasicAuthentication;
import org.eclipse.jetty.client.security.ProxyAuthorization;
import org.eclipse.jetty.client.security.Realm;
import org.eclipse.jetty.client.security.RealmResolver;
import org.eclipse.jetty.client.security.SimpleRealmResolver;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Field;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

import sun.misc.BASE64Encoder;

import com.shadworld.poolserver.conf.Res;
import com.shadworld.util.Time;

public class JsonRpcClient {

	HttpClient client;

	private String url;
	private String httpUser;
	private String httpPassword;
	private String authString;
	
	private String proxyHost;
	private int proxyPort = 80;
	private String proxyUser;
	private String proxyPassword;

	private boolean longPoll = false;
	private int requestId = 1;
	
	UrlRealmResolver realmResolver = new UrlRealmResolver();
	
	private ContentExchange lastExchange;

	public JsonRpcClient(String url, String user, String password) {
		this(false, url, user, password);
	}

	public JsonRpcClient(boolean longPoll, String url, String user, String password) {
		this.longPoll = longPoll;
		this.url = url;
		this.httpUser = user;
		this.httpPassword = password;
		initClient();
	}

	private void initClient() {
		client = new HttpClient();
		client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
		if (proxyHost != null) {
			client.setProxy(new Address(proxyHost, proxyPort));
		}
		if (proxyUser != null) {
			try {
				client.setProxyAuthentication(new ProxyAuthorization(proxyUser, proxyPassword));
			} catch (IOException e) {
				Res.logException(e);
			}
		}

		client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
		client.setRealmResolver(realmResolver);
		if (httpUser != null) {
			setHttpAuthCredentials(url, httpUser, httpPassword);
		}
		
		if (longPoll) {
			client.setTimeout(Time.MIN * 5);
			client.setConnectTimeout(10000);
			client.setIdleTimeout(Time.MIN * 5);
		} else {
			client.setTimeout(30000);
			client.setConnectTimeout(10000);
			client.setIdleTimeout(30000);
		}
	}
	
	public void setHttpAuthCredentials (String url, final String username, final String password) {
		realmResolver.addRealm(url, username, password);
	}
	
	private void prepareExchange(ContentExchange ex, String content, String url) {
		if (!client.isRunning())
			try {
				client.start();
			} catch (Exception e) {
				Res.logException(e);
				return;
			}
		ex.setMethod("POST");
		ex.setRequestContent(new ByteArrayBuffer(content));
		ex.setURL(url == null ? this.url : url);
		ex.setRequestContentType("application/json");
		ex.setRequestHeader("Connection", "Keep-Alive");
		//if (httpUser != null)
		//	ex.addRequestHeader("Authorization", getAuthString());
	}

	private ContentExchange getExchange(String content, String url) {
		lastExchange = new ContentExchange(true);
		prepareExchange(lastExchange, content, url);
		return lastExchange;
	}

	/**
	 * do async request.  Pass a ContentExchange with the appropriate callbacks.
	 * @param request
	 * @param exchange
	 * @throws IOException
	 */
	public void doRequest(String url, String request, ContentExchange exchange) throws IOException {
		if (!client.isRunning())
			try {
				client.start();
			} catch (Exception e) {
				Res.logException(e);
				return;
			}
		prepareExchange(exchange, request, url);
		client.send(exchange);
	}
	
	/**
	 * do async request.  Pass a ContentExchange with the appropriate callbacks.
	 * @param request
	 * @param exchange
	 * @throws IOException
	 */
	public void doRequest(String request, ContentExchange exchange) throws IOException {
		doRequest(url, request, exchange);
	}
	
	/**
	 * do async request.  Pass a ContentExchange with the appropriate callbacks.
	 * @param request
	 * @param exchange
	 * @throws IOException
	 */
	public void doRequest(String url, JSONObject json, ContentExchange exchange) throws IOException {
		doRequest(url, json.toString(), exchange);
	}
	
	/**
	 * do async request.  Pass a ContentExchange with the appropriate callbacks.
	 * @param request
	 * @param exchange
	 * @throws IOException
	 */
	public void doRequest(JSONObject json, ContentExchange exchange) throws IOException {
		doRequest(url, json.toString(), exchange);
	}
	
	/**
	 * do async request.  Pass a ContentExchange with the appropriate callbacks.
	 * @param request
	 * @param exchange
	 * @throws IOException
	 */
	public void doRequest(String url, JsonRpcRequest request, ContentExchange exchange) throws IOException {
		doRequest(url, request.toJSONString(), exchange);
	}
	
	/**
	 * do async request.  Pass a ContentExchange with the appropriate callbacks.
	 * @param request
	 * @param exchange
	 * @throws IOException
	 */
	public void doRequest(JsonRpcRequest request, ContentExchange exchange) throws IOException {
		doRequest(url, request.toJSONString(), exchange);
	}
	
	/**
	 * do request when you expect an array response.  If the response is an error then an array of length 1 will be returned
	 * containing the JsonRpc error response 
	 * @param url
	 * @param request
	 * @return
	 */
	public List<JsonRpcResponse> doRequestForArray(String url, JSONObject request) {
		return doRequestForArray(url, request.toString());
	}
	
	/**
	 * do request when you expect an array response.  If the response is an error then an array of length 1 will be returned
	 * containing the JsonRpc error response 
	 * @param url
	 * @param request
	 * @return
	 */
	public List<JsonRpcResponse> doRequestForArray(JSONObject request) {
		return doRequestForArray(null, request.toString());
	}
	
	/**
	 * do request when you expect an array response.  If the response is an error then an array of length 1 will be returned
	 * containing the JsonRpc error response 
	 * @param url
	 * @param request
	 * @return
	 */
	public List<JsonRpcResponse> doRequestForArray(String url, JsonRpcRequest request) {
		return doRequestForArray(url, request.toJSONString());
	}
	
	/**
	 * do request when you expect an array response.  If the response is an error then an array of length 1 will be returned
	 * containing the JsonRpc error response 
	 * @param url
	 * @param request
	 * @return
	 */
	public List<JsonRpcResponse> doRequestForArray(JsonRpcRequest request) {
		return doRequestForArray(null, request.toJSONString());
	}

	/**
	 * do request when you expect an array response.  If the response is an error then an array of length 1 will be returned
	 * containing the JsonRpc error response 
	 * @param url
	 * @param request
	 * @return
	 */
	public List<JsonRpcResponse> doRequestForArray(String request) {
		return doRequestForArray(null, request);
	}
	
	/**
	 * do request when you expect an array response.  If the response is an error then an array of length 1 will be returned
	 * containing the JsonRpc error response 
	 * @param url
	 * @param request
	 * @return
	 */
	public List<JsonRpcResponse> doRequestForArray(String url, String request) {
		ContentExchange exchange = getExchange(request, url);
		exchange.setRequestContentSource(new ByteArrayInputStream(request.getBytes()));
		try {
			client.send(exchange);
			int result = exchange.waitForDone();
			String response = exchange.getResponseContent();
			if (response == null)
				return null;
			if (JsonUtil.isJSONArray(response)) {
				JSONArray array = new JSONArray(response);
				List<JsonRpcResponse> responses = new ArrayList(array.length());
				for (int i = 0; i < array.length(); i++) {
					JsonRpcResponse r = new JsonRpcResponse(null, array.getJSONObject(i));
					responses.add(r);
				}
				return responses;
			} else {
				JsonRpcResponse r = new JsonRpcResponse(response, null);
				return Collections.singletonList(r);
			} 
		} catch (Exception e) {
			Res.logException(e);
			return null;
		}
	}
		
	/**
	 * do request or notify and return a JSONObject which should contain the
	 * JSON-RPC response
	 * 
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doRequest(String url, String request) {
		ContentExchange exchange = getExchange(request, url);
		exchange.setRequestContentSource(new ByteArrayInputStream(request.getBytes()));
		try {
			client.send(exchange);
			int result = exchange.waitForDone();
			String response = exchange.getResponseContent();
			if (response == null)
				return null;
			return new JsonRpcResponse(response, null);
		} catch (Exception e) {
			if (Res.isDebug())
				Res.logException(e);
			return null;
		}
	}
	
	/**
	 * do request or notify and return a JSONObject which should contain the
	 * JSON-RPC response
	 * 
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doRequest(String request) {
		return doRequest(url, request);
	}

	/**
	 * do request or notify and return a JSONObject which should contain the
	 * JSON-RPC response
	 * 
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doRequest(String url, JSONObject request) {
		return doRequest(url, request.toString());
	}
	
	/**
	 * do request or notify and return a JSONObject which should contain the
	 * JSON-RPC response
	 * 
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doRequest(JSONObject request) {
		return doRequest(url, request.toString());
	}

	/**
	 * do request or notify and return a JSONObject which should contain the
	 * JSON-RPC response
	 * 
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doRequest(String url, JsonRpcRequest request) {
		return doRequest(url, request.toJSONString());
	}
	
	/**
	 * do request or notify and return a JSONObject which should contain the
	 * JSON-RPC response
	 * 
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doRequest(JsonRpcRequest request) {
		return doRequest(url, request.toJSONString());
	}

	/**
	 * @return the httpUser
	 */
	public String getHttpUser() {
		return httpUser;
	}

	/**
	 * @param httpUser the httpUser to set
	 */
	public void setHttpUser(String httpUser) {
		this.httpUser = httpUser;
	}

	/**
	 * @return the httpPassword
	 */
	public String getHttpPassword() {
		return httpPassword;
	}

	/**
	 * @param httpPassword the httpPassword to set
	 */
	public void setHttpPassword(String httpPassword) {
		this.httpPassword = httpPassword;
	}

	/**
	 * @return the proxyHost
	 */
	public String getProxyHost() {
		return proxyHost;
	}

	/**
	 * @param proxyHost the proxyHost to set
	 */
	public void setProxyHost(String proxyHost) {
		this.proxyHost = proxyHost;
	}

	/**
	 * @return the proxyPort
	 */
	public int getProxyPort() {
		return proxyPort;
	}

	/**
	 * @param proxyPort the proxyPort to set
	 */
	public void setProxyPort(int proxyPort) {
		this.proxyPort = proxyPort;
	}

	/**
	 * @return the proxyUser
	 */
	public String getProxyUser() {
		return proxyUser;
	}

	/**
	 * @param proxyUser the proxyUser to set
	 */
	public void setProxyUser(String proxyUser) {
		this.proxyUser = proxyUser;
	}

	/**
	 * @return the proxyPassword
	 */
	public String getProxyPassword() {
		return proxyPassword;
	}

	/**
	 * @param proxyPassword the proxyPassword to set
	 */
	public void setProxyPassword(String proxyPassword) {
		this.proxyPassword = proxyPassword;
	}

	/**
	 * @return the longPoll
	 */
	public boolean isLongPoll() {
		return longPoll;
	}

	/**
	 * @return the next requestId used by this client.  This will increment the id so every call to this method is unique.
	 */
	public int newRequestId() {
		return requestId++;
	}
	
	
	
	/**
	 * @return the client
	 */
	public HttpClient getClient() {
		return client;
	}

	public String getAuthString() {
		if (authString == null) {
			BASE64Encoder enc = new BASE64Encoder();
			String plain = "Basic " + httpUser + ":" + httpPassword;
			authString = enc.encodeBuffer(plain.getBytes());
		}
		return authString;
	}
	
	private class UrlRealmResolver implements RealmResolver {

		private HashMap<String, Realm> realms = new HashMap();
		
		@Override
		public Realm getRealm(String realmName, HttpDestination destination, String path) throws IOException {
			String key = keyString(destination.getAddress(), path);
			Realm realm = realms.get(key);
			if (realm == null && Res.isDebug())
				Res.logError("Realm resolved null for key: " + key + " Realm: " + realmName);
			return realm;
		}
		
		private String keyString(Address address, String path) {
			return path == null ? address.toString() : address.toString() + path;
		}
		
		public void addRealm(String url, String username, String password) {
			try {
				URL u = new URL(url);
				Address a = new Address(u.getHost(), u.getPort());
				String key = keyString(a, u.getPath());
				SimpleRealm realm = new SimpleRealm(username, password);
				realms.put(key, realm);
				if (!"/".equals(u.getPath()))
					realms.put(a.toString() + "/", realm);
			} catch (MalformedURLException e) {
				return;
			}
		}
		
	}
	
	private class SimpleRealm implements Realm {

		private String username;
		private String password;
		private String realm = "realm";
		
		
		
		public SimpleRealm(String username, String password) {
			super();
			this.username = username;
			this.password = password;
		}

		public SimpleRealm(String username, String password, String realm) {
			super();
			this.username = username;
			this.password = password;
			this.realm = realm;
		}

		@Override
		public String getId() {
			// TODO Auto-generated method stub
			return realm;
		}

		@Override
		public String getPrincipal() {
			// TODO Auto-generated method stub
			return username;
		}

		@Override
		public String getCredentials() {
			// TODO Auto-generated method stub
			return password;
		}
		
	}
	
	public String getRequestHeaderString() {
		return getRequestHeaderString(lastExchange);
	}
	
	public String getRequestHeaderString(ContentExchange ex) {
		StringBuilder sb = new StringBuilder(ex.getMethod()).append(" ");
		sb.append(ex.getURI()).append(" HTTP/1.").append(ex.getVersion());
		HttpFields fields = ex.getRequestFields();
		if (fields != null)
			for (int i = 0; i < fields.size(); i++) {
				Field field = fields.getField(i);
				sb.append("\n").append(field.getName())
				.append(": ").append(field.getValue());
			}
		return sb.toString();
	}
	
	public String getRequestContentString() {
		return getRequestContentString(lastExchange);
	}
	
	public String getRequestContentString(ContentExchange ex) {
		return ex.getRequestContent() == null ? "" : ex.getRequestContent().toString();
	}
	
	public String getFullRequestString() {
		return getFullRequestString(lastExchange);
	}
	
	public String getFullRequestString(ContentExchange ex) {
		String content = getRequestContentString(ex);
		StringBuilder sb = new StringBuilder(getRequestHeaderString(ex));
		if (content != null)
			sb.append("\n\n").append(content == null ? "" : content);
		return sb.toString();
	}
	
	public String getResponseHeaderString() {
		return getResponseHeaderString(lastExchange);
	}
	
	public String getResponseHeaderString(ContentExchange ex) {
		StringBuilder sb = new StringBuilder("HTTP/1.").append(ex.getVersion());
		sb.append(" ").append(ex.getResponseStatus()).append("\n");
		HttpFields fields = ex.getResponseFields();
		if (fields != null)
			for (int i = 0; i < fields.size(); i++) {
				Field field = fields.getField(i);
				sb.append("\n").append(field.getName())
				.append(": ").append(field.getValue());
			}
		return sb.toString();
	}
	
	public String getResponseContentString() {
		return getResponseContentString(lastExchange);
	}
	
	public String getResponseContentString(ContentExchange ex) {
		try {
			return ex.getResponseContent();
		} catch (UnsupportedEncodingException e) {
			return null;
		}
	}
	
	public String getFullResponseString() {
		return getFullResponseString(lastExchange);
	}
	
	public ContentExchange getLastExchange() {
		return lastExchange;
	}
	
	public String getFullResponseString(ContentExchange ex) {
		String content = getResponseContentString(ex);
		StringBuilder sb = new StringBuilder(getResponseHeaderString(ex));
		if (content != null)
			sb.append("\n\n").append(content == null ? "" : content);
		return sb.toString();
	}
	
	public String getFullExchangeString() {
		return getFullExchangeString(lastExchange);
	}
	
	public String getFullExchangeString(ContentExchange ex) {
		return new StringBuilder("Request:\n")
			.append(getFullRequestString(ex))
			.append("\n\nResponse: ")
			.append(getFullResponseString(ex))
			.toString();
	}

}
