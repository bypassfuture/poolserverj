package com.shadworld.poolserver.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import sun.misc.BASE64Decoder;

import com.shadworld.cache.ArrayDequeResourcePool;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.JsonUtil;
import com.shadworld.jsonrpc.exception.ContinuationException;
import com.shadworld.jsonrpc.exception.JsonRpcResponseException;
import com.shadworld.jsonrpc.exception.MethodNotFoundException;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.PsjVersion;
import com.shadworld.poolserver.WorkProxy;
import com.shadworld.poolserver.WorkerProxy;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.servlet.auth.WorkerAuthenticator;
import com.shadworld.poolserver.servlet.auth.WorkerLoginEntry;
import com.shadworld.poolserver.servlet.auth.WorkerLoginEntry.LoginResult;
import com.shadworld.utils.StringTools;

public abstract class AbstractJsonRpcServlet extends HttpServlet {

	final protected PoolServer poolServer;
	final protected WorkProxy workProxy;
	final protected WorkerProxy workerProxy;
	final protected WorkerAuthenticator workerAuthenticator;

	AbstractJsonRpcServlet longpollServlet;

	// protected boolean isAllowMultiGet = true;
	// private int allowedPort = -1;
	private boolean checkLongpoll = false;

	// private HashMap<String, Integer> failedLogins = new HashMap();

	final protected String longPollUrl;
	protected String longpollPath;
	protected String longpollPathTrimmed;
	protected boolean isLongpoll;

	protected final String rollNTimeString;
	protected final boolean enableNonceRange;
	protected final boolean enableRollNTime;
	protected final boolean enableMiningExtenstions;

	private final String serverHeader = "PoolServerJ/" + PsjVersion.getVersion();

