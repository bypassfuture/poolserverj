package com.shadworld.poolserver.source;

import com.shadworld.poolserver.entity.Work;

public class WorkEntry {
	/**
	 * 
	 */
	private final WorkSource workSource;
	final Work work;
	final long getTime;
	final long blockNumber;

	public WorkEntry(WorkSource workSource, Work work, long getTime, Long blockNumber) {
		super();
		this.workSource = workSource;
		this.work = work;
		this.getTime = getTime;
		this.blockNumber = blockNumber == null || blockNumber == -1 ? this.workSource.myCurrentBlock : blockNumber;
	}

	/**
	 * @return the work
	 */
	public Work getWork() {
		return work;
	}

	/**
	 * @return the getTime
	 */
	public long getGetTime() {
		return getTime;
	}

	/**
	 * @return the blockNumber
	 */
	public long getBlockNumber() {
		return blockNumber;
	}
	
	

}