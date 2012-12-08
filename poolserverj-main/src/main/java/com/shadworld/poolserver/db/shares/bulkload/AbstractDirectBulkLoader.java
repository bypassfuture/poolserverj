package com.shadworld.poolserver.db.shares.bulkload;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.sql.SQLException;

import org.apache.commons.lang.SystemUtils;

import com.shadworld.poolserver.logging.ShareLogger;
import com.shadworld.sql.Sql;

/**
 * parent class for direct loader.  This can only used if the Sql server is on the same
 * machine as the poolserver.
 * @author git
 *
 */
public abstract class AbstractDirectBulkLoader extends AbstractBulkLoader {

	
	
	public AbstractDirectBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, bulkLoadFilename, extraParams);
		// TODO Auto-generated constructor stub
	}

	@Override
	protected InputStream buildInputStream(String filename, Sql sql) throws SQLException {
		//not used in this implementation
		return null;
	}

	@Override
	protected Writer buildOutputWriter(String filename, InputStream in) throws IOException {
		if (tmpFile == null)
			tmpFile = new File(filename);
		if (!fifoMode && isReplaceTmpFile() && tmpFile.exists())
			tmpFile.delete();
		else if (!fifoMode) {
			tmpFile.getParentFile().mkdirs();
		}
		return new FileWriter(tmpFile);
	}

	@Override
	protected boolean isFifoMode() {
		return fifoMode;
	}
	

	@Override
	public boolean isLocalMode() {
		return false;
	}

	@Override
	protected abstract boolean isReplaceTmpFile();

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
		//compression won't help much since the data is not sent via the connection
		//but we don't need to disable it explicity.
		return false;
	}



}
