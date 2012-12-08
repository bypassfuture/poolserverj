package com.shadworld.poolserver.db.shares.bulkload;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.lang.SystemUtils;

import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.db.shares.DefaultPreparedStatementSharesDBFlushEngine;
import com.shadworld.poolserver.db.shares.bulkload.behaviour.DBWriterBehaviour;
import com.shadworld.poolserver.db.shares.bulkload.behaviour.MySqlDBWriterBehaviour;
import com.shadworld.poolserver.db.shares.bulkload.behaviour.PostgresqlDBWriterBehaviour;
import com.shadworld.poolserver.logging.LogUtil;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareLogger;
import com.shadworld.sql.MySql;
import com.shadworld.sql.PostgreSql;
import com.shadworld.sql.Sql;

public abstract class AbstractBulkLoader extends DefaultPreparedStatementSharesDBFlushEngine {

	protected static final int ENGINE_MYSQL = 1;
	protected static final int ENGINE_POSTGRESSQL = 2;

	protected final int engine;

	private DBWriterBehaviour writerBehaviour;

	private static String NEW_LINE = System.getProperty("line.separator", "\n");
	private static Sql sql;
	
	protected File tmpFile;
	
	protected boolean fifoMode = false;

	private static String MYSQL_LOCAL_QUERY_START = "LOAD DATA LOCAL INFILE '";
	private static String MYSQL_LOCAL_QUERY_END = "' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";
	private static String MYSQL_LOCAL_COUNTERS_QUERY_END = "' INTO TABLE shares_counter (username, block_num, valid, stale, unknown, duplicate, efficiency)"
									+ "ON DUPLICATE KEY UPDATE valid = valid + VALUES(valid), stale = stale + VALUES(stale)"
									+ ", unknown = unknown + VALUES(unknown), duplicate = duplicate + VALUES(duplicate)"
									+ ", efficiency = VALUES(efficiency), hashrate_mh = (valid + stale + unknown + duplicate) / (NOW() - first_share_time) * 4295.967";

	// private static String POSTGRESQL_LOCAL_QUERY_START =
	// "COPY poolserverj_native.shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution) FROM";
	private static String POSTGRESQL_QUERY = " (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution) FROM";

