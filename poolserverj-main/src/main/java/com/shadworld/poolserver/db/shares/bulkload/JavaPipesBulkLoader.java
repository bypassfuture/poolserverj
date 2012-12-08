package com.shadworld.poolserver.db.shares.bulkload;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.sql.SQLException;

import com.mysql.jdbc.Statement;
import com.shadworld.poolserver.logging.ShareLogger;
import com.shadworld.sql.Sql;

/**
 * This implementation uses internal Java pipes to stream data to the server in local mode.
 * This is the most independent of platform and external configuration and performs almost
 * as fast as *nix pipes.
 * 
 * Read and write operations are performed concurrently (in fifo mode).  In some cases
 * this may be as fast or faster than LocalFifoBulkLoader.  You'll just have to experiment.
 * 
 * @author git
 *
 */
public class JavaPipesBulkLoader extends AbstractBulkLoader {

	public JavaPipesBulkLoader(ShareLogger logger, String bulkLoadFilename /*ignored*/, String[] extraParams) {
		super(logger, null, extraParams);
		
	}

	@Override
	protected InputStream buildInputStream(String filename, Sql sql) throws SQLException {
		final PipedInputStream pin = new PipedInputStream();
		if (engine == ENGINE_MYSQL) {
			final Statement stmt = (Statement) sql.stmt.unwrap(com.mysql.jdbc.Statement.class);
			stmt.setLocalInfileInputStream(pin);
		}
		return pin;
	}

	@Override
	protected Writer buildOutputWriter(String filename, InputStream in) throws IOException {
		final PipedOutputStream pout = new PipedOutputStream((PipedInputStream) in);
		return new PrintWriter(pout, true);
	}

	@Override
	protected String getBulkFilename() {
		return null;
	}

	@Override
	protected boolean isFifoMode() {
		return true;
	}

	@Override
	public boolean isLocalMode() {
		return true;
	}

	@Override
	protected boolean isReplaceTmpFile() {
		return false;
	}

	@Override
	public boolean isDisableKeys() {
		return true;
	}

	@Override
	public boolean isDisableUniqueChecks() {
		return true;
	}

	@Override
	protected boolean useCompression() {
		return false;
	}

}
