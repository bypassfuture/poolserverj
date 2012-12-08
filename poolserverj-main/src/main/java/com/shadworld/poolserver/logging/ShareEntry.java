package com.shadworld.poolserver.logging;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.source.WorkSource;

public class ShareEntry {
	
	public boolean isRealSolution = false;
	
	public final long createTime = System.currentTimeMillis();
	long submitForLoggingTime;
	public final WorkSource source;
	public final Worker worker;
	//Work work;
	public final boolean ourResult;
	public Boolean upstreamResult = null;
	public boolean reportUpstream = true;
	public String reason;
	public final String solution;
	private Throwable exception;
	private int upstreamSubmitFails = 0;
	public final JsonRpcRequest request;
	public final int blocknum;
	
	public ShareEntry(WorkSource source, Worker worker, JsonRpcRequest request,
			boolean ourResult, boolean upstreamResult, String reason, String solution, int blockNum) {
		this.source = source;
		this.worker = worker;
		//this.work = work;
		this.ourResult = ourResult;
		this.upstreamResult = upstreamResult;
		this.reason = reason;
		this.solution = solution;
		this.request = request;
		this.blocknum = blockNum;
	}
	
	public int getUpstreamSubmitFails() {
		return upstreamSubmitFails;
	}

	public void setUpstreamSubmitFails(int upstreamSubmitFails) {
		this.upstreamSubmitFails = upstreamSubmitFails;
	}

	public Throwable getException() {
		return exception;
	}

	public void setException(Throwable exception) {
		this.exception = exception;
	}

	public enum Reason {
		
//		
//		
//		STALE("stale"), UNKNOWN("unknown-work"), DUPLICATE("duplicate");
//
//	
//		private Reason(String value) {
//			this.value = value;
//		}
//
//		/* (non-Javadoc)
//		 * @see java.lang.Enum#toString()
//		 */
//		@Override
//		public String toString() {
//			
//			return super.toString();
//		}
		
		
		
	}
	
}