	private String bulkLoadFilename;
	protected String bulkLoadQuery;
	protected String bulkUpdateQuery;

	ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {

		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, "db-stream-writer");
		}
	});

	public AbstractBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, null, extraParams);
		exec.execute(new Runnable() {
			
			@Override
			public void run() {
				//Dummy runnable just to create the handler early so it's not at the bottom of the debugger list.
			}
		});
		this.bulkLoadFilename = bulkLoadFilename;
		sql = Conf.getSharesSql();
		if (!useCompression()) {
			sql.close();
			sql.getJdbcOptionMap().put("useCompression", "false");
			try {
				Res.logDebug("Restarting Connection to DB URL: " + sql.getUrl());
				sql.prepareConnection();
			} catch (SQLException e) {
				Res.logError("Failed to turn off compression on bulkLoad connection. Connection failed", e);
				System.exit(1);
			}
		}
		if (sql instanceof MySql) {
			engine = ENGINE_MYSQL;
			writerBehaviour = new MySqlDBWriterBehaviour(this);
		} else if (sql instanceof PostgreSql) {
			engine = ENGINE_POSTGRESSQL;
			writerBehaviour = new PostgresqlDBWriterBehaviour(this);
		} else {
			engine = 0;
			Res.logError("Unsupported database engine for BulkLoader: " + sql.getClass().getName());
			System.exit(1);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.shadworld.poolserver.db.shares.
	 * DefaultPreparedStatementSharesDBFlushEngine
	 * #flushToDatabase(java.util.Collection, boolean, boolean,
	 * java.lang.String)
	 */
	@Override
	public void flushToDatabase(final Collection<ShareEntry> results) {
		int size = results.size();
		long start = System.currentTimeMillis();

		final String filename = getBulkFilename();

		// final String MYSQL_BULKLOAD_DIRECT_FIFO_QUERY =
		// "LOAD DATA INFILE '" + getBulkFilename() +
		// "' INTO TABLE shares (time, rem_host, username, source, block_num, our_result, upstream_result, reason, solution)";

		if (Res.isDebug())
			Res.logInfo("Doing database flush for shares: " + size);

		final CountDownLatch executeLatch = new CountDownLatch(1);
		final CountDownLatch writeLatch = new CountDownLatch(isFifoMode() ? 1 : 2);
		final CountDownLatch pipeConnectLatch = new CountDownLatch(this instanceof JavaPipesBulkLoader ? 1 : 0);
		
		try {

			sql.prepareConnection();

			final InputStream in = buildInputStream(filename, sql);

			Runnable writeThread = new Runnable() {
				public void run() {
					Writer writer = null;
					try {
						writeLatch.countDown();
						writer = buildOutputWriter(filename, in);
						pipeConnectLatch.countDown();
						writeResultsToStream(writer, results, true);

					} catch (IOException e1) {
						Res.logError("Failed to write to DB BulkLoader OutputStream", e1);

					} finally {
						if (writer != null)
							try {
								writer.close();
							} catch (IOException e) {
								Res.logError("Failed to close DB BulkLoader OutputStream", e);
							}
					}
					// release the block allowing load to start
					// if fifoMode this will already have been released.
					writeLatch.countDown();

					while (executeLatch.getCount() != 0) {
						try {
							// need to wait for the load to complete before the
							// handler
							// can be allowed to die. If using any form of pipe
							// the pipe
							// will be broken if this handler dies before the
							// other handler
							// finished reading.
							executeLatch.await();
						} catch (InterruptedException e) {
							// too bad
						}
					}
					//don't try to close the stream until the reader has finished with it.
					//correction stream must be closed or query will never finish executing because
					// it thinks there's more content in the 'file'
//					if (isFifoMode() && !isLocalMode() && writer != null)
//						try {
//							writer.close();
//						} catch (IOException e) {
//							Res.logError("Failed to close DB BulkLoader OutputStream", e);
//						}
				}
			};
			exec.execute(writeThread);
			//writeThread.start();

			while (writeLatch.getCount() != 0 || pipeConnectLatch.getCount() != 0) {
				try {
					writeLatch.await();
					pipeConnectLatch.await();
				} catch (InterruptedException e) {
					// too bad
				}
			}

			startDbWrite(sql.conn, sql.stmt, in);

			executeLatch.countDown();

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				int rate = (int) (size / (time / 1000));
				Res.logInfo("Flushed " + size + " shares to DB in " + time + "ms (" + rate + "/sec)");
			}

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} catch (IOException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (sql.stmt != null) {
				try {
					sql.stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
			//ensure the runnable is unblocked and can exit.
			while (executeLatch.getCount() > 0)
				executeLatch.countDown();
			while (writeLatch.getCount() > 0)
				writeLatch.countDown();
			if (isReplaceTmpFile() && !isFifoMode() && tmpFile != null && tmpFile.exists()) {
				//delete the temp file quickly.  If we are lucky it's still in the
				//file system's cache and will never need to be written to disk.
				tmpFile.delete();
			}
		}
	}

	/**
	 * Initiates DB write by executing appropriate SQL statements. Note for
	 * MySql: If using a PipedInputStream the actual LOAD query must be
	 * performed with Statement.execute(query). If you try to use a batch
	 * execute the inputstream will not be set correctly. This is an issue with
	 * mysql.
	 * 
	 * @param conn
	 * @param stmt
	 * @param in
	 * @throws IOException 
	 * @throws SQLException 
	 */
	protected void startDbWrite(Connection conn, Statement stmt, InputStream in) throws SQLException, IOException {
		//using a bit of indirection here so we don't have to create
		// a separate set of classes for each database engine.
		//you can skip the behaviour and put the write code directly in this
		// method if overriding.
		writerBehaviour.startDbWrite(conn, stmt, in);
	}

	/**
	 * Builds the InputStream used for local loads. This is usually a
	 * FileInputStream or a PipedInputStream
	 * 
	 * @param filename
	 *            filename if a FileInputStream is expected or null.
	 * @return
	 * @throws SQLException 
	 * @throws FileNotFoundException 
	 * @throws IOException 
	 */
	protected abstract InputStream buildInputStream(String filename, Sql sql) throws SQLException, FileNotFoundException, IOException;

	/**
	 * Build the Writer used for local or direct loads. This is usually a
	 * FileWriter or a PrintWriter backed by a PipedOutputStream.
	 * 
	 * @param filename
	 * @param in
	 *            a PipedInputStream which is needed to connect to a
	 *            PipedOutputStream if this is a pipe connection.
	 * @return
	 * @throws IOException 
	 */
	protected abstract Writer buildOutputWriter(String filename, InputStream in) throws IOException, FileNotFoundException;

	/**
	 * This method performs the writing of data to the stream i.e. produces the
	 * contents of 'file' that is being bulkloaded. It may or may not actually
	 * be a physical file depending on what the underlying stream is connected
	 * to.
	 * 
	 * @param writer
	 * @param results
	 * @param writeByLine
	 * @throws IOException
	 */
	protected void writeResultsToStream(final Writer writer, final Collection<ShareEntry> results,
			final boolean writeByLine) throws IOException {
		//using a bit of indirection here so we don't have to create
		// a separate set of classes for each database engine.
		//you can skip the behaviour and put the write code directly in this
		// method if overriding.
		writerBehaviour.writeResultsToStream(writer, results, writeByLine);
	}

	public String getBulkLoadQuery() {
		if (bulkLoadQuery != null)
			return bulkLoadQuery;
		if (engine == ENGINE_MYSQL) {
			bulkLoadQuery = MYSQL_LOCAL_QUERY_START + getBulkFilename() + MYSQL_LOCAL_QUERY_END;
			bulkLoadQuery = "LOAD DATA ";
			if (isLocalMode() || getBulkFilename() == null)
				bulkLoadQuery += "LOCAL ";
			bulkLoadQuery += "INFILE '" + getBulkFilename();
			bulkLoadQuery += MYSQL_LOCAL_QUERY_END;
		} else if (engine == ENGINE_POSTGRESSQL) {
			bulkLoadQuery = "COPY " + ((PostgreSql) sql).getDbSchema() + "." + "shares " + POSTGRESQL_QUERY;
			bulkLoadQuery += isLocalMode() ? " STDIN" : "'" + getBulkFilename() + "'";
		}
		return bulkLoadQuery;
	}
	
	public String getBulkUpdateQuery() {
		if (bulkUpdateQuery != null)
			return bulkLoadQuery;
		if (engine == ENGINE_MYSQL) {
			bulkUpdateQuery = MYSQL_LOCAL_QUERY_START + getBulkFilename() + MYSQL_LOCAL_QUERY_END;
			bulkUpdateQuery = "LOAD DATA ";
			if (isLocalMode() || getBulkFilename() == null)
				bulkUpdateQuery += "LOCAL ";
			bulkUpdateQuery += "INFILE '" + getBulkFilename();
			bulkUpdateQuery += MYSQL_LOCAL_QUERY_END;
		} else if (engine == ENGINE_POSTGRESSQL) {
			bulkUpdateQuery = "COPY " + ((PostgreSql) sql).getDbSchema() + "." + "shares " + POSTGRESQL_QUERY;
			bulkUpdateQuery += isLocalMode() ? " STDIN" : "'" + getBulkFilename() + "'";
		}
		return bulkLoadQuery;
	}

	/**
	 * only used by bulkloader methods that use either a local fifo or a serial
	 * write/read strategy. note: for internal java pipes this filename is
	 * ignored but the file must NOT exist in the file system or MySql will
	 * attempt to use it anyway.
	 * 
	 * @return filename or null if this bulkloader uses internal pipes;
	 */
	protected String getBulkFilename() {
		return bulkLoadFilename;
	}

	/**
	 * only used by bulkloader methods that use either a local fifo or a serial
	 * write/read strategy.
	 * 
	 * @return true if the stream written to can be read and written
	 *         simultaneously. False if the entire dataset must be written
	 *         before reading can begin.
	 */
	protected abstract boolean isFifoMode();
	
	public void setFifoMode(boolean fifoMode) {
		this.fifoMode = fifoMode;
		if (fifoMode && !SystemUtils.IS_OS_UNIX) {
			throw new IllegalArgumentException("Can only set fifoMode=true on Unix type OS's.  For windows servers please try serial write/read Bulkloader engines instead.");
		}
	}

	/**
	 * Whether the bulk load is happening is local mode. This means that data is
	 * sent across the database connection. This can be true whether or not the
	 * DB server is running on localhost. It can only false if the server is
	 * running on localhost and the data is being piped to a local file (or
	 * fifo) that the server is reading from.
	 * 
	 * @return
	 */
	public abstract boolean isLocalMode();

	/**
	 * only used by bulkloader methods that use either a local fifo or a serial
	 * write/read strategy.
	 * 
	 * @return true if the file is a normal file (and must be overwritten each
	 *         time). False if the file is a real file on the local filesystem.
	 */
	protected abstract boolean isReplaceTmpFile();

	/**
	 * whether to "ALTER TABLE shares DISABLE KEYS" (or postgres equivalent)
	 * before beginning upload
	 * 
	 * currently this is supported for mysql only
	 * 
	 * @return
	 */
	public abstract boolean isDisableKeys();
	
	/**
	 * whether to "SET UNIQUE_CHECKS=0" (or postgres equivalent)
	 * before beginning upload
	 * 
	 * currently this is supported for mysql only
	 * 
	 * @return
	 */
	public abstract boolean isDisableUniqueChecks();
	
	/**
	 * Whether to allow compression on the database connection. This MUST be
	 * false if any of the following conditions are true: - The driver is not
	 * Mysql<br>
	 * - The server is remote (due to a bug in the Mysql JDBC driver that causes
	 * connections to break if using LOAD DATA LOCAL INFILE on compressed
	 * connections).<br>
	 * - The server is local and you are using LOAD DATA LOCAL INFILE rather
	 * than LOAD DATA INFILE if this is the case you are probably doing it
	 * because you've problems allowing Mysql to access a file outside of it's
	 * data directory due to apparmor or SELinux<br>
	 * 
	 * @return
	 */
	protected abstract boolean useCompression();

	public void shutdown() {
		exec.shutdown();
	}
	
}