	private final ArrayDequeResourcePool<StringBuilder> contentStringBuilderPool = new ArrayDequeResourcePool<StringBuilder>(
			50, 200);

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest
	 * , javax.servlet.http.HttpServletResponse)
	 */
	public AbstractJsonRpcServlet(PoolServer poolServer, String longPollUrl, String rollNTimeString) {
		super();
		this.poolServer = poolServer;
		this.workProxy = poolServer.getWorkProxy();
		this.workerProxy = poolServer.getWorkerProxy();
		this.longPollUrl = longPollUrl;
		this.longpollPath = Conf.get().getHttpLPJsonRpcPath();
		this.longpollPathTrimmed = longpollPath;
		if (longpollPathTrimmed != null && longpollPathTrimmed.isEmpty())
			longpollPathTrimmed = null;
		if (longpollPathTrimmed != null) {
			longpollPathTrimmed = longpollPathTrimmed.trim().toLowerCase();
			if (longpollPathTrimmed.startsWith("/"))
				longpollPathTrimmed = longpollPathTrimmed.substring(1);
			if (longpollPathTrimmed.endsWith("/"))
				longpollPathTrimmed = longpollPathTrimmed.substring(0, longpollPathTrimmed.length() - 1);
		}

		this.rollNTimeString = rollNTimeString;
		this.enableNonceRange = workProxy.isEnableNonceRange();
		this.enableRollNTime = workProxy.isEnableRollNTime();
		enableMiningExtenstions = enableNonceRange || enableRollNTime;
		this.workerAuthenticator = Conf.get().getWorkerAuthenticator();
		this.workerProxy.setWorkerAuthenticator(workerAuthenticator);
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doRequest(req, resp, true);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doRequest(req, resp, false);
	}

	protected void doRequest(final HttpServletRequest req, final HttpServletResponse resp, final boolean isGet)
			throws ServletException, IOException {

		if (!isLongpoll && longpollPathTrimmed != null) {
			String path = req.getServletPath();
			if (path == null)
				path = req.getContextPath();
			if (path != null && path.toLowerCase().contains(longpollPathTrimmed)) {
				if (Res.isTrace()) {
					Res.logTrace(Res.TRACE_LP_SPAM,
							"Redirecting incorrect LP url to LP servlet.  Path sent: " + req.getPathInfo());
				}
				longpollServlet.doRequest(req, resp, isGet);
				return;
			}
		}

		// auth
		WorkerLoginEntry entry = workerAuthenticator.checkAuth(req);
		if (entry == null) {
			resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			resp.addHeader("WWW-Authenticate", "Basic realm=\"realm\"");
			// baseRequest.setHandled(true);
			return;
		}
		// if (Res.isTrace(Res.TRACE_LP_SPAM))
		// entry.worker.registerWorkerRequest(poolServer.getBlockTracker().getCurrentBlock(),
		// entry.loginResult == LoginResult.OK, this instanceof
		// PoolServerJLongpollServlet, isGet);
		// if (entry.worker.isBadWorker() ||
		// !entry.worker.getPassword().equals(entry.suppliedPassword)
		// || !entry.worker.isAllowedHost(req.getRemoteAddr())) {
		if (entry.loginResult == LoginResult.OK) {
			entry.worker.setLoggedIn(true);
		} else {
			resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
			entry.worker.setLoggedIn(false);
			return;
		}

		// write headers
		resp.setHeader("Server", serverHeader);
		if (!isLongpoll) {

			// this is a bad idea. It completely screws up nginx.
			// resp.setHeader("Connection", "Keep-Alive");
		}
		if (longPollUrl != null) {
			//if (isLongpoll) {
			//	Res.logError("Longpoll servlet has Longpoll URL set: " + longPollUrl);
			//}
			// resp.addHeader("X-Long-Polling", longPollUrl);
			resp.addHeader("X-Long-Polling", longpollPath);
			// resp.addHeader("X-Long-Polling", "/LP/");
		}
		long currentBlock = workProxy.getCurrentBlock();
		if (currentBlock > 0) {
			resp.setHeader("X-Blocknum", String.valueOf(currentBlock));
		}

		// Set<String> miningExtensions = null;
		boolean supportRejectReason = false;
		boolean supportNoncerange = false;
		boolean supportRollNTime = false;

		if (enableMiningExtenstions) {
			String xHeader = req.getHeader("X-Mining-Extensions");
			if (xHeader != null) {
				supportRejectReason = xHeader.indexOf("reject-reason") != -1;
				// supportNoncerange = enableNonceRange &&
				// xHeader.indexOf("noncerange") != -1;
				supportNoncerange = false;
				// supportRollNTime = enableRollNTime &&
				// xHeader.indexOf("rollntime") != -1;
				supportRollNTime = false;
			}
			if (supportRollNTime && rollNTimeString != null) {
				resp.addHeader("X-Roll-NTime", rollNTimeString);
			}
		}

		resp.setContentType("application/json");

		// force headers to flush if this is a long poll, otherwise we'll wait
		// until we've got response content to send
		// so we don't force extra packets.

		// no longer needed since we implemented continuations.
		// if (longPollUrl != null)
		// resp.flushBuffer();

		JsonRpcRequest request;

		StringBuilder sb = contentStringBuilderPool.getResource();
		if (sb == null) {
			sb = new StringBuilder();
		} else {
			sb.setLength(0);
		}
		BufferedReader reader = req.getReader();
		String s;
		while ((s = reader.readLine()) != null) {
			sb.append(s).append("\n");
		}
		String rString = sb.toString();
		if (sb.length() < 1000) {
			contentStringBuilderPool.returnResource(sb);
		}
		PrintWriter writer = resp.getWriter();

		if (isGet || (isLongpoll && "".equals(rString))) {
			request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORK, 1);
		} else {

			try {
				request = new JsonRpcRequest(rString, null);
			} catch (JSONException e) {
				JSONObject response = buildErrorResponse(null, -32700, "Parse error.", null);
				String responseString = response.toString();
				resp.setContentLength(responseString.length());
				writer.append(responseString);
				writer.close();
				return;
			}
		}

		try {
			request.setRequesterIp(entry.ip);
			request.setUsername(entry.worker.getUsername());
			request.setRequestUrl(req.getRequestURI());
			if (JsonRpcRequest.METHOD_GETWORK.equals(request.getMethod())) {
				JsonRpcResponse response = getResponse(request, req, resp, entry.worker);
				String responseString = response == null ? "" : response.toJSONString();

				if (supportRejectReason && response != null) {
					String rejectReason = response.getRejectReason();
					if (rejectReason != null) {
						resp.setHeader("X-Reject-Reason", rejectReason);
					} else {
						com.shadworld.jsonrpc.JsonRpcResponse.Error error = response.getError();
						if (error != null) {
							resp.setHeader("X-Reject-Reason", error.getMessage());
						}
					}
				}

				resp.setContentLength(responseString.length());
				writer.append(responseString);
			} else {
				handleUknownMethod(writer, request, entry.worker, resp);
			}
		} catch (JsonRpcResponseException e) {
			JSONObject response = buildErrorResponse(request.getId(), e);
			String responseString = response.toString();
			resp.setContentLength(responseString.length());
			if (supportRejectReason) {
				JSONObject error;
				try {
					error = response.getJSONObject("error");
					if (error != null)
						resp.addHeader("X-Reject-Reason", error.getString("message"));
				} catch (JSONException e1) {
					Res.logException(e1);
				}
			}
			writer.append(responseString);
		} catch (ContinuationException e) {
			return;
		}

		writer.close();
	}

	protected void handleUknownMethod(PrintWriter writer, JsonRpcRequest request, Worker worker,
			HttpServletResponse resp) throws JsonRpcResponseException {
		throw new MethodNotFoundException();
	}

	// moved to WorkerAuthenticator class to allow plugging in different auth
	// modules.

	// /**
	// * check authorization and return relevant worker object if auth ok.
	// *
	// * @param req
	// * @return
	// */
	// protected WorkerLoginEntry checkAuth(HttpServletRequest req) {
	// // extract username and pass from header
	// String username;
	// String password;
	// String auth = req.getHeader("Authorization");
	// if (auth == null)
	// return null;
	// String[] parts = Res.decodeAuthString(auth);
	// if (parts == null)
	// return null;
	// // found so get worker from cache;
	// Worker worker = workerProxy.getWorker(parts[0]);
	// if (worker != null) {
	// WorkerLoginEntry entry = new WorkerLoginEntry();
	// entry.ip = req.getRemoteAddr();
	// entry.worker = worker;
	// entry.suppliedPassword = parts[1];
	// return entry;
	// }
	// return null;
	// }

	/**
	 * Execute the actual JSON-RPC request and return the correct response
	 * object.
	 * 
	 * @param request
	 * @param servReq
	 * @param resp
	 * @param worker
	 * @return
	 * @throws JsonRpcResponseException
	 * @throws ContinuationException
	 */
	protected abstract JsonRpcResponse getResponse(JsonRpcRequest request, HttpServletRequest servReq,
			HttpServletResponse resp, Worker worker) throws JsonRpcResponseException, ContinuationException;

	// private void writeResponse(PrintWriter writer, String response) {
	// writer.append(response);
	// }

	public JSONObject buildErrorResponse(Object requestId, JsonRpcResponseException e) {
		return buildErrorResponse(requestId, e.getCode(), e.getMessageForResponse(), e.getData());
	}

	public JSONObject buildErrorResponse(Object requestId, int errorCode, String message, JSONObject data) {
		JSONObject response = new JSONObject();
		try {
			JSONObject error = new JSONObject();
			error.put("code", errorCode);
			error.put("message", message);
			if (data != null)
				error.put("data", data);

			response = new JSONObject();
			response.put("id", requestId == null ? JSONObject.NULL : requestId);
			response.put("error", error);
			response.put("result", JSONObject.NULL);

		} catch (JSONException e) {
			Res.logException(e);
		}

		return response;
	}

	/**
	 * @param longpollServlet
	 *            the longpollServlet to set
	 */
	public void setLongpollServlet(AbstractJsonRpcServlet longpollServlet) {
		this.longpollServlet = longpollServlet;
	}

}
