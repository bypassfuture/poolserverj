package com.shadworld.poolserver;

import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.WorkSource;

public class Cleaner extends Thread {

	private boolean shutdown = false;
	final private PoolServer server;
	private WorkProxy proxy;

	public Cleaner(PoolServer server) {
		super("cache-cleaner");
		this.server = server;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run() {
		try {
			proxy = server.getWorkProxy();
			long lastFlush = System.currentTimeMillis();
			long lastSourceCacheAdjust = System.currentTimeMillis();
			long lastLongpollClean = System.currentTimeMillis();
			while (!shutdown) {
				long now = System.currentTimeMillis();
				if (now > lastFlush + Conf.getFlushCacheInterval()) {
					try {
						server.getWorkerProxy().flushCache();
					} catch (Exception e) {
						// unknown error but we don't want this thread to crash
						// or
						// we'll end up with OOM error
						Res.logError("Exception in Cleaner Thread");
						Res.logException(e);
					}
					try {
						server.getWorkProxy().flushCache();
					} catch (Exception e) {
						// unknown error but we don't want this thread to crash
						// or
						// we'll end up with OOM error
						Res.logError("Exception in Cleaner Thread");
						Res.logException(e);
					}
					lastFlush = now;
				}
				try {
					if (now > lastSourceCacheAdjust + 500) {
						lastSourceCacheAdjust = now;
						for (WorkSource source : proxy.allSources) {
							source.getState().adjust();
						}
					}
				} catch (Exception e) {
					// unknown error but we don't want this thread to crash or
					// we'll
					// end up with OOM error
					Res.logError("Exception in Cleaner Thread");
					Res.logException(e);
				}
				try {
					if (now > lastLongpollClean + 60000 && server.getLongpollHandler() != null) {
						lastLongpollClean = System.currentTimeMillis();
						server.getLongpollHandler().cleanBadConnections();
					}
				} catch (Exception e) {
					// unknown error but we don't want this thread to crash or
					// we'll
					// end up with OOM error
					Res.logError("Exception in Cleaner Thread");
					Res.logException(e);
				}
				try {
					synchronized (this) {
						wait(200);
					}
				} catch (InterruptedException e) {
				}
			}
		} catch (Exception e) {
			Res.logError("Cache Cleaner Thread has crashed.  OOM is likely to occur, restart required.");
			e.printStackTrace();
		}
	}

	public void shutdown() {
		shutdown = true;
		synchronized (this) {
			notifyAll();
		}
	}

}
