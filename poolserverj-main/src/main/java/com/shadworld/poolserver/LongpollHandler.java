package com.shadworld.poolserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.AsyncContinuation;
import org.eclipse.jetty.server.HttpConnection;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.exception.JsonRpcResponseException;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.servlet.LongpollContinuation;

public class LongpollHandler {

	private boolean abortClean = false;
	final private LinkedHashMap<Continuation, LongpollContinuation> longpolls = new LinkedHashMap();
	final private LinkedHashMap<Continuation, LongpollContinuation> badLongpolls = new LinkedHashMap();

	final ScheduledThreadPoolExecutor expireExecutor;

	WorkProxy proxy;
	BlockChainTracker blockTracker;

	final int timeout;

	final boolean forceCloseConnectionOnResponse = true;

	public LongpollHandler(int maxThreads, int timeout, WorkProxy proxy, BlockChainTracker blockTracker) {
		super();
		this.proxy = proxy;
		this.blockTracker = blockTracker;
		this.blockTracker.longpollHandler = this;

		this.timeout = timeout;
		// this.timeout = 60000;

		expireExecutor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {

			int count = 0;

			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "lp-expiry-monitor-" + count++);
			}
		});
		expireExecutor.allowCoreThreadTimeOut(false);
		expireExecutor.prestartAllCoreThreads();
	}

	public void shutdown() {
		expireExecutor.shutdownNow();
	}

	/**
	 * 
	 * @param continuation
	 * @param worker
	 * @param request
	 * @return
	 */
	public void registerLongpoll(final Continuation continuation, final Worker worker, final JsonRpcRequest request) {

		synchronized (longpolls) {
			// remove instead of get because if found we want to add it back to
			// the end of the queue.
			LongpollContinuation oldCont = longpolls.remove(continuation);
			if (oldCont != null) {
				if (Res.isTrace()) {
					Res.logTrace(
							Res.TRACE_LONGPOLL,
							"Replacing LP continuation: "
									+ continuation.toString().replace("org.eclipse.jetty.server.", ""));
				}
				// cancel the expiry task if it hasn't already run.
				boolean waitForDone = false;
				synchronized (oldCont) {
					if (oldCont.expireTask != null) {
						waitForDone = !oldCont.expireTask.cancel(false);
					}
				}
				if (waitForDone) {
					try {
						boolean concurrentWarning = oldCont.expireTask.get();
						// expiry task has run. Concurrent warning just means
						// that the task
						// ran right about now.
					} catch (Exception e) {
						// do nothing we are just forcing thread to wait for
						// completion of task
					}
				}

			}
			LongpollContinuation newCont = new LongpollContinuation();
			newCont.continuation = continuation;
			newCont.request = request;
			newCont.worker = worker;
			newCont.http = HttpConnection.getCurrentConnection();

			// launch new expiry task
			LongpollTimeoutTask timeoutTask = new LongpollTimeoutTask(newCont);
			newCont.expireTask = expireExecutor.schedule(timeoutTask, timeout, TimeUnit.MILLISECONDS);
			longpolls.put(continuation, newCont);
		}
	}

	public void registerBadLongpoll(final Continuation continuation, final Worker worker, final JsonRpcRequest request) {
		LongpollContinuation newCont = new LongpollContinuation();
		newCont.continuation = continuation;
		newCont.request = request;
		newCont.worker = worker;
		newCont.http = HttpConnection.getCurrentConnection();

		badLongpolls.put(continuation, newCont);
	}

	/**
	 * 
	 * @param waitLock
	 * @param response
	 * @return true if successfuly sent work
	 * @throws JsonRpcResponseException
	 */
	public boolean completeLongpoll(LongpollContinuation waitLock, JsonRpcResponse response)
			throws JsonRpcResponseException {
		if (waitLock.complete)
			return false;
		waitLock.complete = true;
		if (response == null) {
			response = proxy.handleRequest(waitLock.request, waitLock.worker);
			if (response == null) {
				// we can't get a work so return an empty response.
				if (Res.isTrace()) {
					DateFormat df = DateFormat.getTimeInstance(DateFormat.MEDIUM);
					Res.logTrace(Res.TRACE_LONGPOLL_EMPTY_RESPONSE, df.format(new Date())
							+ ": LP sending empty response to worker: " + waitLock.worker.getUsername() + " cont: "
							+ waitLock.continuation.toString().replace("org.eclipse.jetty.server.", ""));
				}
				try {
					waitLock.continuation.complete();
				} catch (Exception e) {
				}
				return false;
			}
		}
		HttpServletResponse resp = (HttpServletResponse) waitLock.continuation.getServletResponse();

		synchronized (waitLock) {
			resp.setHeader("X-Blocknum", String.valueOf(blockTracker.getCurrentBlock()));
			String responseString = response.toJSONString();
			resp.setContentLength(responseString.length());
			if (forceCloseConnectionOnResponse) {
				resp.setHeader("Connection", "close");
			}
			try {
				resp.getWriter().append(responseString);
				if (resp.getWriter().checkError()) {
					// stream may be closed if peer reset connection so we'll
					// recycle the work
					waitLock.worker.registerWorkDelivered(-1);
					return false;
				}
			} catch (IOException e) {

				// failed to send so we need to decrement the
				// worker's delivered stats
				waitLock.worker.registerWorkDelivered(-1);
				Res.logTrace(Res.TRACE_LONGPOLL,
						"LP IOException: " + waitLock.continuation.toString().replace("org.eclipse.jetty.server.", "")
								+ " - " + e.getMessage());
				// shouldn't see this anymore now that we
				// unregister the continuation on expiry.

			} catch (RuntimeIOException e) {
				// failed to send so we need to decrement the
				// worker's delivered stats
				waitLock.worker.registerWorkDelivered(-1);
				Res.logTrace(
						Res.TRACE_LONGPOLL,
						"LP RuntimeIOException (client probably dropped connection): "
								+ waitLock.continuation.toString().replace("org.eclipse.jetty.server.", "") + " - "
								+ e.getMessage());
				e.printStackTrace();
			} catch (Throwable t) {
				// failed to send so we need to decrement the
				// worker's delivered stats
				waitLock.worker.registerWorkDelivered(-1);

				// don't know what this is so we probably want
				// to know about it.
				Res.logDebug("LP Unknown Exception: "
						+ waitLock.continuation.toString().replace("org.eclipse.jetty.server.", "") + " - "
						+ t.getMessage());
				Res.logException(t);

			}
			try {
				// might see: java.lang.IllegalStateException:
				// COMPLETE,resumed,expired
				waitLock.continuation.complete();
				waitLock.complete = true;
			} catch (Exception e) {
				if (Res.isDebug()) {
					Res.logException(e);
				}
			}
		}
		return true;
	}

	public void cleanBadConnections() {
		if (abortClean)
			return;
		
		int closed = 0;
		int total = 0;
		int checked = 0;
		long start = System.currentTimeMillis();
		boolean aborted = false;
		
		synchronized (longpolls) {
			total = longpolls.size();
			Iterator<Entry<Continuation, LongpollContinuation>> i = longpolls.entrySet().iterator();
			ByteBuffer buffer = ByteBuffer.allocate(1);
			while (i.hasNext() && !abortClean) {
				checked++;
				Entry<Continuation, LongpollContinuation> entry = i.next();
				try {
					SocketChannel ch = (SocketChannel) entry.getValue().http.getEndPoint().getTransport();
					buffer.clear();
					try {
						//attempt to read from the socket.  Normally this should not read anything
						//and return empty buffer.  If the socket is closed it will throw an exception.
						ch.read(buffer);
						buffer.clear();
						buffer.put((byte) 0);
						ch.write(buffer);
						
					} catch (IOException e) {
						//close and remove the connection from the LP queue.
						closed++;
						try {
							entry.getValue().complete = true;
							entry.getValue().expireTask.cancel(false);
							entry.getValue().continuation.complete();
						} catch (Exception e1) {
							//suppress, this will probably always throw but dev isn't sure
							//if not calling complete() will leave stuff not cleaned up properly.
						}
						i.remove();
					}
				} catch (Exception e) {
					//something unexpected so let see it.
					Res.logException(e);
				}
			}
			aborted = abortClean;
		}
		if (Res.isTrace()) {
			Res.logTrace(Res.TRACE_LONGPOLL, "Bad longpoll cleaner checked " + checked + "/" + total + " connections in " + (System.currentTimeMillis() - start) + "ms. Found "
					+ closed + "/" + checked + " dead connections.  Aborted by flush request: " + aborted);
		}
	}

	public List<LongpollContinuation> flushQueue() {
		abortClean = true;
		synchronized (longpolls) {
			ArrayList<LongpollContinuation> waitLocks = new ArrayList(longpolls.size());
			for (LongpollContinuation entry : longpolls.values()) {
				if (entry.expireTask.cancel(false)) {
					waitLocks.add(entry);
					entry.worker.removeLongpoll();
				}
			}
			longpolls.clear();
			abortClean = false;
			return waitLocks;
		}
	}

	public List<LongpollContinuation> flushBadQueue() {
		synchronized (badLongpolls) {
			ArrayList<LongpollContinuation> waitLocks = new ArrayList(badLongpolls.values());
			badLongpolls.clear();
			return waitLocks;
		}
	}

	public int getNumLongpollConnections() {
		return longpolls.size();
	}

	private class LongpollTimeoutTask implements Callable<Boolean> {

		LongpollContinuation cont;

		public LongpollTimeoutTask(LongpollContinuation cont) {
			super();
			this.cont = cont;
		}

		@Override
		public Boolean call() throws Exception {
			boolean concurrentWarning;
			synchronized (longpolls) {
				// if it's not there then it must be in the process of being
				// cancelled
				// and renewed inside registerLongpoll()
				concurrentWarning = longpolls.remove(cont.continuation) == null;
				cont.worker.removeLongpoll();
			}
			if (Res.isTrace()) {
				DateFormat df = DateFormat.getTimeInstance(DateFormat.MEDIUM);
				Res.logTrace(Res.TRACE_LONGPOLL_TIMEOUT,
						df.format(new Date()) + ": LP timeout for worker: " + cont.worker.getUsername() + " cont: "
								+ cont.continuation.toString().replace("org.eclipse.jetty.server.", ""));
			}
			synchronized (cont) {
				completeLongpoll(cont, null);
			}
			return concurrentWarning;
		}

	}

}
