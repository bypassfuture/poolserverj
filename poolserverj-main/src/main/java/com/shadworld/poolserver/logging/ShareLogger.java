package com.shadworld.poolserver.logging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.StringBufferInputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.io.IOUtils;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import com.mysql.jdbc.Connection;
import com.mysql.jdbc.Statement;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.db.shares.DefaultPreparedStatementSharesDBFlushEngine;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.sql.PostgreSql;
import com.shadworld.sql.Sql;
import com.shadworld.utils.FileUtil;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public class ShareLogger {

	private static String NEW_LINE = System.getProperty("line.separator", "\n");

	final ShareLoggingThread workResultLoggingThread;
	DefaultPreparedStatementSharesDBFlushEngine dbFlushEngine;

	boolean shutdown = false;
	boolean shutdownRequested = false;

	int maxEntrysToQueueBeforeCommit = 5;
	long maxEntryAgeBeforeCommit = 5000;
	private boolean usePushPoolCompatibleFormat = false;
	boolean flushFilesImmedate = false;

	// private Sql sql;
	private String insertQuery;
	private int numQueryParams = -1;
	private File outputFile;
	boolean updateCounters;

	final ArrayDeque<ShareEntry> q = new ArrayDeque();

	public ShareLogger() {
		super();
		workResultLoggingThread = new ShareLoggingThread(this, "work-result-logging-handler");
	}

	public void start() {
		workResultLoggingThread.start();
	}

	public void submitLogEntry(ShareEntry entry) {
		entry.submitForLoggingTime = System.currentTimeMillis();
		synchronized (q) {
			q.offerLast(entry);
		}
		if (flushFilesImmedate)
			flushToFile(Collections.singletonList(entry));
		synchronized (q) {
			q.notifyAll();
		}
		if (Res.isThrottleWorkSubmits()) {
			try {
				Thread.sleep(Res.throttleWorkSubmitTime());
			} catch (InterruptedException e) {

			}
		}
	}
	
	public void notifyBlockChange() {
		workResultLoggingThread.doBlockChangeWait = true;
	}

	/**
	 * call during shutdown to start flushing cache straight away.
	 */
	public void flushImmediate() {
		synchronized (q) {
			flushToFile(q);
			if (dbFlushEngine != null) {
				synchronized (dbFlushEngine) {
					dbFlushEngine.flushToDatabase(q);
					//flushToDataBase(q);
				}
			}
			q.notifyAll();
		}
	}

	public void shutdown(boolean isFinal) {
		// shutdown = true;
		shutdownRequested = true;
		workResultLoggingThread.setPriority(Thread.MAX_PRIORITY);
		synchronized (q) {
			q.notifyAll();
		}
		Thread.yield();
		synchronized (this) {
			notifyAll();
		}
		synchronized (q) {
			if (!flushFilesImmedate) // already done on submission
				flushToFile(q);
			flushToDataBase(q);
			q.notifyAll();
		}
		if (isFinal)
			dbFlushEngine.shutdown();
	}

	synchronized void flushToFile(Collection<ShareEntry> results) {
		boolean logToFile = true;
		if (outputFile == null || !outputFile.exists() || results.isEmpty())
			logToFile = false;
		if (outputFile != null && !outputFile.canWrite()) {
			Res.logError("Cannot write to output file: " + outputFile.getAbsolutePath());
			logToFile = false;
		}
		if (!logToFile && !Res.isLogSharesToStdout())
			return;

		StringBuilder sb = new StringBuilder();
		FileWriter writer = null;
		// System.out.println("writing file");
		try {
			if (logToFile)
				writer = new FileWriter(outputFile, true);
			final String separator = usePushPoolCompatibleFormat ? " " : ",";

			for (ShareEntry entry : results) {
				buildLine(entry, sb, separator, "null");
				String out = sb.toString();
				if (logToFile)
					writer.write(out);
				if (Res.isLogSharesToStdout())
					System.out.print(out);
				sb.setLength(0);
			}
			if (logToFile)
				writer.close();

		} catch (IOException ex) {
			Res.logError("Enable to write shares to file: " + outputFile.getAbsolutePath(), ex);
		} finally {
			try {
				if (writer != null)
					writer.close();
			} catch (IOException ex) {
				Res.logError("Problem writing shares to file: " + outputFile.getAbsolutePath(), ex);
			}
		}
	}

	private void buildLine(final ShareEntry entry, final StringBuilder sb, final String separator,
			final String nullString) {
		if (usePushPoolCompatibleFormat)
			sb.append(LogUtil.getCompatibleTimeValue(entry.createTime)).append(separator);
		else
			sb.append(com.shadworld.util.Time.sqlDateTimeFormat.format(new Date(entry.createTime))).append(separator);
		sb.append(entry.request.getRequesterIp()).append(separator);
		sb.append(entry.worker.getUsername()).append(separator);
		if (!usePushPoolCompatibleFormat) {
			sb.append(entry.source == null ? nullString : entry.source.getName()).append(separator);
			sb.append(entry.blocknum).append(separator);
		}
		sb.append(LogUtil.getCompatibleValue(entry.ourResult, "-", nullString, usePushPoolCompatibleFormat)).append(
				separator);
		sb.append(LogUtil.getCompatibleValue(entry.upstreamResult, "-", nullString, usePushPoolCompatibleFormat))
				.append(separator);
		sb.append(LogUtil.getCompatibleValue(entry.reason, "-", nullString, usePushPoolCompatibleFormat)).append(
				separator);
		sb.append(entry.solution).append(NEW_LINE);
	}
	
	void postgresBulkLoadDirect(final Collection<ShareEntry> results, final boolean fifoMode, final boolean replaceFile, final String filename) {
		int size = results.size();
		long start = System.currentTimeMillis();
		
		final String PG_BULKLOAD_DIRECT_FIFO_QUERY = 
				"COPY poolserverj_native.shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution) FROM '" + filename + "'";

		if (Res.isDebug())
			Res.logInfo("Doing database flush for Work Results: " + size);
		
		Sql sql = Conf.getSharesSql();
		try {
			sql.prepareConnection();
			
			
			final CountDownLatch writeLatch = new CountDownLatch(fifoMode ? 1 : 2);
			final CountDownLatch executeLatch = new CountDownLatch(1);

			Thread writeThread = new Thread("db-stream-writer") {
				public void run() {
					Writer writer = null;
					try {
						writeLatch.countDown();
						
						File file = new File(filename);
						if (!fifoMode && replaceFile && file.exists())
							file.delete();
						else if (!fifoMode) {
							file.getParentFile().mkdirs();
						}
						writer = new FileWriter(file);
						writeToMysqlStream(writer, results, true);
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
					}
					writeLatch.countDown();

					while (executeLatch.getCount() != 0) {
						try {
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}

				}
			};
			writeThread.start();
			
			while (writeLatch.getCount() != 0) {
				try {
					writeLatch.await();
				} catch (InterruptedException e) {
					// too bad
				}
			}
			
			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY); 
			
			//sql.stmt.addBatch("START TRANSACTION");
			//sql.stmt.addBatch("SET UNIQUE_CHECKS=0");
			//sql.stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
			sql.stmt.execute(PG_BULKLOAD_DIRECT_FIFO_QUERY);
			//sql.stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
			//sql.stmt.addBatch("SET UNIQUE_CHECKS=1");
			//sql.stmt.addBatch("COMMIT");
			//sql.stmt.executeBatch();

			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY);

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
	}

	void postgresBulkLoadLocal(final Collection<ShareEntry> results, final boolean fifoMode, final boolean replaceFile, final String filename) {
		int size = results.size();
		long start = System.currentTimeMillis();
		
		if (Res.isDebug())
			Res.logInfo("Doing database flush for Work Results: " + size);
		
		Sql sql = Conf.getSharesSql();
		try {
			sql.prepareConnection();
			final CopyManager copyManager = new CopyManager(sql.conn.unwrap(BaseConnection.class));
			final File file = new File(filename);
			
			final CountDownLatch writeLatch = new CountDownLatch(fifoMode ? 1 : 2);
			final CountDownLatch executeLatch = new CountDownLatch(1);

			Thread writeThread = new Thread("db-stream-writer") {
				public void run() {
					Writer writer = null;
					try {
						writeLatch.countDown();
						
						if (!fifoMode && replaceFile && file.exists())
							file.delete();
						else if (!fifoMode) {
							file.getParentFile().mkdirs();
						}
						writer = new FileWriter(file);
						writeToMysqlStream(writer, results, true);
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
					}
					writeLatch.countDown();

					while (executeLatch.getCount() != 0) {
						try {
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}

				}
			};
			writeThread.start();
			
			while (writeLatch.getCount() != 0) {
				try {
					writeLatch.await();
				} catch (InterruptedException e) {
					// too bad
				}
			}
			
			final String PG_BULKLOAD_LOCAL_FIFO_QUERY = 
					"COPY poolserverj_native.shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution) FROM STDIN";

			copyManager.copyIn(PG_BULKLOAD_LOCAL_FIFO_QUERY, new FileInputStream(file));
			
			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY); 
			
			//sql.stmt.addBatch("START TRANSACTION");
			//sql.stmt.addBatch("SET UNIQUE_CHECKS=0");
			//sql.stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
			//sql.stmt.execute(PG_BULKLOAD_DIRECT_FIFO_QUERY);
			//sql.stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
			//sql.stmt.addBatch("SET UNIQUE_CHECKS=1");
			//sql.stmt.addBatch("COMMIT");
			//sql.stmt.executeBatch();

			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY);

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
	}

	// private static final String query =
	// "LOAD DATA INFILE '/tmp/fifo' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";
	private static final String MYSQL_BULKLOAD_LOCAL_QUERY = 
			"LOAD DATA LOCAL INFILE '/tmp/fif' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";
	private static final String MYSQL_BULKLOAD_LOCAL_FIFO_QUERY = 
			"LOAD DATA LOCAL INFILE '/tmp/fifo' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";
	private static final String MYSQL_BULKLOAD_LOCAL_FILE_QUERY = 
			"LOAD DATA LOCAL INFILE 'tmp/mysql.tmp' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";
	

	void mysqlBulkLoadDirect(final Collection<ShareEntry> results, final boolean fifoMode, final boolean replaceFile, final String filename) {
		int size = results.size();
		long start = System.currentTimeMillis();
		
		final String MYSQL_BULKLOAD_DIRECT_FIFO_QUERY = 
				"LOAD DATA INFILE '" + filename + "' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";

		if (Res.isDebug())
			Res.logInfo("Doing database flush for Work Results: " + size);
		
		Sql sql = Conf.getSharesSql();
		try {
			sql.prepareConnection();
			
			
			final CountDownLatch writeLatch = new CountDownLatch(fifoMode ? 1 : 2);
			final CountDownLatch executeLatch = new CountDownLatch(1);

			Thread writeThread = new Thread("db-stream-writer") {
				public void run() {
					Writer writer = null;
					try {
						writeLatch.countDown();
						
						File file = new File(filename);
						if (!fifoMode && replaceFile && file.exists())
							file.delete();
						else if (!fifoMode) {
							file.getParentFile().mkdirs();
						}
						writer = new FileWriter(file);
						writeToMysqlStream(writer, results, true);
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
					}
					writeLatch.countDown();

					while (executeLatch.getCount() != 0) {
						try {
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}

				}
			};
			writeThread.start();
			
			while (writeLatch.getCount() != 0) {
				try {
					writeLatch.await();
				} catch (InterruptedException e) {
					// too bad
				}
			}
			
			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY); 
			
			sql.stmt.addBatch("START TRANSACTION");
			sql.stmt.addBatch("SET UNIQUE_CHECKS=0");
			sql.stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
			sql.stmt.addBatch(MYSQL_BULKLOAD_DIRECT_FIFO_QUERY);
			sql.stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
			sql.stmt.addBatch("SET UNIQUE_CHECKS=1");
			sql.stmt.addBatch("COMMIT");
			sql.stmt.executeBatch();

			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY);

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
	}
	
	
	void mysqlBulkLoadLocalPipedStreams(final Collection<ShareEntry> results) {
		int size = results.size();
		long start = System.currentTimeMillis();

		if (Res.isDebug())
			Res.logInfo("Doing database flush for Work Results: " + size);
		
		Sql sql = Conf.getSharesSql();
		try {
			sql.prepareConnection();
			
			// InputStream in = IOUtils.toInputStream(sb.toString());
			// String query =
			// "LOAD DATA LOCAL INFILE 'dummy' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";

			final PipedInputStream pin = new PipedInputStream();
			final Statement stmt = (Statement) sql.stmt.unwrap(com.mysql.jdbc.Statement.class);
			stmt.setLocalInfileInputStream(pin);
			
			final CountDownLatch writeLatch = new CountDownLatch(1);
			final CountDownLatch executeLatch = new CountDownLatch(1);

			Thread writeThread = new Thread("db-stream-writer") {
				public void run() {
					Writer writer = null;
					try {
						//stmt.setLocalInfileInputStream(pin);
						writeLatch.countDown();
						final PipedOutputStream pout = new PipedOutputStream(pin);
						
						//writer = new FileWriter("/tmp/fifo");
						writer = new PrintWriter(pout, true);
						writeToMysqlStream(writer, results, true);
						// stmt.setLocalInfileInputStream(IOUtils.toInputStream(sb.toString()));
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
					}
					//

					while (executeLatch.getCount() != 0) {
						try {
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}

				}
			};
			writeThread.start();
			
//			sql.stmt.addBatch("START TRANSACTION");
//			sql.stmt.addBatch("SET UNIQUE_CHECKS=0");
//			sql.stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
//			sql.stmt.executeBatch();
			
			while (writeLatch.getCount() != 0) {
				try {
					writeLatch.await();
					try {
						while (pin.available() <= 0 || stmt.getLocalInfileInputStream() != pin)
							Thread.sleep(1);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				} catch (InterruptedException e) {
					// too bad
				}
			}
			
			//no other way to do this except with seperate call because executeBatch internally
			//creates a different statement object and doesn't pass on the localInfileInputStream.
			sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY); 
			
//			sql.stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
//			sql.stmt.addBatch("SET UNIQUE_CHECKS=1");
//			sql.stmt.addBatch("COMMIT");
//			sql.stmt.executeBatch();

			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_QUERY);

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
	}

	void mysqlBulkLoadLocalFifo(final Collection<ShareEntry> results) {
		int size = results.size();
		long start = System.currentTimeMillis();

		if (Res.isDebug())
			Res.logInfo("Doing database flush for Work Results: " + size);
		
		Sql sql = Conf.getSharesSql();
		try {
			sql.prepareConnection();
			final Statement stmt = (Statement) sql.stmt.unwrap(Statement.class);
			// InputStream in = IOUtils.toInputStream(sb.toString());
			// String query =
			// "LOAD DATA LOCAL INFILE 'dummy' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";

			//final PipedInputStream pin = new PipedInputStream();
			
			final CountDownLatch writeLatch = new CountDownLatch(1);
			final CountDownLatch executeLatch = new CountDownLatch(1);

			Thread writeThread = new Thread("db-stream-writer") {
				public void run() {
					Writer writer = null;
					try {
						//stmt.setLocalInfileInputStream(pin);
						writeLatch.countDown();
						//final PipedOutputStream pout = new PipedOutputStream(pin);
						
						writer = new FileWriter("/tmp/fifo");
						//writer = new PrintWriter(pout, true);
						writeToMysqlStream(writer, results, true);
						// stmt.setLocalInfileInputStream(IOUtils.toInputStream(sb.toString()));
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
					}
					//

					while (executeLatch.getCount() != 0) {
						try {
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}

				}
			};
			writeThread.start();
			
			sql.stmt.addBatch("START TRANSACTION");
			sql.stmt.addBatch("SET UNIQUE_CHECKS=0");
			sql.stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
			sql.stmt.executeBatch();
			
			while (writeLatch.getCount() != 0) {
				try {
					writeLatch.await();
//					try {
//						while (pin.available() <= 0)
//							Thread.sleep(1);
//					} catch (IOException e) {
//						// TODO Auto-generated catch block
//						e.printStackTrace();
//					}
				} catch (InterruptedException e) {
					// too bad
				}
			}
			sql.stmt.addBatch(MYSQL_BULKLOAD_LOCAL_FIFO_QUERY);
			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_FIFO_QUERY);
			sql.stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
			sql.stmt.addBatch("SET UNIQUE_CHECKS=1");
			sql.stmt.addBatch("COMMIT");
			sql.stmt.executeBatch();
			
			
//			String q = "SET UNIQUE_CHECKS=0; ALTER TABLE shares DISABLE KEYS;" + MYSQL_BULKLOAD_LOCAL_FIFO_QUERY
//					+ "; ALTER TABLE shares ENABLE KEYS; SET UNIQUE_CHECKS=1;";
//			sql.stmt.execute(q);
			

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
	}

	
	void mysqlBulkLoadSerial(final Collection<ShareEntry> results) {
		int size = results.size();
		long start = System.currentTimeMillis();

		if (Res.isDebug())
			Res.logInfo("Doing database flush for Work Results: " + size);
		// final StringBuilder sb = new StringBuilder(380 * results.size());
		

		Sql sql = Conf.getSharesSql();
		try {
			sql.prepareConnection();
			final Statement stmt = (Statement) sql.stmt.unwrap(Statement.class);
			// InputStream in = IOUtils.toInputStream(sb.toString());
			// String query =
			// "LOAD DATA LOCAL INFILE 'dummy' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";

			//final PipedInputStream pin = new PipedInputStream();
			
			final CountDownLatch writeLatch = new CountDownLatch(1);
			final CountDownLatch executeLatch = new CountDownLatch(1);

			Thread writeThread = new Thread("db-stream-writer") {
				public void run() {
					Writer writer = null;
					try {
						//final PipedOutputStream pout = new PipedOutputStream(pin);
						//stmt.setLocalInfileInputStream(pin);
						File file = new File("tmp/mysql.tmp");
						if (file.exists())
							file.delete();
						else
							file.getParentFile().mkdirs();
						//file.createNewFile();
						writer = new FileWriter("tmp/mysql.tmp", false);
						//writer = new PrintWriter(pout, true);
						writeToMysqlStream(writer, results, true);
						// stmt.setLocalInfileInputStream(IOUtils.toInputStream(sb.toString()));
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
					}
					writeLatch.countDown();
					
					//

					while (executeLatch.getCount() != 0) {
						try {
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}

				}
			};
			writeThread.start();
			
			sql.stmt.addBatch("START TRANSACTION");
			sql.stmt.addBatch("SET UNIQUE_CHECKS=0");
			sql.stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
			sql.stmt.executeBatch();
			
			while (writeLatch.getCount() != 0) {
				try {
					writeLatch.await();
					//Thread.sleep(500);
				} catch (InterruptedException e) {
					// too bad
				}
			}
			sql.stmt.addBatch(MYSQL_BULKLOAD_LOCAL_FILE_QUERY);
			//sql.stmt.execute(MYSQL_BULKLOAD_LOCAL_FILE_QUERY);
			
			sql.stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
			sql.stmt.addBatch("SET UNIQUE_CHECKS=1");
			sql.stmt.addBatch("COMMIT");
			sql.stmt.executeBatch();

			//sql.stmt.execute(query);

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
	}
	
	private void writeToMysqlStream(final Writer writer, final Collection<ShareEntry> results, final boolean writeByLine) throws IOException {
		final StringBuilder sb = new StringBuilder(writeByLine ? 380 : 380 * results.size());
		final String separator = usePushPoolCompatibleFormat ? " " : "\t";
		
		for (final ShareEntry entry : results) {
			if (usePushPoolCompatibleFormat)
				sb.append(LogUtil.getCompatibleTimeValue(entry.createTime)).append(separator);
			else
				sb.append(com.shadworld.util.Time.sqlDateTimeFormat.format(new Date(entry.createTime)))
						.append(separator);
			sb.append(entry.request.getRequesterIp()).append(separator);
			sb.append(entry.worker.getUsername()).append(separator);
			if (!usePushPoolCompatibleFormat) {
				sb.append(entry.source == null ? "\\N" : entry.source.getName()).append(separator);
				sb.append(entry.blocknum).append(separator);
			}
			sb.append(entry.ourResult ? 1 : 0).append(separator);
			sb.append(entry.upstreamResult == null ? "\\N" : entry.upstreamResult ? 1 : 0).append(
					separator);
			sb.append(LogUtil.getCompatibleValue(entry.reason, "-", "\\N", usePushPoolCompatibleFormat))
					.append(separator);
			sb.append(entry.solution).append(NEW_LINE);
			if (writeByLine) {
				writer.append(sb.toString());
				sb.setLength(0);
			}
		}
		if (!writeByLine) {
			writer.append(sb.toString());
		}
	}

	void flushToDataBase(Collection<ShareEntry> results) {
		if (results.isEmpty())
			return;
		Sql sql = Conf.getSharesSql();
		
		int size = results.size();
		long start = System.currentTimeMillis();

		Res.logDebug("Doing database flush for Work Results: " + results.size());
		if (sql == null || insertQuery == null)
			return;
		PreparedStatement stmt = null;
		try {
			sql.prepareConnection();
			stmt = sql.conn.prepareStatement(insertQuery);
			for (ShareEntry entry : results) {
				// stmt.setTime(8, new Time(98l))
				stmt.setString(1, entry.request.getRequesterIp());
				stmt.setString(2, entry.worker.getUsername());
				if (usePushPoolCompatibleFormat) {
					stmt.setString(3,
							LogUtil.getCompatibleValue(entry.ourResult, null, null, usePushPoolCompatibleFormat));
					stmt.setString(4,
							LogUtil.getCompatibleValue(entry.upstreamResult, null, null, usePushPoolCompatibleFormat));
				} else {
					stmt.setBoolean(3, entry.ourResult);
					stmt.setBoolean(4, entry.upstreamResult);
				}
				stmt.setString(5, LogUtil.getCompatibleValue(entry.reason, null, null, usePushPoolCompatibleFormat));
				stmt.setString(6, entry.solution);
				if (numQueryParams >= 7) {
					if (usePushPoolCompatibleFormat)
						// stmt.setLong(9, entry.createTime);
						stmt.setTimestamp(7, new Timestamp(entry.createTime));
					else
						stmt.setTimestamp(7, new Timestamp(entry.createTime));
				}
				if (numQueryParams >= 8)
					stmt.setString(8, entry.source == null ? null : entry.source.getName());
				if (numQueryParams >= 9)
					stmt.setInt(9, entry.blocknum);
				if (numQueryParams >= 10) {
					stmt.setString(10, entry.solution.substring(8, 72));
				}
				stmt.addBatch();
			}
			int[] resultValues = stmt.executeBatch();

			// TODO add verification of query results and logging any individual
			// failures.

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
		
		if (Res.isDebug()) {
			float time = System.currentTimeMillis() - start;
			int rate = (int) (size / (time / 1000));
			Res.logInfo("Flushed " + size + " work results to DB in " + time + "ms (" + rate + "/sec)");
		}
	}

	/**
	 * @return the maxEntrysToQueueBeforeCommit
	 */
	public int getMaxEntrysToQueueBeforeCommit() {
		return maxEntrysToQueueBeforeCommit;
	}

	/**
	 * @param maxEntrysToQueueBeforeCommit
	 *            the maxEntrysToQueueBeforeCommit to set
	 */
	public void setMaxEntrysToQueueBeforeCommit(int maxEntrysToQueueBeforeCommit) {
		this.maxEntrysToQueueBeforeCommit = maxEntrysToQueueBeforeCommit;
	}

	/**
	 * @return the maxEntryAgeBeforeCommit
	 */
	public long getMaxEntryAgeBeforeCommit() {
		return maxEntryAgeBeforeCommit;
	}

	/**
	 * @param maxEntryAgeBeforeCommit
	 *            the maxEntryAgeBeforeCommit to set
	 */
	public void setMaxEntryAgeBeforeCommit(long maxEntryAgeBeforeCommit) {
		this.maxEntryAgeBeforeCommit = maxEntryAgeBeforeCommit;
	}

	/**
	 * @return the usePushPoolCompatibleFormat
	 */
	public boolean isUsePushPoolCompatibleFormat() {
		return usePushPoolCompatibleFormat;
	}

	/**
	 * @param usePushPoolCompatibleFormat
	 *            the usePushPoolCompatibleFormat to set
	 */
	public void setUsePushPoolCompatibleFormat(boolean usePushPoolCompatibleFormat) {
		this.usePushPoolCompatibleFormat = usePushPoolCompatibleFormat;
	}

	/**
	 * @return the flushFilesImmedate
	 */
	public boolean isFlushFilesImmedate() {
		return flushFilesImmedate;
	}

	/**
	 * @param flushFilesImmedate
	 *            the flushFilesImmedate to set
	 */
	public void setFlushFilesImmedate(boolean flushFilesImmedate) {
		this.flushFilesImmedate = flushFilesImmedate;
	}
	
	

	// /**
	// * @return the sql
	// */
	// public Sql getSql() {
	// return sql;
	// }
	//
	// /**
	// * @param sql the sql to set
	// */
	// public void setSql(Sql sql) {
	// this.sql = sql;
	// }

	/**
	 * @param dbFlushEngine the dbFlushEngine to set
	 */
	public void setDbFlushEngine(DefaultPreparedStatementSharesDBFlushEngine dbFlushEngine) {
		this.dbFlushEngine = dbFlushEngine;
	}

	/**
	 * @return the insertQuery
	 */
	public String getInsertQuery() {
		return insertQuery;
	}

	/**
	 * @param insertQuery
	 *            the insertQuery to set
	 */
	public void setInsertQuery(String insertQuery) {
		this.insertQuery = insertQuery;
		if (insertQuery == null) {
			numQueryParams = -1;
			return;
		}
		int count = 0;
		char findChar = '?';
		Sql sql = Conf.getSharesSql();
		if (sql != null && sql instanceof PostgreSql)
			findChar = '?';
		for (int i = 0; i < insertQuery.length(); i++) {
			if (insertQuery.charAt(i) == findChar)
				count++;
		}
		numQueryParams = count;
	}

	/**
	 * @return the outputFile
	 */
	public File getOutputFile() {
		return outputFile;
	}

	/**
	 * @param outputFile
	 *            the outputFile to set
	 */
	public void setOutputFile(File outputFile) {
		if (outputFile != null) {
			if (!outputFile.exists()) {
				if (outputFile.getParentFile() != null)
					outputFile.getParentFile().mkdirs();
				try {
					if (outputFile.createNewFile() && outputFile.canWrite()) {
						this.outputFile = outputFile;
					}
				} catch (IOException e) {
					Res.logError("could not create share log file: " + outputFile.getAbsolutePath(), e);
				}
			} else if (outputFile.canWrite()) {
				this.outputFile = outputFile;
			} else {
				Res.logError("Cannot write to share log file: " + outputFile.getAbsolutePath());
			}
		}
	}

	public int getNumQueryParams() {
		return numQueryParams;
	}

	public void setUpdateCounters(boolean updateCounters) {
		this.updateCounters = updateCounters;
	}


}
