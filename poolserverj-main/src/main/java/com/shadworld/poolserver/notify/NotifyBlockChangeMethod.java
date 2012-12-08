package com.shadworld.poolserver.notify;

import com.shadworld.poolserver.source.WorkSource;

public abstract class NotifyBlockChangeMethod {

	/**
	 * Should return immediatey.  If time consuming tasks need to be done then do in another handler.
	 * @param delay
	 * @param blockwon
	 * @param source
	 */
	public abstract void notifyBlockChange(long delay, boolean blockwon, String source);
	
	public abstract void shutdown();
	
}
