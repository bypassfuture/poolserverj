package com.shadworld.poolserver.source;

public abstract class GetWorkRequest {
	
	private long startTime;
	private int numRequested;
	private boolean doneNotify = false;
	private Long reportedBlock;
	private boolean isLongpoll = false;
	private WorkSource source;
	
	public GetWorkRequest(WorkSource source) {
		super();
		this.source = source;
		this.numRequested = source.currentAskRate;
	}

	/**
	 * @return the startTime
	 */
	public long getStartTime() {
		return startTime;
	}

	/**
	 * @param startTime the startTime to set
	 */
	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}
	
	

	/**
	 * @return the numRequested
	 */
	public int getNumRequested() {
		return numRequested;
	}

	/**
	 * @param numRequested the numRequested to set
	 */
	public void setNumRequested(int numRequested) {
		this.numRequested = numRequested;
	}

	/**
	 * @return the doneNotify
	 */
	public boolean isDoneNotify() {
		return doneNotify;
	}
	
	/**
	 * @param doneNotify the doneNotify to set
	 */
	public void setDoneNotify(boolean doneNotify) {
		this.doneNotify = doneNotify;
	}
	
	/**
	 * @return the isLongpoll
	 */
	public boolean isLongpoll() {
		return isLongpoll;
	}

	/**
	 * @param isLongpoll the isLongpoll to set
	 */
	public void setLongpoll(boolean isLongpoll) {
		this.isLongpoll = isLongpoll;
	}

	/**
	 * @return the reportedBlock
	 */
	public Long getReportedBlock() {
		return reportedBlock;
	}

	/**
	 * @param reportedBlock the reportedBlock to set
	 */
	public void setReportedBlock(Long reportedBlock) {
		this.reportedBlock = reportedBlock;
	}

	public abstract void cancel();
	
}