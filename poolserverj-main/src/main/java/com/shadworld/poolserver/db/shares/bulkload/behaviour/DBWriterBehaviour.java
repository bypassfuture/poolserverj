package com.shadworld.poolserver.db.shares.bulkload.behaviour;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Date;

import com.shadworld.poolserver.db.shares.bulkload.AbstractBulkLoader;
import com.shadworld.poolserver.logging.LogUtil;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareLogger;

public abstract class DBWriterBehaviour {
	
	final ShareLogger logger;
	AbstractBulkLoader bulkLoader;
	private static final String NEW_LINE = System.getProperty("line.separator", "\n");
	
	protected boolean initialised = false;
	protected boolean usePushPoolCompatibleFormat;
	protected int numQueryParams;
	
	
	public DBWriterBehaviour(AbstractBulkLoader bulkLoader) {
		super();
		this.bulkLoader = bulkLoader;
		this.logger = bulkLoader.getLogger();
	}

	/**
	 * This method performs the writing of data to the stream i.e. produces the contents of 'file'
	 * that is being bulkloaded.  It may or may not actually be a physical file depending on what
	 * the underlying stream is connected to.
	 * @param writer
	 * @param results
	 * @param writeByLine
	 * @throws IOException
	 */
	public void writeResultsToStream(final Writer writer, final Collection<ShareEntry> results, final boolean writeByLine) throws IOException {
		
		if (!initialised) {
			initialised = true;
			usePushPoolCompatibleFormat = logger.isUsePushPoolCompatibleFormat();
			numQueryParams = logger.getNumQueryParams();
		}
		
		final StringBuilder sb = new StringBuilder(writeByLine ? 380 : 380 * results.size());
		final char separator = usePushPoolCompatibleFormat ? ' ' : '\t';
		
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
	
	/**
	 * This method is the same as writeResultsToStream except that it is for counters.
	 * @param writer
	 * @param results
	 * @param writeByLine
	 * @throws IOException
	 */
	public void writeCountersToStream(final Writer writer, final Collection<ShareEntry> results, final boolean writeByLine) throws IOException {
		
		if (!initialised) {
			initialised = true;
			usePushPoolCompatibleFormat = logger.isUsePushPoolCompatibleFormat();
			numQueryParams = logger.getNumQueryParams();
		}
		
		final StringBuilder sb = new StringBuilder(writeByLine ? 380 : 380 * results.size());
		final char separator = usePushPoolCompatibleFormat ? ' ' : '\t';
		final String goodResult = usePushPoolCompatibleFormat ? "1 0 0 0 " : "1\t0\t0\t0\t";
		final String badResultPrefix = usePushPoolCompatibleFormat ? "0 " : "1\t";
		
		for (ShareEntry entry: results) {
			
			sb.append(entry.worker.getUsername()).append(separator);
			sb.append(entry.blocknum).append(separator);
			if (entry.ourResult) {
				sb.append(goodResult);
			} else {
				sb.append(badResultPrefix);
				sb.append(entry.reason.equals("stale") ? 1 : 0).append(separator);
				sb.append(entry.reason.equals("unknown-work") ? 1 : 0).append(separator);
				sb.append(entry.reason.equals("duplicate") ? 1 : 0).append(separator);
			}
			sb.append(entry.worker.efficiency());
			
			sb.append(NEW_LINE);
			
			if (writeByLine) {
				writer.append(sb.toString());
				sb.setLength(0);
			}
		}
		
		if (!writeByLine) {
			writer.append(sb.toString());
		}
		
	}
	
	/**
	 * Initiates DB write by executing appropriate SQL statements.
	 * Note for MySql:  If using a PipedInputStream the actual LOAD query must be performed with
	 * Statement.execute(query).  If you try to use a batch execute the inputstream will not be set
	 * correctly.  This is an issue with mysql. 
	 * @param conn
	 * @param stmt
	 * @param in
	 * @throws SQLException 
	 * @throws IOException 
	 */
	public abstract void startDbWrite(Connection conn, Statement stmt, InputStream in) throws SQLException, IOException;
}
