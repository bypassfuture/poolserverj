package com.shadworld.poolserver.logging;

import java.util.ArrayList;

import com.shadworld.poolserver.conf.Res;

class WorkRequestLoggingThread extends Thread {

	/**
	 * 
	 */
	private final WorkRequestLogger workRequestLogger;

	public WorkRequestLoggingThread(WorkRequestLogger workRequestLogger, String name) {
		super(name);
		this.workRequestLogger = workRequestLogger;
	}

	public void run() {
		ArrayList<RequestEntry> readyResults = new ArrayList();
		while (!this.workRequestLogger.shutdown) {
			boolean exceptionThrown = false;
			try {
				boolean doCommit = false;
				// check if q fullfills flush condition
				synchronized (this.workRequestLogger.q) {
					doCommit = this.workRequestLogger.q.size() >= this.workRequestLogger.maxRequestsToQueueBeforeCommit;
					if (!doCommit) {
						// last entry should be oldest so check it's not past
						// it's use by date
						RequestEntry entry = this.workRequestLogger.q.peek();
						if (entry != null
								&& System.currentTimeMillis() > entry.getSubmitTime()
										+ this.workRequestLogger.maxRequestAgeBeforeCommit)
							doCommit = true;
					}
					// pull results out of queue so we don't have a lock on the
					// q while flushing to database/file
					if (doCommit) {
						readyResults.clear();
						while (!this.workRequestLogger.q.isEmpty())
							readyResults.add(this.workRequestLogger.q.poll());
					}
				}
				if (doCommit) {
					if (!this.workRequestLogger.flushFilesImmedate) // already
																	// done on
																	// submission
						this.workRequestLogger.flushToFile(readyResults); // do
																			// this
																			// first
																			// since
																			// it's
																			// probably
																			// quicker
				}
			} catch (Exception e) {
				exceptionThrown = true;
				Res.logError("Unhandled exception in " + Thread.currentThread().getName());
				e.printStackTrace();
			}
			synchronized (this.workRequestLogger.q) {
				try {
					this.workRequestLogger.q.wait(500); // sleep for a bit or
														// until woken up by a
														// new submit
				} catch (InterruptedException e) {
				}
			}
		}
	}

}