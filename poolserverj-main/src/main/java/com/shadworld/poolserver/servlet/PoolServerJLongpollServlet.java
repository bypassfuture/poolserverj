package com.shadworld.poolserver.servlet;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.HttpConnection;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.exception.ContinuationException;
import com.shadworld.jsonrpc.exception.InvalidJsonRpcRequestException;
import com.shadworld.jsonrpc.exception.JsonRpcResponseException;
import com.shadworld.jsonrpc.exception.MethodNotFoundException;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.LongpollHandler;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;

public class PoolServerJLongpollServlet extends AbstractJsonRpcServlet {

	final BlockChainTracker tracker;
	final LongpollHandler longpollHandler;

	public PoolServerJLongpollServlet(PoolServer poolServer, String longPollUrl, String rollNTimeString) {
		super(poolServer, longPollUrl, rollNTimeString);
		tracker = poolServer.getBlockTracker();
		longpollHandler = poolServer.getLongpollHandler();
		longpollPath = Conf.get().getHttpLPJsonRpcPath();
		isLongpoll = true;
	}

	// /*
	// * (non-Javadoc)
	// *
	// * @see
	// *
	// javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest
	// * , javax.servlet.http.HttpServletResponse)
	// */
	// @Override
	// protected void doGet(HttpServletRequest req, HttpServletResponse resp)
	// throws ServletException, IOException {
	// doPost(req, resp);
	// }

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.shadworld.poolserver.BitcoinPoolServlet#getResponse(com.shadworld
	 * .jsonrpc.JsonRpcRequest, javax.servlet.http.HttpServletRequest,
	 * javax.servlet.http.HttpServletResponse,
	 * com.shadworld.poolserver.entity.Worker)
	 */
	@Override
	protected JsonRpcResponse getResponse(JsonRpcRequest request, HttpServletRequest servReq, HttpServletResponse resp,
			Worker worker) throws JsonRpcResponseException, ContinuationException {

		// LongpollContinuation cont = new LongpollContinuation();
		Continuation continuation = ContinuationSupport.getContinuation(servReq);
		// int lpTimeout = Conf.get().getHttpLPTimeout();
		int lpTimeout = 30000;

		if (Res.isTrace()) {
			DateFormat df = DateFormat.getTimeInstance(DateFormat.MEDIUM);
			Res.logTrace(
					Res.TRACE_LONGPOLL,
					df.format(new Date()) + ": LP "
							+ ("request from: ")
							+ worker.getUsername() + "@[" + servReq.getRemoteAddr() + ":" + servReq.getRemotePort()
							+ "], " + continuation.toString().replace("org.eclipse.jetty.server.", "")
			// + " " + "isInitial: " + continuation.isInitial() +
			// ", isExpired: " + continuation.isExpired() + ", isResumed: "
			// + continuation.isResumed() + ", isSuspended: " +
			// continuation.isSuspended() + ", "
			);
		}

		//if (continuation.isInitial()) { // skip all this business if this
										// is a continuation.

			// HttpConnection.getCurrentConnection().scheduleTimeout(task,
			// timeoutMs)

			String method = request.getMethod();
			if (!JsonRpcRequest.METHOD_GETWORK.equals(method))
				throw new JsonRpcResponseException(-345, "only getwork method supported via longpoll", null);

			if (worker.allowNewLongpoll()) {
				longpollHandler.registerLongpoll(continuation, worker, request);
			} else {
				longpollHandler.registerBadLongpoll(continuation, worker, request);
			}
			continuation.setTimeout(0);
			continuation.suspend(resp);
			throw new ContinuationException();

//		} else {
//			// we should never see this.
//			Res.logError("LP continuation reached LP servlet but is not in 'initial' state: "
//					+ continuation.toString().replace("org.eclipse.jetty.server.", ""));
//			return null;
//		}
	}

}
