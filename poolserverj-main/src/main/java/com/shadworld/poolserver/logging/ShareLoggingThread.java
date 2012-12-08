package com.shadworld.poolserver.logging;

import java.util.ArrayList;

import org.apache.commons.lang.SystemUtils;

import com.shadworld.poolserver.conf.Res;

class ShareLoggingThread extends Thread {

	/**
	 * 
	 */
	private final ShareLogger shareLogger;
	private final int highPriority;

	boolean doBlockChangeWait = false;
	long blockChangeSleepTime = 4000;
	long blockChangeSleepStart = 0;

	public ShareLoggingThread(ShareLogger workResultLogger, String name) {
		super(name);
		// TODO Auto-generated constructor stub
		this.shareLogger = workResultLogger;
		if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_UNIX)
			highPriority = 9;
		else if (SystemUtils.IS_OS_WINDOWS)
			highPriority = 9;
		else
			highPriority = 6;
	}

	public void run() {
		ArrayList<ShareEntry> readyResults = new ArrayList();
		int consecutiveCacheExcesses = 0;
		int lastExcessiveCacheSize = 1;
		int consecutiveCacheGood = 0;
		while (!this.shareLogger.shutdown) {
			boolean exceptionThrown = false;
			try {
				if (this.shareLogger.shutdownRequested) // ensure one last
														// cycle to clear
														// everything out on
														// shutdown.
					this.shareLogger.shutdown = true;
				boolean doCommit = false;

				if (doBlockChangeWait) {
					if (blockChangeSleepStart == 0) {
						blockChangeSleepStart = System.currentTimeMillis();
						if (Res.isDebug()) {
							Res.logInfo("Pausing Share logging due to block change.");
						}
					} else if (System.currentTimeMillis() > blockChangeSleepStart + blockChangeSleepTime) {
						blockChangeSleepStart = 0;
						doBlockChangeWait = false;
						if (Res.isDebug()) {
							Res.logInfo("Resuming Share logging.");
						}
					}
				}

				if (!doBlockChangeWait) {
					// check if q fullfills flush condition
					synchronized (this.shareLogger.q) {
						doCommit = this.shareLogger.shutdownRequested
								|| this.shareLogger.q.size() >= this.shareLogger.maxEntrysToQueueBeforeCommit;
						if (!doCommit) {
							// last entry should be oldest so check it's not
							// past
							// it's use by date
							ShareEntry entry = this.shareLogger.q.peek();
							if (entry != null
									&& System.currentTimeMillis() > entry.submitForLoggingTime
											+ this.shareLogger.maxEntryAgeBeforeCommit)
								doCommit = true;
						}
						// pull results out of queue so we don't have a lock on
						// the
						// q while flushing to database/file
						if (doCommit) {
							readyResults.clear();
							while (!this.shareLogger.q.isEmpty())
								readyResults.add(this.shareLogger.q.poll());
						}
					}

					if (doCommit) {
						boolean priority = false;
						if (readyResults.size() >= (this.shareLogger.maxEntrysToQueueBeforeCommit * 1.5)) {
							// Under extreme load the server can accept work
							// submits
							// faster than
							// they can be written to database. In this case in
							// the
							// time it takes
							// to flush the queue of work submits more new
							// submits
							// come in than were
							// flushed. First we try a priority boost. If that
							// doesn't
							// help by next flush cycle then we resort to
							// throttling
							// the receiving
							// threads for work submits.
							setPriority(highPriority);
							priority = true;
							Res.logDebug("priority db flush");
							if (this.shareLogger.maxEntrysToQueueBeforeCommit > 1) {
								consecutiveCacheExcesses++;
								consecutiveCacheGood = 0;
								if (consecutiveCacheExcesses > 0) {
									Res.throttleWorkSubmits(true);
									if (consecutiveCacheExcesses > 1) {
										float loadFactor = lastExcessiveCacheSize == 0 ? readyResults.size()
												/ shareLogger.maxEntrysToQueueBeforeCommit
												: (readyResults.size() - shareLogger.maxEntrysToQueueBeforeCommit)
														/ (lastExcessiveCacheSize - shareLogger.maxEntrysToQueueBeforeCommit);
										Res.throttleWorkSubmitsHarder(loadFactor);
									}
								}
								lastExcessiveCacheSize = readyResults.size();
							}
						} else {
							consecutiveCacheExcesses = 0;
							consecutiveCacheGood++;
							if (consecutiveCacheGood > 3 && consecutiveCacheGood % 3 == 0) {
								Res.throttleWorkSubmitsSofter();
							}
							Res.throttleWorkSubmits(false);
						}
						if (!this.shareLogger.flushFilesImmedate) // already
																	// done on
																	// submission
							this.shareLogger.flushToFile(readyResults); // do
																		// this
																		// first
																		// since
																		// it's
																		// probably
																		// quicker

						// this.workResultLogger.
						// this.workResultLogger.flushToDataBase(readyResults);
						// this.workResultLogger.mysqlBulkLoadLocalPipedStreams(readyResults);
						// this.workResultLogger.mysqlBulkLoadLocalFifo(readyResults);
						// this.workResultLogger.mysqlBulkLoadSerial(readyResults);
						// this.workResultLogger.mysqlBulkLoadDirect(readyResults,
						// true, false, "/tmp/mysql/fifo");
						// this.workResultLogger.postgresBulkLoadDirect(readyResults,
						// false, true, "/tmp/mysql/fifo.tmp");
						// this.workResultLogger.postgresBulkLoadLocal(readyResults,
						// true, false, "/tmp/mysql/fifo");

						synchronized (shareLogger.dbFlushEngine) {
							shareLogger.dbFlushEngine.flushToDatabase(readyResults);
							if (shareLogger.updateCounters)
								shareLogger.dbFlushEngine.updateCounters(readyResults);
						}

						if (priority) {
							setPriority(Thread.NORM_PRIORITY);
						}
					}
				}
				if (doBlockChangeWait) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				} else {
					synchronized (this.shareLogger.q) {
						try {
							this.shareLogger.q.wait(500); // sleep for a bit or
															// until woken up by
															// a new submit
						} catch (InterruptedException e) {
						}
					}
				}

			} catch (Exception e) {
				exceptionThrown = true;
				Res.logError("Unhandled exception in " + Thread.currentThread().getName());
				e.printStackTrace();
				try {
					Thread.sleep(100);
				} catch (InterruptedException e1) {
				}
			}
		}
	}

}