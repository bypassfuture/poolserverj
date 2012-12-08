package com.shadworld.poolserver.source;

import java.io.IOException;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.daemonhandler.SingleWorkExchange;
import com.shadworld.poolserver.source.daemonhandler.WorkExchange;

class LongpollThread extends Thread {
	
	/**
	 * 
	 */
	private final WorkSource workSource;

	/**
	 * @param workSourceOwner
	 */
	LongpollThread(WorkSource workSource) {
		this.workSource = workSource;
	}

	private boolean shutdown = false;
	
	public void run() {
		JsonRpcClient client = Res.getLongpollSharedClient();
		client.setHttpAuthCredentials(this.workSource.getLongPollUrl(), this.workSource.username, this.workSource.password);
		while (!shutdown) {
			GetWorkRequest req = workSource.getDaemonHandler().buildGetWorkRequest();
			WorkExchange ex = (WorkExchange) new SingleWorkExchange(workSource, workSource.getDaemonHandler(), req);
			ex.isLongpoll = true;
			synchronized (this.workSource.longpollThread) {
				JsonRpcRequest request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORK, client.newRequestId());
				try {
					client.doRequest(this.workSource.getLongPollUrl(), request, ex);
					try {
						this.workSource.longpollThread.wait();
					} catch (InterruptedException e) {
					}
				} catch (IOException e) {
					Res.logError("Sleeping for 5 seconds after Longpoll IO exception for URL: " + this.workSource.getLongPollUrl(), e);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e1) {
					}
				}
			}
		}
	}
	
	public void shutdown() {
		shutdown = true;
		synchronized (this.workSource.longpollThread) {
			this.workSource.longpollThread.notifyAll();
			this.workSource.longpollThread.interrupt();
		}
	}
}